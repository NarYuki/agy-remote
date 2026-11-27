#!/bin/sh
# Regenerates Sources/Resources/licenses.json (Rust crates in AgyP2PCore; no Swift packages are used).
exec python3 "$(dirname "$0")/../../android/tools/gen_licenses.py" --ios
