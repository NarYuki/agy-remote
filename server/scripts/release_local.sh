#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Build agy-connector for Linux (musl, x86_64 + aarch64), macOS (arm64 + x86_64) and Windows
# (x86_64 + arm64) on this Mac
# and publish them as a GitHub Release — no hosted CI involved.
#
#   server/scripts/release_local.sh            # build into server/target/dist only
#   server/scripts/release_local.sh --publish  # also create release connector-v<version> with gh
#
# Needs: rustup (with the six targets), cargo-zigbuild + zig, cargo-xwin + brew llvm lld, gh (logged in) for --publish.
set -eu
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"   # rustup's cargo, not a system one

version=$(sed -n 's/^version = "\(.*\)"/\1/p' agy-connector/Cargo.toml | head -n 1)
tag="connector-v$version"
dist=target/dist
rm -rf "$dist" && mkdir -p "$dist"

build() { # target asset use_zig
    rustup target add "$1" >/dev/null 2>&1 || true
    if [ "$3" = zig ]; then cargo zigbuild --release -p agy-connector --target "$1"
    else cargo build --release -p agy-connector --target "$1"; fi
    cp "target/$1/release/agy-connector" "$dist/agy-connector-$2"
    tar -C "target/$1/release" -czf "$dist/agy-connector-$2.tar.gz" agy-connector
}

build x86_64-unknown-linux-musl linux-x86_64 zig
build aarch64-unknown-linux-musl linux-aarch64 zig
build aarch64-apple-darwin macos-aarch64 native
build x86_64-apple-darwin macos-x86_64 native

build_windows() { # target asset  (MSVC ABI, cross-built with cargo-xwin and Homebrew llvm + lld)
    rustup target add "$1" >/dev/null 2>&1 || true
    # ring insists on plain clang for ARM64, so build in clang mode (Apple's clang can't target Windows).
    PATH="$(brew --prefix llvm)/bin:$(brew --prefix lld)/bin:$PATH" \
        cargo xwin build --cross-compiler clang --release -p agy-connector --target "$1"
    cp "target/$1/release/agy-connector.exe" "$dist/agy-connector-$2.exe"
    (cd "target/$1/release" && rm -f "$OLDPWD/$dist/agy-connector-$2.zip" && zip -q "$OLDPWD/$dist/agy-connector-$2.zip" agy-connector.exe)
}

build_windows x86_64-pc-windows-msvc windows-x86_64
build_windows aarch64-pc-windows-msvc windows-aarch64

(cd "$dist" && shasum -a 256 agy-connector-* > checksums.txt)
echo "built $tag into server/$dist"

if [ "${1:-}" = --publish ]; then
    gh release create "$tag" "$dist"/* --title "agy-connector $version" \
        --notes "Install or update: \`curl -fsSL https://n4t.su/agy/ | sh\`"
fi
