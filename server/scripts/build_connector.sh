#!/usr/bin/env zsh
# Builds agy-connector (release) from this Cargo workspace -> server/target/release/agy-connector.
#   --install   also copy it to ~/.local/bin/agy-connector
set -euo pipefail
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
SERVER="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$SERVER/.." && pwd)"
# Keep local absolute paths (home dir, cargo registry, checkout dir) out of the shipped binary.
CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
RUSTC_SYSROOT="$(rustc --print sysroot)"
export RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=$CARGO_HOME_DIR=/cargo --remap-path-prefix=$RUSTC_SYSROOT=/rustc --remap-path-prefix=$REPO=/src --remap-path-prefix=$HOME=/home"
export CFLAGS="${CFLAGS:-} -ffile-prefix-map=$CARGO_HOME_DIR=/cargo -ffile-prefix-map=$REPO=/src -ffile-prefix-map=$HOME=/home"

cd "$SERVER"
cargo build --release -p agy-connector
ls -lh target/release/agy-connector

if [[ "${1:-}" == "--install" ]]; then
  mkdir -p "$HOME/.local/bin"
  cp -f target/release/agy-connector "$HOME/.local/bin/agy-connector"
  chmod +x "$HOME/.local/bin/agy-connector"
  echo "Installed to $HOME/.local/bin/agy-connector"
fi
