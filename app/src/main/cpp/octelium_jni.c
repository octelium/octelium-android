#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "octelium.h"

typedef uint32_t (*abi_version_fn)(void);

typedef const char *(*version_fn)(void);

typedef const char *(*last_error_fn)(void);

typedef int32_t (*tunnel_new_fn)(uint32_t abi_version, const octelium_tunnel_opts_t *opts,
	uint64_t *tunnel);

typedef int32_t (*tunnel_set_config_fn)(uint64_t tunnel, const octelium_config_t *config);

typedef int32_t (*tunnel_set_network_state_fn)(uint64_t tunnel,
	const octelium_network_state_t *state);

typedef int32_t (*tunnel_complete_request_fn)(uint64_t tunnel, uint64_t request_id,
	const octelium_response_t *response);

typedef void (*tunnel_free_fn)(uint64_t tunnel);

typedef struct {
	void *handle;
	abi_version_fn abi_version;
	version_fn version;
	last_error_fn last_error;
	tunnel_new_fn tunnel_new;
	tunnel_set_config_fn tunnel_set_config;
	tunnel_set_network_state_fn tunnel_set_network_state;
	tunnel_complete_request_fn tunnel_complete_request;
	tunnel_free_fn tunnel_free;
} octelium_lib_t;

typedef struct {
	jobject callbacks;
} octelium_ctx_t;

typedef struct {
	void **ptrs;
	size_t *sizes;
	size_t len;
	size_t cap;
	int is_failed;
} arena_t;

static octelium_lib_t lib;
static pthread_mutex_t lib_mu = PTHREAD_MUTEX_INITIALIZER;

static JavaVM *jvm;
static pthread_key_t env_key;

static jclass string_class;
static jmethodID string_ctor;
static jmethodID string_get_bytes;
static jobject utf8_charset;

static jclass result_class;
static jmethodID result_ctor;

static jclass network_config_class;
static jmethodID network_config_ctor;

static jclass dns_config_class;
static jmethodID dns_config_ctor;

static jmethodID on_event_method;
static jmethodID on_request_method;

static struct {
	jfieldID domain;
	jfieldID mtu;
	jfieldID l3_mode;
	jfieldID x25519_key;
	jfieldID addresses;
	jfieldID gateways;
	jfieldID dns_servers;
	jfieldID cidr;
	jfieldID tunnel_mode;
	jfieldID dns_mode;
	jfieldID preferred_mtu;
	jfieldID keepalive_seconds;
} config_fields;

static struct {
	jfieldID v4;
	jfieldID v6;
} network_fields;

static struct {
	jfieldID id;
	jfieldID hostname;
	jfieldID addresses;
	jfieldID cidrs;
	jfieldID wireguard;
	jfieldID quicv0;
} gateway_fields;

static struct {
	jfieldID public_key;
	jfieldID port;
	jfieldID keepalive_seconds;
} wireguard_fields;

static struct {
	jfieldID port;
	jfieldID keepalive_seconds;
} quicv0_fields;

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

static void *arena_alloc(arena_t *a, size_t size) {
	if (a->is_failed) {
		return NULL;
	}

	if (a->len == a->cap) {
		size_t cap = a->cap == 0 ? 16 : a->cap * 2;

		void **ptrs = realloc(a->ptrs, cap * sizeof(void *));
		if (ptrs == NULL) {
			a->is_failed = 1;
			return NULL;
		}
		a->ptrs = ptrs;

		size_t *sizes = realloc(a->sizes, cap * sizeof(size_t));
		if (sizes == NULL) {
			a->is_failed = 1;
			return NULL;
		}
		a->sizes = sizes;

		a->cap = cap;
	}

	void *ret = calloc(1, size == 0 ? 1 : size);
	if (ret == NULL) {
		a->is_failed = 1;
		return NULL;
	}

	a->ptrs[a->len] = ret;
	a->sizes[a->len] = size;
	a->len++;

	return ret;
}

static void arena_free(arena_t *a) {
	for (size_t i = 0; i < a->len; i++) {
		volatile uint8_t *p = a->ptrs[i];
		for (size_t j = 0; j < a->sizes[i]; j++) {
			p[j] = 0;
		}
		free(a->ptrs[i]);
	}

	free(a->ptrs);
	free(a->sizes);
	memset(a, 0, sizeof(arena_t));
}

static int check_exception(JNIEnv *env, arena_t *a) {
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		a->is_failed = 1;
	}

	return a->is_failed;
}

static jstring new_string(JNIEnv *env, const char *arg) {
	if (arg == NULL) {
		return NULL;
	}

	size_t len = strlen(arg);
	if (len > INT32_MAX) {
		return NULL;
	}

	jbyteArray data = (*env)->NewByteArray(env, (jsize)len);
	if (data == NULL) {
		return NULL;
	}

	(*env)->SetByteArrayRegion(env, data, 0, (jsize)len, (const jbyte *)arg);

	jstring ret = (*env)->NewObject(env, string_class, string_ctor, data, utf8_charset);
	(*env)->DeleteLocalRef(env, data);

	return ret;
}

static const char *get_string(JNIEnv *env, arena_t *a, jstring arg) {
	if (arg == NULL || a->is_failed) {
		return NULL;
	}

	jbyteArray data = (*env)->CallObjectMethod(env, arg, string_get_bytes, utf8_charset);
	if (check_exception(env, a) || data == NULL) {
		a->is_failed = 1;
		return NULL;
	}

	jsize len = (*env)->GetArrayLength(env, data);

	char *ret = arena_alloc(a, (size_t)len + 1);
	if (ret != NULL) {
		(*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)ret);
	}

	(*env)->DeleteLocalRef(env, data);

	return ret;
}

static const char *get_string_field(JNIEnv *env, arena_t *a, jobject obj, jfieldID field) {
	if (a->is_failed) {
		return NULL;
	}

	jstring str = (*env)->GetObjectField(env, obj, field);
	const char *ret = get_string(env, a, str);
	(*env)->DeleteLocalRef(env, str);

	return ret;
}

static const char *const *get_string_array(JNIEnv *env, arena_t *a, jobjectArray arr, size_t *len) {
	*len = 0;

	if (arr == NULL || a->is_failed) {
		return NULL;
	}

	jsize n = (*env)->GetArrayLength(env, arr);
	if (n == 0) {
		return NULL;
	}

	const char **ret = arena_alloc(a, (size_t)n * sizeof(char *));
	if (ret == NULL) {
		return NULL;
	}

	for (jsize i = 0; i < n && !a->is_failed; i++) {
		jstring str = (*env)->GetObjectArrayElement(env, arr, i);
		ret[i] = get_string(env, a, str);
		(*env)->DeleteLocalRef(env, str);
	}

	*len = (size_t)n;

	return ret;
}

static const char *const *get_string_array_field(JNIEnv *env, arena_t *a, jobject obj, jfieldID field,
	size_t *len) {
	*len = 0;

	if (a->is_failed) {
		return NULL;
	}

	jobjectArray arr = (*env)->GetObjectField(env, obj, field);
	const char *const *ret = get_string_array(env, a, arr, len);
	(*env)->DeleteLocalRef(env, arr);

	return ret;
}

static void get_network(JNIEnv *env, arena_t *a, jobject obj, octelium_dual_stack_network_t *ret) {
	if (obj == NULL) {
		return;
	}

	ret->v4 = get_string_field(env, a, obj, network_fields.v4);
	ret->v6 = get_string_field(env, a, obj, network_fields.v6);
}

static void get_gateway(JNIEnv *env, arena_t *a, jobject obj, octelium_gateway_t *ret) {
	ret->id = get_string_field(env, a, obj, gateway_fields.id);
	ret->hostname = get_string_field(env, a, obj, gateway_fields.hostname);
	ret->addresses = get_string_array_field(env, a, obj, gateway_fields.addresses, &ret->addresses_len);
	ret->cidrs = get_string_array_field(env, a, obj, gateway_fields.cidrs, &ret->cidrs_len);

	if (a->is_failed) {
		return;
	}

	jobject wg = (*env)->GetObjectField(env, obj, gateway_fields.wireguard);
	if (wg != NULL) {
		octelium_gateway_wireguard_t *itm = arena_alloc(a, sizeof(octelium_gateway_wireguard_t));
		if (itm != NULL) {
			itm->public_key = get_string_field(env, a, wg, wireguard_fields.public_key);
			itm->port = (*env)->GetIntField(env, wg, wireguard_fields.port);
			itm->keepalive_seconds = (*env)->GetIntField(env, wg, wireguard_fields.keepalive_seconds);
			ret->wireguard = itm;
		}
		(*env)->DeleteLocalRef(env, wg);
	}

	if (a->is_failed) {
		return;
	}

	jobject quicv0 = (*env)->GetObjectField(env, obj, gateway_fields.quicv0);
	if (quicv0 != NULL) {
		octelium_gateway_quicv0_t *itm = arena_alloc(a, sizeof(octelium_gateway_quicv0_t));
		if (itm != NULL) {
			itm->port = (*env)->GetIntField(env, quicv0, quicv0_fields.port);
			itm->keepalive_seconds = (*env)->GetIntField(env, quicv0, quicv0_fields.keepalive_seconds);
			ret->quicv0 = itm;
		}
		(*env)->DeleteLocalRef(env, quicv0);
	}
}

static int get_config(JNIEnv *env, arena_t *a, jobject obj, octelium_config_t *cfg,
	octelium_connection_state_t *state, octelium_preferences_t *prefs) {
	memset(cfg, 0, sizeof(octelium_config_t));
	memset(state, 0, sizeof(octelium_connection_state_t));
	memset(prefs, 0, sizeof(octelium_preferences_t));

	cfg->domain = get_string_field(env, a, obj, config_fields.domain);
	cfg->state = state;
	cfg->preferences = prefs;

	state->mtu = (*env)->GetIntField(env, obj, config_fields.mtu);
	state->l3_mode = (uint32_t)(*env)->GetIntField(env, obj, config_fields.l3_mode);

	prefs->tunnel_mode = (uint32_t)(*env)->GetIntField(env, obj, config_fields.tunnel_mode);
	prefs->dns_mode = (uint32_t)(*env)->GetIntField(env, obj, config_fields.dns_mode);
	prefs->mtu = (*env)->GetIntField(env, obj, config_fields.preferred_mtu);
	prefs->keepalive_seconds = (*env)->GetIntField(env, obj, config_fields.keepalive_seconds);

	if (check_exception(env, a)) {
		return -1;
	}

	jbyteArray key = (*env)->GetObjectField(env, obj, config_fields.x25519_key);
	if (key != NULL) {
		jsize n = (*env)->GetArrayLength(env, key);
		uint8_t *ret = arena_alloc(a, (size_t)n);
		if (ret != NULL) {
			(*env)->GetByteArrayRegion(env, key, 0, n, (jbyte *)ret);
			state->x25519_key = ret;
			state->x25519_key_len = (size_t)n;
		}
		(*env)->DeleteLocalRef(env, key);
	}

	jobjectArray addresses = (*env)->GetObjectField(env, obj, config_fields.addresses);
	if (addresses != NULL && !a->is_failed) {
		jsize n = (*env)->GetArrayLength(env, addresses);
		octelium_dual_stack_network_t *ret = arena_alloc(a, (size_t)n * sizeof(octelium_dual_stack_network_t));
		if (ret != NULL) {
			for (jsize i = 0; i < n && !a->is_failed; i++) {
				jobject itm = (*env)->GetObjectArrayElement(env, addresses, i);
				get_network(env, a, itm, &ret[i]);
				(*env)->DeleteLocalRef(env, itm);
			}
			state->addresses = ret;
			state->addresses_len = (size_t)n;
		}
	}
	(*env)->DeleteLocalRef(env, addresses);

	jobjectArray gateways = (*env)->GetObjectField(env, obj, config_fields.gateways);
	if (gateways != NULL && !a->is_failed) {
		jsize n = (*env)->GetArrayLength(env, gateways);
		octelium_gateway_t *ret = arena_alloc(a, (size_t)n * sizeof(octelium_gateway_t));
		if (ret != NULL) {
			for (jsize i = 0; i < n && !a->is_failed; i++) {
				jobject itm = (*env)->GetObjectArrayElement(env, gateways, i);
				if (itm != NULL) {
					get_gateway(env, a, itm, &ret[i]);
				}
				(*env)->DeleteLocalRef(env, itm);
			}
			state->gateways = ret;
			state->gateways_len = (size_t)n;
		}
	}
	(*env)->DeleteLocalRef(env, gateways);

	state->dns_servers = get_string_array_field(env, a, obj, config_fields.dns_servers, &state->dns_servers_len);

	if (!a->is_failed) {
		jobject cidr = (*env)->GetObjectField(env, obj, config_fields.cidr);
		get_network(env, a, cidr, &state->cidr);
		(*env)->DeleteLocalRef(env, cidr);
	}

	return check_exception(env, a) ? -1 : 0;
}

static jobjectArray new_string_array(JNIEnv *env, const char *const *arr, size_t len) {
	if (len > INT32_MAX) {
		return NULL;
	}

	jobjectArray ret = (*env)->NewObjectArray(env, (jsize)len, string_class, NULL);
	if (ret == NULL) {
		return NULL;
	}

	for (size_t i = 0; i < len; i++) {
		jstring itm = new_string(env, arr[i]);
		if (itm == NULL) {
			(*env)->DeleteLocalRef(env, ret);
			return NULL;
		}
		(*env)->SetObjectArrayElement(env, ret, (jsize)i, itm);
		(*env)->DeleteLocalRef(env, itm);
	}

	return ret;
}

static int new_prefix_arrays(JNIEnv *env, const octelium_prefix_t *arr, size_t len,
	jobjectArray *addresses, jintArray *prefix_lens) {
	*addresses = NULL;
	*prefix_lens = NULL;

	if (len > INT32_MAX) {
		return -1;
	}

	*addresses = (*env)->NewObjectArray(env, (jsize)len, string_class, NULL);
	if (*addresses == NULL) {
		return -1;
	}

	*prefix_lens = (*env)->NewIntArray(env, (jsize)len);
	if (*prefix_lens == NULL) {
		return -1;
	}

	for (size_t i = 0; i < len; i++) {
		jstring itm = new_string(env, arr[i].address);
		if (itm == NULL) {
			return -1;
		}
		(*env)->SetObjectArrayElement(env, *addresses, (jsize)i, itm);
		(*env)->DeleteLocalRef(env, itm);

		jint prefix_len = (jint)arr[i].prefix_len;
		(*env)->SetIntArrayRegion(env, *prefix_lens, (jsize)i, 1, &prefix_len);
	}

	return 0;
}

static jobject new_dns_config(JNIEnv *env, const octelium_dns_config_t *dns) {
	jobject ret = NULL;
	jobjectArray search_domains = NULL;
	jobjectArray match_domains = NULL;

	jobjectArray servers = new_string_array(env, dns->servers, dns->servers_len);
	if (servers == NULL) {
		goto done;
	}

	search_domains = new_string_array(env, dns->search_domains, dns->search_domains_len);
	if (search_domains == NULL) {
		goto done;
	}

	match_domains = new_string_array(env, dns->match_domains, dns->match_domains_len);
	if (match_domains == NULL) {
		goto done;
	}

	ret = (*env)->NewObject(env, dns_config_class, dns_config_ctor, servers, search_domains, match_domains,
		(jboolean)(dns->match_all_domains != 0));

done:
	(*env)->DeleteLocalRef(env, servers);
	(*env)->DeleteLocalRef(env, search_domains);
	(*env)->DeleteLocalRef(env, match_domains);

	return ret;
}

static jobject new_network_config(JNIEnv *env, const octelium_network_config_t *cfg) {
	jobject ret = NULL;
	jobject dns = NULL;
	jobjectArray addresses = NULL;
	jintArray address_prefix_lens = NULL;
	jobjectArray routes = NULL;
	jintArray route_prefix_lens = NULL;

	if (new_prefix_arrays(env, cfg->addresses, cfg->addresses_len, &addresses, &address_prefix_lens) != 0) {
		goto done;
	}

	if (new_prefix_arrays(env, cfg->routes, cfg->routes_len, &routes, &route_prefix_lens) != 0) {
		goto done;
	}

	if (cfg->dns != NULL) {
		dns = new_dns_config(env, cfg->dns);
		if (dns == NULL) {
			goto done;
		}
	}

	ret = (*env)->NewObject(env, network_config_class, network_config_ctor, (jlong)cfg->generation,
		addresses, address_prefix_lens, routes, route_prefix_lens, dns, (jint)cfg->mtu);

done:
	(*env)->DeleteLocalRef(env, dns);
	(*env)->DeleteLocalRef(env, addresses);
	(*env)->DeleteLocalRef(env, address_prefix_lens);
	(*env)->DeleteLocalRef(env, routes);
	(*env)->DeleteLocalRef(env, route_prefix_lens);

	return ret;
}

static jobject new_result(JNIEnv *env, jint code, jlong handle, jlong ctx, const char *msg) {
	jstring str = new_string(env, msg);
	jobject ret = (*env)->NewObject(env, result_class, result_ctor, code, handle, ctx, str);
	(*env)->DeleteLocalRef(env, str);
	return ret;
}

static jobject new_last_error_result(JNIEnv *env, jint code) {
	return new_result(env, code, 0, 0, code == OCTELIUM_OK ? NULL : lib.last_error());
}

static void on_event(void *ctx, const octelium_event_t *ev) {
	octelium_ctx_t *c = ctx;

	JNIEnv *env = get_env();
	if (env == NULL) {
		return;
	}

	jstring msg = new_string(env, ev->message);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
	}

	(*env)->CallVoidMethod(env, c->callbacks, on_event_method, (jint)ev->type, (jint)ev->state,
		(jint)ev->error, (jint)ev->log_level, (jlong)ev->created_at, msg);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
	}

	(*env)->DeleteLocalRef(env, msg);
}

static void on_request(void *ctx, uint64_t request_id, const octelium_request_t *req) {
	octelium_ctx_t *c = ctx;

	JNIEnv *env = get_env();
	if (env == NULL) {
		return;
	}

	jobject cfg = NULL;
	if (req->type == OCTELIUM_REQUEST_APPLY_NETWORK_CONFIG && req->network_config != NULL) {
		cfg = new_network_config(env, req->network_config);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
		}
	}

	(*env)->CallVoidMethod(env, c->callbacks, on_request_method, (jlong)request_id, (jint)req->type, cfg);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
	}

	(*env)->DeleteLocalRef(env, cfg);
}

static int is_loaded(void) {
	pthread_mutex_lock(&lib_mu);
	int ret = lib.handle != NULL;
	pthread_mutex_unlock(&lib_mu);
	return ret;
}

static jclass find_class(JNIEnv *env, const char *name) {
	jclass cls = (*env)->FindClass(env, name);
	if (cls == NULL) {
		return NULL;
	}

	jclass ret = (*env)->NewGlobalRef(env, cls);
	(*env)->DeleteLocalRef(env, cls);

	return ret;
}

static int load_fields(JNIEnv *env) {
	jclass cls = (*env)->FindClass(env, "com/octelium/client/lib/NativeConfig");
	if (cls == NULL) {
		return -1;
	}

	config_fields.domain = (*env)->GetFieldID(env, cls, "domain", "Ljava/lang/String;");
	config_fields.mtu = (*env)->GetFieldID(env, cls, "mtu", "I");
	config_fields.l3_mode = (*env)->GetFieldID(env, cls, "l3Mode", "I");
	config_fields.x25519_key = (*env)->GetFieldID(env, cls, "x25519Key", "[B");
	config_fields.addresses = (*env)->GetFieldID(env, cls, "addresses",
		"[Lcom/octelium/client/lib/NativeDualStackNetwork;");
	config_fields.gateways = (*env)->GetFieldID(env, cls, "gateways", "[Lcom/octelium/client/lib/NativeGateway;");
	config_fields.dns_servers = (*env)->GetFieldID(env, cls, "dnsServers", "[Ljava/lang/String;");
	config_fields.cidr = (*env)->GetFieldID(env, cls, "cidr", "Lcom/octelium/client/lib/NativeDualStackNetwork;");
	config_fields.tunnel_mode = (*env)->GetFieldID(env, cls, "tunnelMode", "I");
	config_fields.dns_mode = (*env)->GetFieldID(env, cls, "dnsMode", "I");
	config_fields.preferred_mtu = (*env)->GetFieldID(env, cls, "preferredMTU", "I");
	config_fields.keepalive_seconds = (*env)->GetFieldID(env, cls, "keepAliveSeconds", "I");
	(*env)->DeleteLocalRef(env, cls);

	if ((*env)->ExceptionCheck(env)) {
		return -1;
	}

	cls = (*env)->FindClass(env, "com/octelium/client/lib/NativeDualStackNetwork");
	if (cls == NULL) {
		return -1;
	}

	network_fields.v4 = (*env)->GetFieldID(env, cls, "v4", "Ljava/lang/String;");
	network_fields.v6 = (*env)->GetFieldID(env, cls, "v6", "Ljava/lang/String;");
	(*env)->DeleteLocalRef(env, cls);

	if ((*env)->ExceptionCheck(env)) {
		return -1;
	}

	cls = (*env)->FindClass(env, "com/octelium/client/lib/NativeGateway");
	if (cls == NULL) {
		return -1;
	}

	gateway_fields.id = (*env)->GetFieldID(env, cls, "id", "Ljava/lang/String;");
	gateway_fields.hostname = (*env)->GetFieldID(env, cls, "hostname", "Ljava/lang/String;");
	gateway_fields.addresses = (*env)->GetFieldID(env, cls, "addresses", "[Ljava/lang/String;");
	gateway_fields.cidrs = (*env)->GetFieldID(env, cls, "cidrs", "[Ljava/lang/String;");
	gateway_fields.wireguard = (*env)->GetFieldID(env, cls, "wireguard",
		"Lcom/octelium/client/lib/NativeGatewayWireGuard;");
	gateway_fields.quicv0 = (*env)->GetFieldID(env, cls, "quicv0", "Lcom/octelium/client/lib/NativeGatewayQUICV0;");
	(*env)->DeleteLocalRef(env, cls);

	if ((*env)->ExceptionCheck(env)) {
		return -1;
	}

	cls = (*env)->FindClass(env, "com/octelium/client/lib/NativeGatewayWireGuard");
	if (cls == NULL) {
		return -1;
	}

	wireguard_fields.public_key = (*env)->GetFieldID(env, cls, "publicKey", "Ljava/lang/String;");
	wireguard_fields.port = (*env)->GetFieldID(env, cls, "port", "I");
	wireguard_fields.keepalive_seconds = (*env)->GetFieldID(env, cls, "keepAliveSeconds", "I");
	(*env)->DeleteLocalRef(env, cls);

	if ((*env)->ExceptionCheck(env)) {
		return -1;
	}

	cls = (*env)->FindClass(env, "com/octelium/client/lib/NativeGatewayQUICV0");
	if (cls == NULL) {
		return -1;
	}

	quicv0_fields.port = (*env)->GetFieldID(env, cls, "port", "I");
	quicv0_fields.keepalive_seconds = (*env)->GetFieldID(env, cls, "keepAliveSeconds", "I");
	(*env)->DeleteLocalRef(env, cls);

	return (*env)->ExceptionCheck(env) ? -1 : 0;
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

	string_class = find_class(env, "java/lang/String");
	result_class = find_class(env, "com/octelium/client/lib/NativeResult");
	network_config_class = find_class(env, "com/octelium/client/lib/NativeNetworkConfig");
	dns_config_class = find_class(env, "com/octelium/client/lib/NativeDNSConfig");
	if (string_class == NULL || result_class == NULL || network_config_class == NULL || dns_config_class == NULL) {
		return JNI_ERR;
	}

	string_ctor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/nio/charset/Charset;)V");
	string_get_bytes = (*env)->GetMethodID(env, string_class, "getBytes", "(Ljava/nio/charset/Charset;)[B");
	result_ctor = (*env)->GetMethodID(env, result_class, "<init>", "(IJJLjava/lang/String;)V");
	network_config_ctor = (*env)->GetMethodID(env, network_config_class, "<init>",
		"(J[Ljava/lang/String;[I[Ljava/lang/String;[ILcom/octelium/client/lib/NativeDNSConfig;I)V");
	dns_config_ctor = (*env)->GetMethodID(env, dns_config_class, "<init>",
		"([Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Z)V");
	if (string_ctor == NULL || string_get_bytes == NULL || result_ctor == NULL || network_config_ctor == NULL ||
		dns_config_ctor == NULL) {
		return JNI_ERR;
	}

	jclass charsets_class = (*env)->FindClass(env, "java/nio/charset/StandardCharsets");
	if (charsets_class == NULL) {
		return JNI_ERR;
	}

	jfieldID utf8_field = (*env)->GetStaticFieldID(env, charsets_class, "UTF_8", "Ljava/nio/charset/Charset;");
	if (utf8_field == NULL) {
		return JNI_ERR;
	}

	jobject charset = (*env)->GetStaticObjectField(env, charsets_class, utf8_field);
	utf8_charset = (*env)->NewGlobalRef(env, charset);
	(*env)->DeleteLocalRef(env, charset);
	(*env)->DeleteLocalRef(env, charsets_class);

	if (utf8_charset == NULL) {
		return JNI_ERR;
	}

	jclass callbacks_class = (*env)->FindClass(env, "com/octelium/client/lib/NativeCallbacks");
	if (callbacks_class == NULL) {
		return JNI_ERR;
	}

	on_event_method = (*env)->GetMethodID(env, callbacks_class, "onEvent", "(IIIIJLjava/lang/String;)V");
	on_request_method = (*env)->GetMethodID(env, callbacks_class, "onRequest",
		"(JILcom/octelium/client/lib/NativeNetworkConfig;)V");
	(*env)->DeleteLocalRef(env, callbacks_class);

	if (on_event_method == NULL || on_request_method == NULL || load_fields(env) != 0) {
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
		return new_string(env, err != NULL ? err : "Could not load liboctelium");
	}

	octelium_lib_t ret = {
		.handle = handle,
		.abi_version = (abi_version_fn)dlsym(handle, "octelium_abi_version"),
		.version = (version_fn)dlsym(handle, "octelium_version"),
		.last_error = (last_error_fn)dlsym(handle, "octelium_last_error"),
		.tunnel_new = (tunnel_new_fn)dlsym(handle, "octelium_tunnel_new"),
		.tunnel_set_config = (tunnel_set_config_fn)dlsym(handle, "octelium_tunnel_set_config"),
		.tunnel_set_network_state = (tunnel_set_network_state_fn)dlsym(handle, "octelium_tunnel_set_network_state"),
		.tunnel_complete_request = (tunnel_complete_request_fn)dlsym(handle, "octelium_tunnel_complete_request"),
		.tunnel_free = (tunnel_free_fn)dlsym(handle, "octelium_tunnel_free"),
	};

	if (ret.abi_version == NULL || ret.version == NULL || ret.last_error == NULL || ret.tunnel_new == NULL ||
		ret.tunnel_set_config == NULL || ret.tunnel_set_network_state == NULL ||
		ret.tunnel_complete_request == NULL || ret.tunnel_free == NULL) {
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

JNIEXPORT jint JNICALL Java_com_octelium_client_lib_Native_hostABIVersion(JNIEnv *env, jclass cls) {
	(void)env;
	(void)cls;

	return (jint)OCTELIUM_ABI_VERSION;
}

JNIEXPORT jstring JNICALL Java_com_octelium_client_lib_Native_version(JNIEnv *env, jclass cls) {
	(void)cls;

	if (!is_loaded()) {
		return NULL;
	}

	return new_string(env, lib.version());
}

JNIEXPORT jobject JNICALL Java_com_octelium_client_lib_Native_newTunnel(JNIEnv *env, jclass cls,
	jobject callbacks, jint log_level) {
	(void)cls;

	if (!is_loaded()) {
		return new_result(env, OCTELIUM_ERR_INVALID_STATE, 0, 0, "liboctelium is not loaded");
	}

	if (callbacks == NULL) {
		return new_result(env, OCTELIUM_ERR_INVALID_ARGUMENT, 0, 0, "The callbacks are not set");
	}

	octelium_ctx_t *ctx = calloc(1, sizeof(octelium_ctx_t));
	if (ctx == NULL) {
		return new_result(env, OCTELIUM_ERR_INTERNAL, 0, 0, "Could not allocate the tunnel context");
	}

	ctx->callbacks = (*env)->NewGlobalRef(env, callbacks);

	octelium_tunnel_opts_t opts = {
		.ctx = ctx,
		.on_event = on_event,
		.on_request = on_request,
		.protect_socket = NULL,
		.platform = OCTELIUM_PLATFORM_HOST,
		.log_level = (uint32_t)log_level,
		.device_name = NULL,
	};

	uint64_t tunnel = 0;

	int32_t code = lib.tunnel_new(OCTELIUM_ABI_VERSION, &opts, &tunnel);
	if (code != OCTELIUM_OK) {
		jobject ret = new_last_error_result(env, code);
		(*env)->DeleteGlobalRef(env, ctx->callbacks);
		free(ctx);
		return ret;
	}

	return new_result(env, OCTELIUM_OK, (jlong)tunnel, (jlong)(intptr_t)ctx, NULL);
}

JNIEXPORT jobject JNICALL Java_com_octelium_client_lib_Native_setConfig(JNIEnv *env, jclass cls,
	jlong tunnel, jobject config) {
	(void)cls;

	if (!is_loaded()) {
		return new_result(env, OCTELIUM_ERR_INVALID_STATE, 0, 0, "liboctelium is not loaded");
	}

	if (config == NULL) {
		return new_result(env, OCTELIUM_ERR_INVALID_ARGUMENT, 0, 0, "The config is not set");
	}

	arena_t a = { 0 };
	octelium_config_t cfg;
	octelium_connection_state_t state;
	octelium_preferences_t prefs;

	if (get_config(env, &a, config, &cfg, &state, &prefs) != 0) {
		arena_free(&a);
		return new_result(env, OCTELIUM_ERR_INTERNAL, 0, 0, "Could not read the config");
	}

	jobject ret = new_last_error_result(env, lib.tunnel_set_config((uint64_t)tunnel, &cfg));

	arena_free(&a);

	return ret;
}

JNIEXPORT jobject JNICALL Java_com_octelium_client_lib_Native_setNetworkState(JNIEnv *env, jclass cls,
	jlong tunnel, jboolean is_available, jstring id) {
	(void)cls;

	if (!is_loaded()) {
		return new_result(env, OCTELIUM_ERR_INVALID_STATE, 0, 0, "liboctelium is not loaded");
	}

	arena_t a = { 0 };

	octelium_network_state_t state = {
		.is_available = is_available ? 1 : 0,
		.id = get_string(env, &a, id),
	};

	if (a.is_failed) {
		arena_free(&a);
		return new_result(env, OCTELIUM_ERR_INTERNAL, 0, 0, "Could not read the network state");
	}

	jobject ret = new_last_error_result(env, lib.tunnel_set_network_state((uint64_t)tunnel, &state));

	arena_free(&a);

	return ret;
}

JNIEXPORT jobject JNICALL Java_com_octelium_client_lib_Native_completeRequest(JNIEnv *env, jclass cls,
	jlong tunnel, jlong request_id, jint result, jstring message, jint tun_fd, jstring access_token) {
	(void)cls;

	if (!is_loaded()) {
		return new_result(env, OCTELIUM_ERR_INVALID_STATE, 0, 0, "liboctelium is not loaded");
	}

	arena_t a = { 0 };

	octelium_response_t resp = {
		.result = result,
		.message = get_string(env, &a, message),
		.tun_fd = tun_fd,
		.access_token = get_string(env, &a, access_token),
	};

	if (a.is_failed) {
		arena_free(&a);
		return new_result(env, OCTELIUM_ERR_INTERNAL, 0, 0, "Could not read the response");
	}

	jobject ret = new_last_error_result(env,
		lib.tunnel_complete_request((uint64_t)tunnel, (uint64_t)request_id, &resp));

	arena_free(&a);

	return ret;
}

JNIEXPORT void JNICALL Java_com_octelium_client_lib_Native_freeTunnel(JNIEnv *env, jclass cls,
	jlong tunnel, jlong ctx) {
	(void)cls;

	if (!is_loaded()) {
		return;
	}

	lib.tunnel_free((uint64_t)tunnel);

	octelium_ctx_t *c = (octelium_ctx_t *)(intptr_t)ctx;
	if (c == NULL) {
		return;
	}

	(*env)->DeleteGlobalRef(env, c->callbacks);
	free(c);
}
