# AGY Remote for Android

Kotlin + Jetpack Compose (Material 3 as a base, matching the iOS app), minSdk 31, targetSdk 36.
Talks protocol v1 (`../../docs/protocol.md`) to agy-connector over iroh QUIC through the Rust library in
`server/agy-p2p-jni`.

## Build

Requirements: Android SDK (platform 36), JDK 17+ (Android Studio's bundled JBR works), and for the
native part the Android NDK plus rustup with the Android targets.

```sh
# 1. Native P2P library -> app/src/main/jniLibs/<abi>/libagy_p2p_jni.so
rustup target add aarch64-linux-android x86_64-linux-android   # once
./native/build-rust.sh                 # ABIS="arm64-v8a x86_64" PROFILE=release by default
# (or: ./gradlew buildRust)

# 2. App
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # if no system JDK
./gradlew assembleDebug
```

`build-rust.sh` uses the NDK's clang directly (no cargo-ndk needed) and rustup's `cargo`/`rustc`
(a Homebrew Rust has no Android std). The NDK is taken from `ANDROID_NDK_HOME`, else the newest one
under `$ANDROID_HOME/ndk` or `~/Library/Android/sdk/ndk`.

Without the `.so` the app still builds and runs: `NativeP2P.isAvailable` is false and the app uses
`UnavailableTransport`, which fails every request cleanly (useful for UI work).

## Layout

- `net/` — `NativeP2P` (JNI), `P2PTransport`, protocol types (`Protocol.kt`), `ConnectorClient` (typed calls; `turn.start` as a Flow).
- `state/` — `WorkspaceViewModel` (connection with 5 s retries, chats, turns, options, toasts), `Store` (prefs), `Haptics`.
- `ui/theme` — design tokens (colours, type, metrics, springs).
- `ui/effects` — AGSL backdrop curtains and header glow (gradient fallback on Android 12).
- `ui/setup` — the setup pages (ported from the iOS `LandingView`).
- `ui/workspace` — header, transcript, zero state, mode chips, composer, sidebar drawer.
- `ui/chat` — Markdown, code blocks, activity/plan/approval cards, notices.
- `ui/sheets` — model picker, usage, Add to Agy, settings, computers, search, QR scanner, notifications explainer, folder picker.

## Strings

`app/src/main/res/values*/strings_catalog.xml` is generated from the iOS catalogs for all 20
languages — re-run after the catalogs change:

```sh
python3 tools/convert_xcstrings.py ../ios/Sources/Resources app/src/main/res
```

Strings the iOS catalog doesn't have yet (Settings rows, sheet titles, …) are in
`res/values/strings.xml`, English only.
