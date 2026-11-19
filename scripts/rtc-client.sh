#!/bin/bash
# The WordPlus RTC client for Android the calls run on (cloud.wordplus:rtc-client-android): LiveKit's Android SDK with
# WordPlus Mesh's patch, its packages LiveKit's own. Copied from a build of it into vendor/rtc-client-android/, a Maven
# repository with its AAR, POM, module and sources, beside its LICENSE, NOTICE, VERSION and the patch it was built from.
# The calls, the screens and the example take it from there (each build's settings.gradle.kts); a release publishes
# it to Maven Central with the others (rtc-client/), where an app takes it from.
#
#   scripts/rtc-client.sh <a build of the client>      (the mesh repository's clients/android/build.sh makes one)
set -euo pipefail
cd "$(dirname "$0")/.."

from=${1:?usage: scripts/rtc-client.sh <a build of the WordPlus RTC client for Android>}
if [ ! -f "$from/VERSION" ] || [ ! -d "$from/cloud/wordplus/rtc-client-android" ]; then
    echo "$from is no build of the client: it has no VERSION or cloud/wordplus/rtc-client-android" >&2
    exit 1
fi

version=$(cat "$from/VERSION")
mkdir -p vendor/rtc-client-android
rsync -a --delete "$from"/ vendor/rtc-client-android/
echo "vendor/rtc-client-android: cloud.wordplus:rtc-client-android:$version"
for file in calls/build.gradle.kts ui/build.gradle.kts rtc-client/build.gradle.kts; do
    grep -q "$version" "$file" || echo "  $file doesn't name $version yet" >&2
done
