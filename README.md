# AGY Remote

Control the agy CLI (Google Antigravity) on your computer from your iPhone, Apple Watch or Android
phone, peer to peer. Chats, approvals (Allow / Deny from the notification), file attachments and
usage all go straight between the phone and the computer over an end-to-end encrypted iroh QUIC
connection; the push relay only ever sees encrypted, fixed-size notifications.

- iOS: [AGY Remote for Antigravity](https://apps.apple.com/app/id6839454324) (iOS 26, with a watchOS 26 app and complications)
- Android: [Google Play](https://play.google.com/store/apps/details?id=moe.n4tsu.agyremote) (Android 12+)
- Connector: macOS, Linux and Windows (x86_64 / arm64)

| Folder | What it is |
| --- | --- |
| `server/` | Rust Cargo workspace: `agy-connector` (the computer side: talks to `agy acp`, serves the app over iroh QUIC), the shared `agy-p2p-common`, the mobile bindings `agy-p2p-ffi` (iOS) and `agy-p2p-jni` (Android), and `vendor/` (patched `netdev`/`netwatch`, used via `[patch.crates-io]`) |
| `mobile/ios/` | iOS app (SwiftUI, iOS 26): `Sources/`, `NotificationService/` (decrypts pushes), `Shared/` (push crypto), `Watch*/` (watchOS app, widgets/complications), `project.yml` (xcodegen) |
| `mobile/android/` | Android app (Kotlin, Jetpack Compose) |
| `relay/` | Open-source push relay (Cloudflare Worker, AGPL-3.0) |
| `docs/` | Protocol, connector bridge design, localization, Android push setup |

## Status

App 1.2.0 is **under review** on both the App Store and Google Play; the store links above work
once it is approved. `agy-connector` 0.2.7 is the current connector release.

Until then, builds of the app are attached to the
[app-v1.2.0 release](https://github.com/NarYuki/agy-remote/releases/tag/app-v1.2.0):

- `AGYRemote-1.2.0-unsigned.ipa`: unsigned iOS build; sign it yourself (for example with your own
  Apple developer account) to install it.
- `AGYRemote-1.2.0.apk`: signed Android build, installable directly.

## Install the connector

The connector runs on the computer that has the agy CLI. On macOS or Linux (x86_64 or arm64) it
installs to `~/.local/bin` and needs no sudo:

```sh
curl -fsSL https://n4t.su/agy/ | sh
agy-connector up          # starts it in the background (shows no pairing code)
agy-connector pair        # pairs a phone: shows a QR code, then asks for the number the phone shows
agy-connector down        # stops it
```

To keep it running in the background (a LaunchAgent on macOS, a `systemd --user` service on Linux):

```sh
curl -fsSL https://n4t.su/agy/ | sh -s -- --service
```

On Windows (PowerShell; installs to `%LOCALAPPDATA%\Programs\agy-connector`, add `-Service` when
run as a file to start it at login):

```powershell
irm https://raw.githubusercontent.com/NarYuki/agy-remote/main/server/scripts/install.ps1 | iex
```

The installers download the latest GitHub Release for your OS and CPU, check that its manifest is
signed by the release key (Ed25519) and that the download matches the SHA-256 in it. The Linux
builds are static, so they run on any distribution.

Pairing: `agy-connector pair` shows a one-time code (QR or text) that works once, for ten
minutes. It holds the computer's address and a secret, no token. After the phone scans it, the
phone shows a 6-digit number made from both devices' keys; typing it into `agy-connector pair`
on the computer finishes pairing, and the phone gets a token of its own. The computer never
displays the number, so a photo or a shared view of its screen pairs nothing. See the paired phones with `agy-connector devices`,
remove one with `agy-connector unpair <id>`, or unpair every phone with
`agy-connector reset-pairing`. Turbo mode (every request allowed
without asking) can be picked on the phone; forbid it on this computer with
`agy-connector set-turbo off`. Chats started in your home folder or `/` are not offered for
file attachments unless you add that folder with `agy-connector add-workspace`. Installs from the release update
themselves after checking the same signature (`agy-connector set-auto-update off` turns that off,
`agy-connector update` checks now). `agy-connector check` tests the connection to agy, and
`agy-connector set-relay` points notifications at your own push relay.

## Building from source

Requirements: Xcode 26, Rust (stable, via rustup, with the iOS/Android targets you need),
[xcodegen](https://github.com/yonaskolb/XcodeGen), JDK 17 + Android SDK/NDK for Android, Node 20+ for the relay.

```sh
# Connector (macOS/Linux) -> server/target/release/agy-connector
server/scripts/build_connector.sh            # add --install to copy it to ~/.local/bin
server/target/release/agy-connector up
server/target/release/agy-connector pair     # pair the app
server/scripts/install_service.sh            # optional: run it at login (LaunchAgent / systemd --user)
(cd server && cargo test)

# iOS
mobile/ios/scripts/build_xcframework.sh      # Rust library -> mobile/ios/Frameworks/AgyP2PCore.xcframework
cd mobile/ios
cp Config/Signing.local.xcconfig.example Config/Signing.local.xcconfig   # set your DEVELOPMENT_TEAM
xcodegen generate
open AntigravityRemote.xcodeproj

# Android (see mobile/android/README.md)
cd mobile/android
./native/build-rust.sh                       # Rust library -> app/src/main/jniLibs
./gradlew :app:assembleDebug

# Relay
cd relay && npm install && npm test
```

Bundle identifiers (`moe.n4tsu.agyremote*`) are set in `mobile/ios/project.yml` and
`mobile/android/app/build.gradle.kts`; change them if you ship your own build. Push notifications
need your own relay and credentials: see [`relay/README.md`](relay/README.md) and
[`docs/push-android.md`](docs/push-android.md). Release signing reads its keystore and passwords
from Gradle properties / environment variables (`AGY_UPLOAD_*`), never from files in this repository.

## More

- [`docs/protocol.md`](docs/protocol.md) — the app ↔ connector protocol
- [`docs/bridge.md`](docs/bridge.md) — connector bridge requirements
- [`docs/releasing-connector.md`](docs/releasing-connector.md) — publishing a connector release and signing its update manifest
- [`docs/LOCALIZATION.md`](docs/LOCALIZATION.md) — adding languages and keeping text localizable
- [`relay/README.md`](relay/README.md) — running your own push relay
- [Privacy policy](https://n4t.su/agy/privacy) ([`docs/privacy.html`](docs/privacy.html))

## License

AGPL-3.0 — see `LICENSE`.
