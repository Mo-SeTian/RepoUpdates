#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Usage: package-release.sh SIGNED_APK OUTPUT_DIRECTORY}
output_directory=${2:?Usage: package-release.sh SIGNED_APK OUTPUT_DIRECTORY}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
build_tools="$sdk/build-tools/35.0.0"

"$build_tools/apksigner" verify "$apk"
metadata=$("$build_tools/aapt" dump badging "$apk")
app_name=$(sed -n "s/^application-label:'\(.*\)'$/\1/p" <<< "$metadata")
version=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<< "$metadata")
if [[ -z "$app_name" || -z "$version" || "$app_name" == */* || "$version" == */* ]]; then
    printf 'Invalid APK application label or version name.\n' >&2
    exit 1
fi

if command -v md5sum >/dev/null 2>&1; then
    checksum=$(md5sum < "$apk")
else
    checksum=$(md5 -q "$apk")
fi
mkdir -p "$output_directory"
artifact="$output_directory/${app_name}_${version}_realse_${checksum:0:8}.apk"
cp "$apk" "$artifact"
printf '%s\n' "$artifact"
