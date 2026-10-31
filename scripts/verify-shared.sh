#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -x /usr/libexec/java_home ]]; then
    JAVA_17_HOME="$(/usr/libexec/java_home -v 17)"
    export JAVA_HOME="$JAVA_17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
./gradlew :shared:jvmTest :shared:assembleAndroidMain --no-daemon --console=plain
printf '%s\n' 'SHARED_VERIFICATION_PASSED'
