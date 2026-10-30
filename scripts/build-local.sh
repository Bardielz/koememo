#!/bin/sh
# Local Mac toolchain. Normal Android Studio users can use ./gradlew instead.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WORKSPACE_DIR=$(CDPATH= cd -- "$PROJECT_DIR/../.." && pwd)
export JAVA_HOME='/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$WORKSPACE_DIR/work/toolchain/android-sdk"
export GRADLE_USER_HOME="$WORKSPACE_DIR/work/gradle-home"
BUILD_TEMP=$(mktemp -d "$WORKSPACE_DIR/work/local-build.XXXXXX")
python3 "$PROJECT_DIR/scripts/maven_bridge.py" "$WORKSPACE_DIR/work/maven-cache" "$BUILD_TEMP/port" &
BRIDGE_PID=$!
trap 'kill "$BRIDGE_PID" 2>/dev/null || true' EXIT HUP INT TERM
for attempt in 1 2 3 4 5; do
    [ -s "$BUILD_TEMP/port" ] && break
    sleep 1
done
BRIDGE_PORT=$(cat "$BUILD_TEMP/port")
cat > "$BUILD_TEMP/repositories.gradle" <<INIT
settingsEvaluated { settings ->
  settings.pluginManagement.repositories.clear()
  settings.dependencyResolutionManagement.repositories.clear()
  ['google', 'central'].each { repo ->
    settings.pluginManagement.repositories.maven { url = 'http://127.0.0.1:$BRIDGE_PORT/' + repo; allowInsecureProtocol = true }
    settings.dependencyResolutionManagement.repositories.maven { url = 'http://127.0.0.1:$BRIDGE_PORT/' + repo; allowInsecureProtocol = true }
  }
}
INIT
"$WORKSPACE_DIR/work/toolchain/gradle-8.11.1/bin/gradle" -Dhttp.proxyHost= -Dhttps.proxyHost= -I "$BUILD_TEMP/repositories.gradle" -p "$PROJECT_DIR" "$@"
