import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val octeliumVersion = providers.gradleProperty("octelium.version").get()
val releaseTag = providers.environmentVariable("OCTELIUM_ANDROID_RELEASE_TAG").orElse("").get()
val libDir = file(providers.gradleProperty("octelium.libDir").orElse(rootProject.file("liboctelium").path).get())

fun readLibInfo(name: String): String {
    val f = File(libDir, name)
    return if (f.exists()) f.readText().trim() else ""
}

fun getVersionCode(version: String): Int {
    val (major, minor, patch) = version.split(".").map { it.toInt() }
    return major * 1_000_000 + minor * 1_000 + patch
}

fun toBuildConfigString(arg: String): String = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.octelium.client"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.octelium.client"
        minSdk = 29
        targetSdk = 36
        versionCode = getVersionCode(octeliumVersion)
        versionName = octeliumVersion

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("String", "RELEASE_TAG", toBuildConfigString(releaseTag))
        buildConfigField("String", "LIBOCTELIUM_COMMIT", toBuildConfigString(readLibInfo("LIBOCTELIUM_COMMIT")))
        buildConfigField("String", "LIBOCTELIUM_REF", toBuildConfigString(readLibInfo("LIBOCTELIUM_REF")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(libDir)
        }
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("OCTELIUM_ANDROID_KEYSTORE")
            if (!keystore.isNullOrEmpty()) {
                storeFile = file(keystore)
                storePassword = System.getenv("OCTELIUM_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OCTELIUM_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("OCTELIUM_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (!System.getenv("OCTELIUM_ANDROID_KEYSTORE").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val hostLibDir = providers.environmentVariable("OCTELIUM_HOST_LIB_DIR")
    .orElse(rootProject.layout.buildDirectory.dir("host-libs").map { it.asFile.path })

val isScreenshotsRecorded = providers.gradleProperty("octelium.screenshots").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("octelium.hostLibDir", hostLibDir.get())
    systemProperty("roborazzi.test.record", isScreenshotsRecorded.get())
    systemProperty("roborazzi.output.dir", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.path)
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":core"))

    implementation(libs.grpc.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
