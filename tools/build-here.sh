#!/usr/bin/env bash
# Rebuilds Xenon inside a fresh Linux sandbox with no Android toolchain.
# Usage: bash tools/build-here.sh
set -euo pipefail

TC=/var/tmp/tc            # toolchain lives outside the project so it never pollutes the repo
mkdir -p "$TC"

if [ ! -x "$TC/jdk/bin/java" ]; then
  echo "== installing Temurin JDK 17 =="
  curl -sSL -o "$TC/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$TC/jdk" && tar xzf "$TC/jdk.tar.gz" -C "$TC/jdk" --strip-components=1 && rm "$TC/jdk.tar.gz"
fi

if [ ! -d "$TC/sdk/platforms/android-34" ]; then
  echo "== installing Android SDK 34 =="
  curl -sSL -o "$TC/cmdline.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p "$TC/sdk/cmdline-tools"
  unzip -q -o "$TC/cmdline.zip" -d "$TC/sdk/cmdline-tools" && rm "$TC/cmdline.zip"
  [ -d "$TC/sdk/cmdline-tools/cmdline-tools" ] && mv "$TC/sdk/cmdline-tools/cmdline-tools" "$TC/sdk/cmdline-tools/latest"
  export ANDROID_HOME="$TC/sdk"
  yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
      "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

export JAVA_HOME="$TC/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$TC/sdk"
export ANDROID_SDK_ROOT="$TC/sdk"
export GRADLE_USER_HOME=/var/tmp/gradle-home

cd "$(dirname "$0")/.."
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug :app:lintDebug --no-daemon --console=plain
echo
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
