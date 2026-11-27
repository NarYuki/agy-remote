# Releasing agy-connector

Releases are built and published from the maintainer's Mac; no hosted CI is involved. The release
signing key stays offline and is only used locally.

## 1. Build and publish

Bump `version` in `server/agy-connector/Cargo.toml`, commit and push, then:

```sh
server/scripts/release_local.sh            # builds into server/target/dist
```

It builds Linux x86_64/aarch64 (static musl, via cargo-zigbuild) and macOS arm64/x86_64:

| Asset | Used by |
| --- | --- |
| `agy-connector-<os>-<arch>.tar.gz` | `install.sh` (`curl -fsSL https://n4t.su/agy/ \| sh`) |
| `agy-connector-<os>-<arch>` (bare binary) | the connector's self-update |
| `checksums.txt` | `install.sh` checks the tarball arrived whole |
| `manifest.json`, `manifest.json.sig`, `manifest.json.sshsig` | the self-update and `install.sh` (signed; see below) |

`<os>-<arch>` is one of `linux-x86_64`, `linux-aarch64`, `macos-aarch64`, `macos-x86_64`.
Sign the manifest (below) in `server/target/dist`, then publish everything at once:

```sh
gh release create connector-v0.3.0 server/target/dist/* --repo NarYuki/agy-remote --title "agy-connector 0.3.0"
```

## 2. Sign the update manifest (offline)

Running connectors fetch `releases/latest/download/manifest.json` and `manifest.json.sig` and
only accept a manifest signed by the Ed25519 key whose public half is `RELEASE_PUBLIC_KEY` in
`server/agy-connector/src/update.rs`. The signature is the base64 Ed25519 signature of the
manifest file's exact bytes.

```sh
TAG=connector-v0.3.0; VER=${TAG#connector-v}
mkdir -p /tmp/rel && cd /tmp/rel
gh release download "$TAG" --repo NarYuki/agy-remote --pattern 'agy-connector-*' --pattern checksums.txt
sha256sum -c checksums.txt        # macOS: shasum -a 256 -c checksums.txt

base="https://github.com/NarYuki/agy-remote/releases/download/$TAG"
entry() {  # <manifest key> <asset>
  printf '"%s": { "url": "%s/%s", "sha256": "%s" }' "$1" "$base" "$2" "$(shasum -a 256 "$2" | cut -d' ' -f1)"
}
{
  printf '{ "version": "%s", "minAppVersion": "1.1", "assets": {\n' "$VER"
  entry x86_64-linux  agy-connector-linux-x86_64;  printf ',\n'
  entry aarch64-linux agy-connector-linux-aarch64; printf ',\n'
  entry aarch64-macos agy-connector-macos-aarch64; printf ',\n'
  entry x86_64-macos  agy-connector-macos-x86_64;  printf ',\n'
  entry x86_64-windows  agy-connector-windows-x86_64.exe;  printf ',\n'
  entry aarch64-windows agy-connector-windows-aarch64.exe; printf '\n} }\n'
} > manifest.json

# Sign with the offline key (made once with `agy-release keygen <file>`). Prints base64.
# `agy-release` is a maintainer tool in server/agy-release; it is not part of the connector.
cargo run -q --manifest-path server/agy-release/Cargo.toml -- sign manifest.json /path/to/offline/release.key > manifest.json.sig
# The same key's signature in OpenSSH's format, for install.sh on machines without OpenSSL 3 (a stock Mac).
cargo run -q --manifest-path server/agy-release/Cargo.toml -- sshsig manifest.json /path/to/offline/release.key > manifest.json.sshsig

gh release upload "$TAG" manifest.json manifest.json.sig manifest.json.sshsig --repo NarYuki/agy-remote
```

`install.sh` installs nothing unless the manifest is signed by the release key (also written into
the script, as `RELEASE_KEY_SSH` / `RELEASE_KEY_PEM`) and the binary in the tarball is the one the
manifest lists. It checks `manifest.json.sig` with OpenSSL 3 or newer, else `manifest.json.sshsig`
with `ssh-keygen -Y verify`. A release without all three manifest files can't be installed.

Do not edit `manifest.json` after signing: the signature covers its exact bytes. The manifest keys
are `<arch>-<os>` as the connector reports them (`std::env::consts`), which differ in order from
the asset names.

Until the manifest files are uploaded, connectors report the update check as failed and keep
running the version they have, and `install.sh` refuses to install the release. Never add the private key to repository secrets.
