#!/usr/bin/env sh
# Restores gradle/wrapper/gradle-wrapper.jar (the jar itself is not committed
# to keep this repository text-only). Needed once after a fresh clone so that
# ./gradlew works locally. CI does not need it: it runs Gradle directly.
set -eu
cd "$(dirname "$0")/.."
mkdir -p gradle/wrapper
curl -fsSL --retry 3 -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar
echo "gradle-wrapper.jar restored"
