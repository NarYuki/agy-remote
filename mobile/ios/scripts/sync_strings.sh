#!/bin/sh
# Builds the app and refreshes the String Catalogs from the strings the code uses.
# Run after changing user-facing text; then translate new entries in Xcode.
set -eu
cd "$(dirname "$0")/.."
DD="${DERIVED_DATA:-build/strings-dd}"
xcodebuild -project AntigravityRemote.xcodeproj -scheme AntigravityRemote -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DD" \
  CODE_SIGNING_ALLOWED=NO build -quiet
B="$DD/Build/Intermediates.noindex/AntigravityRemote.build/Debug-iphonesimulator"
sync() { # catalog, build dir
  set -- "$1" $(find "$2" -name '*.stringsdata' -exec printf -- '--stringsdata %s ' {} \;)
  xcrun xcstringstool sync "$@"
}
sync Sources/Resources/Localizable.xcstrings "$B/AntigravityRemote.build"
sync NotificationService/Localizable.xcstrings "$B/NotificationService.build"
# The watch app is built for the watch simulator, next to the phone build.
sync Watch/Resources/Localizable.xcstrings "${B%/*}/Debug-watchsimulator/AGYRemoteWatch.build"
echo "String Catalogs updated."
