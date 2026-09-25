# Octelium for Android

The Octelium Android application. It is a native Kotlin/Jetpack Compose application that implements
the Octelium client, i.e. the authentication to the Cluster, the encrypted local state and the
Connect stream of the Cluster API, and that embeds
[liboctelium](https://github.com/octelium/liboctelium), the Octelium tunnel engine implemented in
Rust, through its C ABI.

## Architecture

```text
Presentation                Jetpack Compose
                            No credentials, no JNI handles
        │
        │ LocalClient (octelium.api.client.daemon.v1 state model)
        ▼
Octelium client             OcteliumClient (Kotlin, core)
                            domains, Operations, authentication, credentials,
                            encrypted state, Connect stream, API reconnects
        │
        ├── gRPC-Kotlin ──► Octelium Cluster API (octelium-api.<domain>:443)
        │   OkHttp + TLS    auth.v1: tokens, refresh, logout, Device registration
        │                   user.v1: Connect, Disconnect, GetStatus, ListService
        │
        │ Tunnel (complete desired Connection state)
        ▼
JNI shim                    liboctelium_jni.so (C)
                            dlopen, ABI check, thread attachment, callbacks
        │ C ABI (include/octelium.h)
        ▼
liboctelium                 liboctelium.so (Rust)
                            TUN packet I/O, WireGuard, QUICv0, Gateway reconnects
        │ OCTELIUM_REQUEST_APPLY_NETWORK_CONFIG
        ▼
VpnService                  OcteliumVpnService
                            VpnService.Builder, TUN FD, foreground notification,
                            underlying networks, always-on
```

The rule that the whole application follows is:

> The control plane belongs to Kotlin. The data plane belongs to liboctelium. Cluster state
> belongs to the Cluster API. Presentation belongs to Compose. The Android platform belongs to the
> host.

* The Kotlin client implements the semantics of the Octelium daemon: the domains, their
  authentication and Connection states, the lifecycle Operations, the settings and the errors are
  reported through the `octelium.api.client.daemon.v1` state model, exactly like the desktop
  application. At most one domain is connected at a time since Android provides a single VPN
  interface per application.
* The browser authentication uses Custom Tabs. The login request carries a PKCE code challenge and
  the Portal redirects to `com.octelium.client:/callback/success`. The callback is validated
  against the challenge before its authentication Token is redeemed together with the code
  verifier. The Device is registered to the Cluster after the authentication.
* The access token of a domain is renewed with its refresh token whenever it is needed and, while
  the domain is connected and the network is available, in the background ahead of its expiry
  (10 minutes before it, or halfway through the lifetime of the tokens that are valid for an hour
  or longer). A domain whose refresh token is rejected becomes signed out and its Connection
  requires authentication again. The UI never sees refresh tokens. The Cluster API calls of the UI
  use the short-lived access token, cached in memory until 30 seconds before its expiry and renewed
  once upon `UNAUTHENTICATED`, exactly like the desktop application.
* The credentials and the settings of the domains are persisted in the encrypted state format of
  the Octelium clients (`octelium.db`), encrypted with AES-256-GCM using a random 32-byte key that
  is wrapped by a non-exportable Android Keystore AES-GCM key (StrongBox whenever available). The
  state is stored in `noBackupFilesDir`. Backups are disabled.
* The device identity is a random installation UUID. No hardware identifier is used.
* A Connection maintains the Connect stream of the Cluster API. The Kotlin client reduces the
  initial state and every `AddGateway`, `UpdateGateway`, `DeleteGateway` and `UpdateDNS` event into
  the current Connection state and passes it as a whole to liboctelium which computes the
  difference by itself. The stream is reconnected with an exponential backoff while the tunnel of
  the Connection keeps running. liboctelium asks for the short-lived access token whenever it
  (re)connects to a QUICv0 Gateway. liboctelium never talks to the Cluster API, never persists
  anything and never sees a refresh token.
* Every network configuration requested by liboctelium is validated before being applied as a whole
  via `VpnService.Builder`. Default routes are refused, routes are canonicalized, the IP family that
  Octelium does not use is explicitly allowed to bypass the VPN and the application itself is
  excluded from the VPN so that the tunnel transport and the Cluster API traffic always use the
  underlying network. The previous TUN interface is only closed once liboctelium has duplicated the
  new one.
* Android cannot route DNS queries per domain. Whenever the DNS of a Connection is not
  disabled, the Cluster DNS servers resolve all the DNS queries of the apps routed through the
  VPN.
* The underlying network is tracked via `ConnectivityManager.registerDefaultNetworkCallback`,
  reported to liboctelium and to the reconnection backoff of the Connect stream, and reported to
  Android via `setUnderlyingNetworks`. Nothing is polled.
* Before signing in, `octelium-api.<domain>` is resolved with the Android resolver, the one that
  the Cluster API client uses. Android refuses DNS answers that include names that are not valid
  host names, such as a CNAME whose target label begins with `_`, even though browsers resolve
  them. Such a failure is reported before the browser is opened and the check can be repeated from
  the diagnostics.

## Repository layout

```text
core/                       pure Kotlin/JVM module
core/src/main/proto         the vendored Octelium protobuf APIs (scripts/sync-proto.sh)
core/.../client             the Octelium client: domains, Operations and settings
core/.../auth               the authentication, the session tokens and the Device registration
core/.../connect            the Connect stream and the reduction of the Connection state
core/.../db                 the encrypted local state
core/.../cluster            the Cluster API client of the UI and its credential cache
core/.../tunnel             the liboctelium tunnel interface and the network configuration validation
core/.../local              the local client interface, the status and the log stores
core/.../domain             the domain state helpers shared with the desktop semantics
app/                        the Android application
app/src/main/cpp            the JNI shim over the liboctelium C ABI and its vendored C header
app/.../lib                 the Kotlin side of the JNI shim
app/.../runtime             the liboctelium loading, the state key and the Octelium client
app/.../vpn                 VpnService, the tunnel manager and the Connection controller
app/.../ui                  Connection, Services, Settings, Diagnostics
scripts/                    liboctelium, host library, protobuf and release helpers
liboctelium/                the prebuilt liboctelium libraries (not committed)
```

## Requirements

* JDK 21
* The Android SDK with the `platforms;android-37.0`, `build-tools;36.0.0`,
  `ndk;30.0.16248370` and `cmake;4.1.2` packages
* Rust, as required by `rust-version` of liboctelium, with the `aarch64-linux-android` and
  `x86_64-linux-android` targets
* A checkout of the [liboctelium repository](https://github.com/octelium/liboctelium)

## Building liboctelium

```bash
export ANDROID_HOME=/path/to/the/android/sdk
rustup target add aarch64-linux-android x86_64-linux-android
LIBOCTELIUM_SOURCE_DIR=/path/to/liboctelium ./scripts/build-liboctelium.sh
```

The script builds `liboctelium.so` for `arm64-v8a` and `x86_64` into `liboctelium/`, records the
liboctelium commit and verifies that every library is aligned for 16 KB page sizes.
`-Poctelium.libDir` points Gradle to another directory.

The JNI shim is compiled against `app/src/main/cpp/octelium.h`, a copy of the C header of
liboctelium. The scripts refuse to build a liboctelium revision whose header differs from it.
Whenever the C ABI changes, copy `include/octelium.h` of liboctelium and update the JNI shim
accordingly.

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

The Octelium client tests run the authentication, the Connect stream and the Operations against
an in-process fake Cluster API and a fake tunnel. The encrypted state is tested against a state
written by the Octelium Go clients.

The JNI integration tests run the JNI shim and the Kotlin tunnel against the real liboctelium built
for the host. They are skipped unless the host libraries exist:

```bash
LIBOCTELIUM_SOURCE_DIR=/path/to/liboctelium ./scripts/build-host-libs.sh
./gradlew :app:testDebugUnitTest --tests 'com.octelium.client.lib.*'
```

The UI tests run the whole application on Robolectric against a fake Octelium client and a fake
Cluster API. The screenshots of the screens in the light and the dark themes are recorded into
`app/build/outputs/roborazzi` with:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.octelium.client.ui.AppUITest' -Poctelium.screenshots=true
```

## Updating the protobuf APIs

`core/src/main/proto` is vendored out of the Octelium protobuf APIs repository together with the
commit it was taken from. Synchronize it whenever the Cluster, the daemon or the client state APIs
change:

```bash
OCTELIUM_PB_DIR=/path/to/the/protobuf/apis ./scripts/sync-proto.sh
```

## Workflows

`ci.yaml` runs the unit tests, the UI tests, lint and a debug build for every push and pull
request. It also builds liboctelium out of the current liboctelium `main` branch for the host and
runs the JNI integration tests against it.

The manually triggered `build.yaml` workflow builds the application at any commit. It resolves the
current liboctelium `main` commit (or any other branch, tag or commit given as `liboctelium_ref`),
builds liboctelium out of it, bundles it and uploads a debug APK, an unsigned release APK and AAB
along with the liboctelium commit in `LIBOCTELIUM_COMMIT`.

`release.yaml` runs for semantic `v*.*.*` tags. It verifies that `octelium.version` of
`gradle.properties` matches the tag, builds liboctelium out of the latest published liboctelium
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
from Lucide under the ISC license. See `app/src/main/assets/licenses`. liboctelium embeds
[GotaTun](https://github.com/mullvad/gotatun), which is licensed under MPL-2.0.
