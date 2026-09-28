#!/bin/bash
# cmux 모바일 관제실 — 빌드 · 무선 설치 · 실행
#   ./dev.sh build | install | run | log | devices
# 환경변수:
#   JAVA_HOME     JDK 21 (없으면 Homebrew openjdk@21 경로를 시도)
#   ANDROID_HOME  Android SDK (없으면 ~/Library/Android/sdk)
#   FOLD_SERIAL   adb 기기 지정(여러 대 연결됐을 때)
set -e
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
P="$(cd "$(dirname "$0")" && pwd)"
APP=app
PKG=kr.joonlab.cmuxremote
APK="$P/$APP/build/outputs/apk/debug/$APP-debug.apk"

# 같은 폰이 무선 adb 로 두 포트에 잡힐 수 있다 — :5555 우선, FOLD_SERIAL 로 덮어쓰기.
pick_device() {
  if [ -n "$FOLD_SERIAL" ]; then echo "$FOLD_SERIAL"; return; fi
  local all; all=$(adb devices | awk '$2=="device"{print $1}')
  echo "$all" | grep ':5555$' | head -1 | grep . || echo "$all" | head -1
}
S=""
need_device() {
  S="$(pick_device)"
  [ -n "$S" ] || { echo "연결된 기기가 없습니다 — adb 로 폰을 연결한 뒤 다시 실행하세요"; exit 1; }
  echo "기기: $S"
}
case "${1:-run}" in
  build)   "$P/gradlew" -p "$P" ":$APP:assembleDebug" ;;
  install) need_device; adb -s "$S" install -r "$APK" ;;
  run)     need_device
           "$P/gradlew" -p "$P" ":$APP:assembleDebug"
           adb -s "$S" install -r "$APK"
           adb -s "$S" shell am start -n $PKG/.MainActivity ;;
  log)     need_device; adb -s "$S" logcat --pid="$(adb -s "$S" shell pidof $PKG)" ;;
  devices) adb devices -l ;;
  *) echo "사용: ./dev.sh [build|install|run|log|devices]" ;;
esac
