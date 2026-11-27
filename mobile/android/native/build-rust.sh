#!/usr/bin/env bash
# Builds the JNI library (server/agy-p2p-jni) for the Android ABIs and copies it to
# app/src/main/jniLibs/<abi>/libagy_p2p_jni.so. Uses the NDK's clang directly (no cargo-ndk needed).
#
#   ANDROID_NDK_HOME  NDK to use (default: newest under $ANDROID_HOME/ndk or ~/Library/Android/sdk/ndk)
#   ABIS              space-separated ABIs (default: "arm64-v8a x86_64")
#   PROFILE           release (default) or debug
# Rust targets needed: rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"   # repo root; the Cargo workspace is $ROOT/server
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
API=31
ABIS="${ABIS:-arm64-v8a x86_64}"
PROFILE="${PROFILE:-release}"
# Prefer rustup's cargo: a Homebrew cargo has no Android std.
CARGO="${CARGO:-$( [ -x "$HOME/.cargo/bin/cargo" ] && echo "$HOME/.cargo/bin/cargo" || echo cargo )}"
[ -x "$HOME/.cargo/bin/rustc" ] && export RUSTC="${RUSTC:-$HOME/.cargo/bin/rustc}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

for abi in $ABIS; do
  case "$abi" in
    arm64-v8a)   target=aarch64-linux-android;    clang=aarch64-linux-android$API-clang ;;
    armeabi-v7a) target=armv7-linux-androideabi;  clang=armv7a-linux-androideabi$API-clang ;;
    x86_64)      target=x86_64-linux-android;     clang=x86_64-linux-android$API-clang ;;
    x86)         target=i686-linux-android;       clang=i686-linux-android$API-clang ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  up="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  lower="$(echo "$target" | tr '-' '_')"
  export "CARGO_TARGET_${up}_LINKER=$BIN/$clang"
  export "CC_${lower}=$BIN/$clang"
  export "AR_${lower}=$BIN/llvm-ar"
  flag=""; [ "$PROFILE" = release ] && flag="--release"
  (cd "$ROOT/server" && "$CARGO" build -p agy-p2p-jni --target "$target" $flag)
  mkdir -p "$HERE/../app/src/main/jniLibs/$abi"
  cp "$ROOT/server/target/$target/$PROFILE/libagy_p2p_jni.so" "$HERE/../app/src/main/jniLibs/$abi/"
  "$BIN/llvm-strip" "$HERE/../app/src/main/jniLibs/$abi/libagy_p2p_jni.so" || true
  echo "built $abi"
done
