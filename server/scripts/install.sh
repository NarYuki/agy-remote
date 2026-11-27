#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Installs the latest agy-connector release into ~/.local/bin. No sudo needed.
#
#   curl -fsSL https://n4t.su/agy/ | sh
#   curl -fsSL https://n4t.su/agy/ | sh -s -- --service   # also run it in the background at login
#
# Options:
#   --service        set up a per-user service (systemd --user on Linux, LaunchAgent on macOS)
#   --dir <path>     install somewhere other than ~/.local/bin
#   --version <tag>  install a specific release tag (e.g. connector-v0.2.0) instead of the latest
# Environment: AGY_INSTALL_DIR, AGY_RELEASE_BASE (mirror of the release download folder),
#   AGY_SCRIPTS_BASE (where to fetch install_service.sh from).
#
# The download is only installed if the release's manifest is signed by the release key below
# (the one the connector's own updater trusts) and the binary matches the manifest. A mirror or a
# changed release page can't make this script install anything else.
set -eu

REPO="NarYuki/agy-remote"
# Release signing key (Ed25519, public half): RELEASE_PUBLIC_KEY in server/agy-connector/src/update.rs,
# here in the two spellings the tools below read.
RELEASE_KEY_SSH="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINxqMoVdK458HJ2Yu6E9p3XnhHG74DZP6+W/8C/nW6fQ"
RELEASE_KEY_PEM="MCowBQYDK2VwAyEA3GoyhV0rjnwcnZi7oT2ndeeEcbvgNk/r5b/wL+dbp9A="
RAW="${AGY_SCRIPTS_BASE:-https://raw.githubusercontent.com/$REPO/main/server/scripts}"
dir="${AGY_INSTALL_DIR:-$HOME/.local/bin}"
tag=""
service=0

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --service) service=1 ;;
    --dir) [ $# -ge 2 ] || die "--dir needs a path"; dir="$2"; shift ;;
    --version) [ $# -ge 2 ] || die "--version needs a tag"; tag="$2"; shift ;;
    -h|--help)
      say "usage: curl -fsSL https://n4t.su/agy/ | sh -s -- [--service] [--dir PATH] [--version TAG]"
      exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

if [ -n "${AGY_RELEASE_BASE:-}" ]; then
  base="$AGY_RELEASE_BASE"
elif [ -n "$tag" ]; then
  base="https://github.com/$REPO/releases/download/$tag"
else
  base="https://github.com/$REPO/releases/latest/download"
fi

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) die "unsupported OS $(uname -s); agy-connector runs on Linux and macOS" ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) die "unsupported CPU $(uname -m); releases exist for x86_64 and aarch64" ;;
esac
# A shell running under Rosetta reports x86_64; prefer the native build.
if [ "$os" = macos ] && [ "$arch" = x86_64 ] && [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = 1 ]; then
  arch=aarch64
fi
asset="agy-connector-$os-$arch.tar.gz"

if command -v curl >/dev/null 2>&1; then
  get() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
  get() { wget -q -O "$2" "$1"; }
else
  die "need curl or wget"
fi

if command -v sha256sum >/dev/null 2>&1; then
  digest() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  digest() { shasum -a 256 "$1" | cut -d' ' -f1; }
elif command -v openssl >/dev/null 2>&1; then
  digest() { openssl dgst -sha256 "$1" | sed 's/.*= *//'; }
else
  die "need sha256sum, shasum or openssl to check the download"
fi

work="$(mktemp -d 2>/dev/null || mktemp -d -t agy-install)"
trap 'rm -rf "$work"' EXIT INT TERM

say "Downloading $asset"
get "$base/$asset" "$work/$asset" || die "download failed: $base/$asset"
get "$base/checksums.txt" "$work/checksums.txt" || die "download failed: $base/checksums.txt"

want="$(awk -v f="$asset" '{ n = $2; sub(/^\*/, "", n); if (n == f) print $1 }' "$work/checksums.txt" | head -n 1)"
[ -n "$want" ] || die "checksums.txt has no entry for $asset"
have="$(digest "$work/$asset")"
[ "$have" = "$want" ] || die "checksum mismatch for $asset (expected $want, got $have)"
say "Checksum OK"

# checksums.txt comes from the same place as the download, so it only catches a damaged file.
# What decides is the signed manifest: its signature is checked with the key in this script, and
# the unpacked binary must be the one it lists for this platform.
get "$base/manifest.json" "$work/manifest.json" || die "download failed: $base/manifest.json"

# OpenSSL 3 or newer checks the raw signature (manifest.json.sig). LibreSSL (a stock Mac) and
# OpenSSL 1.x can't, so there ssh-keygen checks the same key's OpenSSH signature (manifest.json.sshsig).
openssl_verifies() {
  command -v openssl >/dev/null 2>&1 || return 1
  case "$(openssl version 2>/dev/null)" in
    "OpenSSL "[3-9]*|"OpenSSL "[1-9][0-9]*) ;;
    *) return 1 ;;
  esac
  printf -- '-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n' "$RELEASE_KEY_PEM" > "$work/release.pem"
  openssl pkey -pubin -in "$work/release.pem" -noout >/dev/null 2>&1
}

if openssl_verifies; then
  get "$base/manifest.json.sig" "$work/manifest.json.sig" || die "download failed: $base/manifest.json.sig"
  openssl base64 -d -A -in "$work/manifest.json.sig" -out "$work/manifest.sig.bin" 2>/dev/null \
    || die "manifest.json.sig is not a signature"
  openssl pkeyutl -verify -pubin -inkey "$work/release.pem" -rawin \
    -in "$work/manifest.json" -sigfile "$work/manifest.sig.bin" >/dev/null 2>&1 \
    || die "the release manifest is not signed by the agy-connector release key; nothing was installed"
elif command -v ssh-keygen >/dev/null 2>&1; then
  get "$base/manifest.json.sshsig" "$work/manifest.json.sshsig" || die "download failed: $base/manifest.json.sshsig"
  printf 'agy-release %s\n' "$RELEASE_KEY_SSH" > "$work/allowed_signers"
  ssh-keygen -Y verify -f "$work/allowed_signers" -I agy-release -n agy-release \
    -s "$work/manifest.json.sshsig" < "$work/manifest.json" >/dev/null 2>&1 \
    || die "the release manifest is not signed by the agy-connector release key; nothing was installed"
else
  die "need OpenSSL 3 or ssh-keygen to check the release signature"
fi

# Unpacked only now that the manifest is known to be genuine, and into a folder of its own: an
# archive can hold links, and a link named like a file this script writes later would send that
# download to wherever the link points.
mkdir "$work/unpacked"
tar -xzf "$work/$asset" -C "$work/unpacked" || die "could not unpack $asset"
bin="$work/unpacked/agy-connector"
[ -f "$bin" ] && [ ! -L "$bin" ] || die "$asset does not contain agy-connector"

# The manifest names platforms as <arch>-<os>, and lists the bare binary (what the tarball holds).
signed="$(tr -d '\n\r' < "$work/manifest.json" \
  | sed -n "s/.*\"$arch-$os\"[[:space:]]*:[[:space:]]*{[^}]*\"sha256\"[[:space:]]*:[[:space:]]*\"\([0-9a-fA-F]\{64\}\)\".*/\1/p" \
  | tr 'A-F' 'a-f')"
[ -n "$signed" ] || die "the signed manifest has no entry for $arch-$os"
have="$(digest "$bin")"
[ "$have" = "$signed" ] || die "agy-connector does not match the signed manifest (expected $signed, got $have); nothing was installed"
say "Signature OK"

mkdir -p "$dir"
# Stage next to the target and rename, so a running connector keeps its old file intact.
cp "$bin" "$dir/.agy-connector.new"
chmod 755 "$dir/.agy-connector.new"
mv -f "$dir/.agy-connector.new" "$dir/agy-connector"
say "Installed $dir/agy-connector ($("$dir/agy-connector" --version 2>/dev/null || echo "version unknown"))"

case ":$PATH:" in
  *":$dir:"*) ;;
  *)
    say ""
    say "Warning: $dir is not on your PATH. Add this to your shell profile:"
    say "  export PATH=\"$dir:\$PATH\""
    ;;
esac

if [ "$service" = 1 ]; then
  get "$RAW/install_service.sh" "$work/install_service.sh" || die "could not fetch install_service.sh"
  AGY_CONNECTOR_BIN="$dir/agy-connector" sh "$work/install_service.sh"
else
  say ""
  say "Next: run 'agy-connector pair' and scan the QR code with the AGY Remote app."
  say "To keep it running in the background: curl -fsSL https://n4t.su/agy/ | sh -s -- --service"
fi
