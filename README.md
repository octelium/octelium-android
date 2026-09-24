# Octelium for Android

The Octelium Android application. It is a native Kotlin/Jetpack Compose application that embeds
`liboctelium`, the Octelium client library implemented in Go in the
[Octelium repository](https://github.com/octelium/octelium/tree/main/client/liboctelium), and
drives it through the `octelium.api.client.mobile.v1` API over its C ABI.

## Architecture

```text
Presentation                Jetpack Compose
                            No credentials, no JNI handles
        │
        ├── gRPC-Kotlin ──► Octelium Cluster API (octelium-api.<domain>:443)
        │   OkHttp + TLS    ListService, ListNamespace, GetStatus
        │                   x-octelium-auth from GetAPICredential
        │
        │ LocalClient (mobilev1 protobuf)
        ▼
JNI shim                    liboctelium_jni.so (C)
                            dlopen, ABI check, thread attachment, callbacks
        │ C ABI
        ▼
liboctelium                 liboctelium.so (Go)
                            authentication, credentials, Connections,
                            WireGuard/QUIC, reconnects, encrypted state
        │ PlatformRequest.ApplyTunnelConfiguration
        ▼
VpnService                  OcteliumVpnService
                            VpnService.Builder, TUN FD, foreground notification,
                            underlying networks, always-on
```

The rule that the whole application follows is:

> Connection state belongs to liboctelium. Cluster state belongs to the Cluster API.
> Presentation belongs to Compose. The Android platform belongs to the host.

* The UI never sees refresh tokens. The Cluster API calls use the short-lived access token of
  `GetAPICredential`, cached in memory until 30 seconds before its expiry and renewed once upon
  `UNAUTHENTICATED`, exactly like the desktop application.
* The liboctelium state is encrypted with a random 32-byte key that is wrapped by a
  non-exportable Android Keystore AES-GCM key (StrongBox whenever available) and stored in
  `noBackupFilesDir`. Backups are disabled.
* The device identity is a random installation UUID. No hardware identifier is used.
* Every `ApplyTunnelConfiguration` is validated before being applied as a whole via
  `VpnService.Builder`. Default routes are refused, routes are canonicalized, the IP family
  that Octelium does not use is explicitly allowed to bypass the VPN and the application
  itself is excluded from the VPN so that the tunnel transport and the Cluster API traffic
  always use the underlying network. The previous TUN interface is only closed once
  liboctelium has duplicated the new one.
* Android cannot route DNS queries per domain. Whenever the DNS of a Connection is not
  disabled, the Cluster DNS servers resolve all the DNS queries of the apps routed through the
  VPN.
* The underlying network is tracked via `ConnectivityManager.registerDefaultNetworkCallback`,
  reported to liboctelium via `SetNetworkState` and to Android via `setUnderlyingNetworks`.
  Nothing is polled.
* The browser authentication uses Custom Tabs. The Portal redirects to
  `com.octelium.client:/callback/success` which is validated and passed to
  `CompleteAuthentication`.
* Before signing in, `octelium-api.<domain>` is resolved with the Android resolver, the one that
  liboctelium and the Cluster API client use. Android refuses DNS answers that include names that
  are not valid host names, such as a CNAME whose target label begins with `_`, even though
  browsers resolve them. Such a failure is reported before the browser is opened and the check
  can be repeated from the diagnostics.

## Repository layout

```text
core/                       pure Kotlin/JVM module
core/src/main/proto         the vendored Octelium protobuf APIs (scripts/sync-proto.sh)
core/.../local              LocalClient over the liboctelium transport, status and log stores
core/.../cluster            the Cluster API client and its credential cache
core/.../tunnel             TunnelConfiguration validation and the PlatformRequest handler
core/.../domain             the domain state helpers shared with the desktop semantics
app/                        the Android application
app/src/main/cpp            the JNI shim over the liboctelium C ABI
app/.../lib                 the Kotlin side of the JNI shim
app/.../runtime             the liboctelium lifecycle, state key and callbacks
app/.../vpn                 VpnService, the tunnel manager and the Connection controller
app/.../ui                  Connection, Services, Settings, Diagnostics
scripts/                    liboctelium, host library, protobuf and release helpers
liboctelium/                the prebuilt liboctelium libraries (not committed)
```

## Requirements

* JDK 21
* The Android SDK with the `platforms;android-37.0`, `build-tools;36.0.0`,
  `ndk;30.0.16248370` and `cmake;4.1.2` packages
* Go, as required by the `go.work` of the Octelium repository, in order to build liboctelium
* A checkout of the Octelium repository that includes `client/liboctelium`

## Building liboctelium

```bash
export ANDROID_HOME=/path/to/the/android/sdk
OCTELIUM_SOURCE_DIR=/path/to/octelium ./scripts/build-liboctelium.sh
```

The script builds `liboctelium.so` for `arm64-v8a` and `x86_64` into `liboctelium/`, records the
Octelium commit and verifies that every library is aligned for 16 KB page sizes. liboctelium is
always built in the production mode, which enforces the TLS verification of the Cluster even when
the Octelium revision is not a tagged release. `-Poctelium.libDir` points Gradle to another
directory.

## Building the application

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease
```

The release builds are minified with R8 and they are signed whenever
`OCTELIUM_ANDROID_KEYSTORE`, `OCTELIUM_ANDROID_KEYSTORE_PASSWORD`, `OCTELIUM_ANDROID_KEY_ALIAS` and
`OCTELIUM_ANDROID_KEY_PASSWORD` are set.

## Testing

```bash
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:lintDebug
```

The JNI integration tests run the JNI shim and the Kotlin client against the real liboctelium
built for the host. They are skipped unless the host libraries exist:

```bash
OCTELIUM_SOURCE_DIR=/path/to/octelium ./scripts/build-host-libs.sh
./gradlew :app:testDebugUnitTest --tests 'com.octelium.client.lib.*'
```

The UI tests run the whole application on Robolectric against a fake liboctelium and a fake
Cluster API. The screenshots of the screens in the light and the dark themes are recorded into
`app/build/outputs/roborazzi` with:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.octelium.client.ui.AppUITest' -Poctelium.screenshots=true
```

## Updating the protobuf APIs

`core/src/main/proto` is vendored out of the Octelium protobuf APIs repository together with the
commit it was taken from. Synchronize it whenever the mobile API changes:

```bash
OCTELIUM_PB_DIR=/path/to/the/protobuf/apis ./scripts/sync-proto.sh
```

## Workflows

`ci.yaml` runs the unit tests, the UI tests, lint and a debug build for every push and pull
request. It also builds liboctelium out of the current Octelium `main` branch for the host and runs
the JNI integration tests against it.

The manually triggered `build.yaml` workflow builds the application at any commit. It resolves the
current Octelium `main` commit (or any other branch, tag or commit given as `octelium_ref`), builds
liboctelium out of it, bundles it and uploads a debug APK, an unsigned release APK and AAB along
with the Octelium commit in `OCTELIUM_COMMIT`.

`release.yaml` runs for semantic `v*.*.*` tags. It verifies that `octelium.version` of
`gradle.properties` matches the tag, builds liboctelium out of the latest published Octelium
release, builds and verifies the signed APK and AAB, generates checksums and provenance and
publishes a GitHub release. Android cannot install unsigned APKs, hence the release fails unless
the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` secrets are set.

## Releasing

```bash
make release VERSION=0.2.0
make release-patch
```

The release helper bumps `octelium.version`, commits the change and creates the tag. Pushing the
tag triggers `release.yaml`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

The bundled Ubuntu font is licensed under the Ubuntu Font Licence 1.0 and the icons are derived
from Lucide under the ISC license. See `app/src/main/assets/licenses`.
