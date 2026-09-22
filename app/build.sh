#!/bin/zsh
# Build iTantra. Sets up the toolchain this machine actually has:
#   JDK 17      -> ~/android-dev/jdk17/Contents/Home   (AGP 8.1.4 requires 17, NOT the system JDK 26)
#   Android SDK -> ~/android-dev/android-sdk
#   Gradle 8.2  -> ~/android-dev/gradle-8.2/bin/gradle (no wrapper jar was generated)
#
# Usage:  ./build.sh [assembleDebug|installDebug|clean|...]
set -eu

DEV="$HOME/android-dev"
export JAVA_HOME="$DEV/jdk17/Contents/Home"
export ANDROID_HOME="$DEV/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JDK 17 missing at $JAVA_HOME — see HANDOVER.md" >&2; exit 1
fi
if [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "Android SDK platforms missing at $ANDROID_HOME — see HANDOVER.md" >&2; exit 1
fi

TASK="${1:-assembleDebug}"
echo "JAVA_HOME   = $JAVA_HOME"
echo "ANDROID_HOME= $ANDROID_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "gradle $TASK"
echo

exec "$DEV/gradle-8.2/bin/gradle" "$TASK" --no-daemon
