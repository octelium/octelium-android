#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define CODE_INVALID_ARGUMENT 3
#define CODE_FAILED_PRECONDITION 9
#define CODE_INTERNAL 13

typedef void (*octelium_event_fn)(void *ctx, const uint8_t *data, size_t data_len);

typedef void (*octelium_request_fn)(void *ctx, uint64_t request_id,
	const uint8_t *data, size_t data_len);

typedef struct {
	void *ctx;
	octelium_event_fn on_event;
	octelium_request_fn on_request;
} octelium_callbacks_t;

typedef uint32_t (*abi_version_fn)(void);

typedef int32_t (*client_new_fn)(uint8_t *config, size_t config_len,
	octelium_callbacks_t *callbacks, uint64_t *client, uint8_t **out, size_t *out_len);

typedef int32_t (*client_call_fn)(uint64_t client, char *method,
	uint8_t *req, size_t req_len, uint8_t **out, size_t *out_len);

typedef int32_t (*client_complete_request_fn)(uint64_t client, uint64_t request_id,
	uint8_t *resp, size_t resp_len);

typedef void (*client_free_fn)(uint64_t client);

typedef void (*free_fn)(void *ptr);

typedef struct {
	void *handle;
	abi_version_fn abi_version;
	client_new_fn client_new;
	client_call_fn client_call;
	client_complete_request_fn client_complete_request;
	client_free_fn client_free;
	free_fn free;
} octelium_lib_t;

typedef struct {
	jobject callbacks;
} octelium_ctx_t;

static octelium_lib_t lib;
static pthread_mutex_t lib_mu = PTHREAD_MUTEX_INITIALIZER;

static JavaVM *jvm;
static pthread_key_t env_key;

static jclass result_class;
static jmethodID result_ctor;
static jmethodID on_event_method;
static jmethodID on_request_method;

static void detach_thread(void *arg) {
	(void)arg;
	(*jvm)->DetachCurrentThread(jvm);
}

static JNIEnv *get_env(void) {
	JNIEnv *env = NULL;

	jint ret = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6);
	if (ret == JNI_OK) {
		return env;
	}

	if (ret != JNI_EDETACHED) {
		return NULL;
	}

	JavaVMAttachArgs args = {
		.version = JNI_VERSION_1_6,
		.name = "liboctelium",
		.group = NULL,
	};

	if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, (void *)&env, &args) != JNI_OK) {
		return NULL;
	}

	pthread_setspecific(env_key, env);

	return env;
}

static jbyteArray new_byte_array(JNIEnv *env, const uint8_t *data, size_t data_len) {
	if (data_len > INT32_MAX) {
		return NULL;
	}

	jbyteArray ret = (*env)->NewByteArray(env, (jsize)data_len);
	if (ret == NULL) {
		return NULL;
	}

	if (data_len > 0) {
		(*env)->SetByteArrayRegion(env, ret, 0, (jsize)data_len, (const jbyte *)data);
	}

	return ret;
}

static uint8_t *get_bytes(JNIEnv *env, jbyteArray arr, size_t *len) {
	*len = 0;

	if (arr == NULL) {
		return NULL;
	}

	jsize n = (*env)->GetArrayLength(env, arr);
	if (n <= 0) {
		return NULL;
	}

	uint8_t *ret = malloc((size_t)n);
	if (ret == NULL) {
		return NULL;
	}

	(*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)ret);
	*len = (size_t)n;

	return ret;
}

static jobject new_result(JNIEnv *env, jint code, jlong handle, jlong ctx, jbyteArray data) {
	return (*env)->NewObject(env, result_class, result_ctor, code, handle, ctx, data);
}

static jobject new_error_result(JNIEnv *env, jint code, const char *msg) {
	jbyteArray data = new_byte_array(env, (const uint8_t *)msg, strlen(msg));
	jobject ret = new_result(env, code, 0, 0, data);
	(*env)->DeleteLocalRef(env, data);
	return ret;
}

static jbyteArray take_output(JNIEnv *env, uint8_t *out, size_t out_len) {
	if (out == NULL) {
		return NULL;
	}

	jbyteArray ret = new_byte_array(env, out, out_len);
	lib.free(out);

	return ret;
}

static void on_event(void *ctx, const uint8_t *data, size_t data_len) {
	octelium_ctx_t *c = ctx;

	JNIEnv *env = get_env();
	if (env == NULL) {
		return;
	}

	jbyteArray arr = new_byte_array(env, data, data_len);
	if (arr == NULL) {
		(*env)->ExceptionClear(env);
		return;
	}

	(*env)->CallVoidMethod(env, c->callbacks, on_event_method, arr);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
	}

	(*env)->DeleteLocalRef(env, arr);
}

static void on_request(void *ctx, uint64_t request_id, const uint8_t *data, size_t data_len) {
	octelium_ctx_t *c = ctx;

	JNIEnv *env = get_env();
	if (env == NULL) {
		return;
	}

	jbyteArray arr = new_byte_array(env, data, data_len);
	if (arr == NULL) {
		(*env)->ExceptionClear(env);
		return;
	}

	(*env)->CallVoidMethod(env, c->callbacks, on_request_method, (jlong)request_id, arr);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
	}

	(*env)->DeleteLocalRef(env, arr);
}

static int is_loaded(void) {
	pthread_mutex_lock(&lib_mu);
	int ret = lib.handle != NULL;
	pthread_mutex_unlock(&lib_mu);
	return ret;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
	(void)reserved;

	jvm = vm;

	JNIEnv *env = NULL;
	if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
		return JNI_ERR;
	}

	if (pthread_key_create(&env_key, detach_thread) != 0) {
		return JNI_ERR;
	}

	jclass cls = (*env)->FindClass(env, "com/octelium/client/lib/NativeResult");
	if (cls == NULL) {
		return JNI_ERR;
	}

	result_class = (*env)->NewGlobalRef(env, cls);
	(*env)->DeleteLocalRef(env, cls);

	result_ctor = (*env)->GetMethodID(env, result_class, "<init>", "(IJJ[B)V");
	if (result_ctor == NULL) {
		return JNI_ERR;
	}

	jclass callbacks_class = (*env)->FindClass(env, "com/octelium/client/lib/NativeCallbacks");
	if (callbacks_class == NULL) {
		return JNI_ERR;
	}

	on_event_method = (*env)->GetMethodID(env, callbacks_class, "onEvent", "([B)V");
	on_request_method = (*env)->GetMethodID(env, callbacks_class, "onRequest", "(J[B)V");
	(*env)->DeleteLocalRef(env, callbacks_class);

	if (on_event_method == NULL || on_request_method == NULL) {
		return JNI_ERR;
	}

	return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL Java_com_octelium_client_lib_Native_open(JNIEnv *env, jclass cls, jstring path) {
	(void)cls;

	pthread_mutex_lock(&lib_mu);

	if (lib.handle != NULL) {
		pthread_mutex_unlock(&lib_mu);
		return NULL;
	}

	const char *p = (*env)->GetStringUTFChars(env, path, NULL);
	if (p == NULL) {
		pthread_mutex_unlock(&lib_mu);
		return (*env)->NewStringUTF(env, "Invalid liboctelium path");
	}

	void *handle = dlopen(p, RTLD_NOW);
	(*env)->ReleaseStringUTFChars(env, path, p);

	if (handle == NULL) {
		const char *err = dlerror();
		pthread_mutex_unlock(&lib_mu);
		return (*env)->NewStringUTF(env, err != NULL ? err : "Could not load liboctelium");
	}

	octelium_lib_t ret = {
		.handle = handle,
		.abi_version = (abi_version_fn)dlsym(handle, "octelium_abi_version"),
		.client_new = (client_new_fn)dlsym(handle, "octelium_client_new"),
		.client_call = (client_call_fn)dlsym(handle, "octelium_client_call"),
		.client_complete_request = (client_complete_request_fn)dlsym(handle, "octelium_client_complete_request"),
		.client_free = (client_free_fn)dlsym(handle, "octelium_client_free"),
		.free = (free_fn)dlsym(handle, "octelium_free"),
	};

	if (ret.abi_version == NULL || ret.client_new == NULL || ret.client_call == NULL ||
		ret.client_complete_request == NULL || ret.client_free == NULL || ret.free == NULL) {
		dlclose(handle);
		pthread_mutex_unlock(&lib_mu);
		return (*env)->NewStringUTF(env, "liboctelium does not export the expected C ABI");
	}

	lib = ret;

	pthread_mutex_unlock(&lib_mu);

	return NULL;
}

JNIEXPORT jint JNICALL Java_com_octelium_client_lib_Native_abiVersion(JNIEnv *env, jclass cls) {
	(void)env;
	(void)cls;

	if (!is_loaded()) {
		return -1;
	}

	return (jint)lib.abi_version();
}

JNIEXPORT jobject JNICALL Java_com_octelium_client_lib_Native_newClient(JNIEnv *env, jclass cls,
	jbyteArray config, jobject callbacks) {
	(void)cls;

	if (!is_loaded()) {
		return new_error_result(env, CODE_FAILED_PRECONDITION, "liboctelium is not loaded");
	}

	if (callbacks == NULL) {
		return new_error_result(env, CODE_INVALID_ARGUMENT, "The callbacks are not set");
	}

	octelium_ctx_t *ctx = calloc(1, sizeof(octelium_ctx_t));
	if (ctx == NULL) {
		return new_error_result(env, CODE_INTERNAL, "Could not allocate the client context");
	}

	ctx->callbacks = (*env)->NewGlobalRef(env, callbacks);

	octelium_callbacks_t cb = {
		.ctx = ctx,
		.on_event = on_event,
		.on_request = on_request,
	};

	size_t config_len = 0;
	uint8_t *config_bytes = get_bytes(env, config, &config_len);

	uint64_t client = 0;
	uint8_t *out = NULL;
	size_t out_len = 0;

	int32_t code = lib.client_new(config_bytes, config_len, &cb, &client, &out, &out_len);
	free(config_bytes);

	jbyteArray data = take_output(env, out, out_len);

	if (code != 0) {
		(*env)->DeleteGlobalRef(env, ctx->callbacks);
		free(ctx);
		jobject ret = new_result(env, code, 0, 0, data);
		(*env)->DeleteLocalRef(env, data);
		return ret;
	}

	jobject ret = new_result(env, code, (jlong)client, (jlong)(intptr_t)ctx, data);
	(*env)->DeleteLocalRef(env, data);

	return ret;
}

JNIEXPORT jobject JNICALL Java_com_octelium_client_lib_Native_call(JNIEnv *env, jclass cls,
	jlong client, jstring method, jbyteArray req) {
	(void)cls;

	if (!is_loaded()) {
		return new_error_result(env, CODE_FAILED_PRECONDITION, "liboctelium is not loaded");
	}

	if (method == NULL) {
		return new_error_result(env, CODE_INVALID_ARGUMENT, "The method is not set");
	}

	const char *m = (*env)->GetStringUTFChars(env, method, NULL);
	if (m == NULL) {
		return new_error_result(env, CODE_INTERNAL, "Could not read the method");
	}

	size_t req_len = 0;
	uint8_t *req_bytes = get_bytes(env, req, &req_len);

	uint8_t *out = NULL;
	size_t out_len = 0;

	int32_t code = lib.client_call((uint64_t)client, (char *)m, req_bytes, req_len, &out, &out_len);

	free(req_bytes);
	(*env)->ReleaseStringUTFChars(env, method, m);

	jbyteArray data = take_output(env, out, out_len);
	jobject ret = new_result(env, code, 0, 0, data);
	(*env)->DeleteLocalRef(env, data);

	return ret;
}

JNIEXPORT jint JNICALL Java_com_octelium_client_lib_Native_completeRequest(JNIEnv *env, jclass cls,
	jlong client, jlong request_id, jbyteArray resp) {
	(void)cls;

	if (!is_loaded()) {
		return CODE_FAILED_PRECONDITION;
	}

	size_t resp_len = 0;
	uint8_t *resp_bytes = get_bytes(env, resp, &resp_len);

	int32_t code = lib.client_complete_request((uint64_t)client, (uint64_t)request_id, resp_bytes, resp_len);
	free(resp_bytes);

	return (jint)code;
}

JNIEXPORT void JNICALL Java_com_octelium_client_lib_Native_freeClient(JNIEnv *env, jclass cls,
	jlong client, jlong ctx) {
	(void)cls;

	if (!is_loaded()) {
		return;
	}

	lib.client_free((uint64_t)client);

	octelium_ctx_t *c = (octelium_ctx_t *)(intptr_t)ctx;
	if (c == NULL) {
		return;
	}

	(*env)->DeleteGlobalRef(env, c->callbacks);
	free(c);
}
