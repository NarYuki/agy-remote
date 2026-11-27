#!/usr/bin/env zsh
# Builds the Rust P2P library (server/agy-p2p-ffi) for iOS devices and simulators and packages it
# as mobile/ios/Frameworks/AgyP2PCore.xcframework, which project.yml links.
set -euo pipefail
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
export IPHONEOS_DEPLOYMENT_TARGET="26.0"
IOS="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$IOS/../.." && pwd)"
SERVER="$REPO/server"
# Keep local absolute paths (home dir, cargo registry, checkout dir) out of the shipped app binary.
CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
RUSTC_SYSROOT="$(rustc --print sysroot)"
export RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=$CARGO_HOME_DIR=/cargo --remap-path-prefix=$RUSTC_SYSROOT=/rustc --remap-path-prefix=$REPO=/src --remap-path-prefix=$HOME=/home"
# Same for C/asm objects compiled by the `cc` crate (e.g. ring), which ignore RUSTFLAGS.
export CFLAGS="${CFLAGS:-} -ffile-prefix-map=$CARGO_HOME_DIR=/cargo -ffile-prefix-map=$REPO=/src -ffile-prefix-map=$HOME=/home"

cd "$SERVER"
echo "[1/3] iOS device library (aarch64-apple-ios)..."
cargo build --release -p agy-p2p-ffi --target aarch64-apple-ios

echo "[2/3] iOS simulator libraries (x86_64-apple-ios + aarch64-apple-ios-sim)..."
cargo build --release -p agy-p2p-ffi --target x86_64-apple-ios
cargo build --release -p agy-p2p-ffi --target aarch64-apple-ios-sim
mkdir -p target/universal-ios-sim/release
lipo -create \
  target/x86_64-apple-ios/release/libagy_p2p_ffi.a \
  target/aarch64-apple-ios-sim/release/libagy_p2p_ffi.a \
  -output target/universal-ios-sim/release/libagy_p2p_ffi.a

echo "[3/3] Packaging mobile/ios/Frameworks/AgyP2PCore.xcframework..."
rm -rf "$IOS/Frameworks/AgyP2PCore.xcframework"
mkdir -p "$IOS/Frameworks"
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libagy_p2p_ffi.a \
  -headers agy-p2p-ffi/include \
  -library target/universal-ios-sim/release/libagy_p2p_ffi.a \
  -headers agy-p2p-ffi/include \
  -output "$IOS/Frameworks/AgyP2PCore.xcframework"
echo "=== BUILD COMPLETE ==="
