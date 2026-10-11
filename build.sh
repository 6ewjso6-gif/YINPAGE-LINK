#!/usr/bin/env bash
# ============================================================================
#  YINPAGE-LINK 构建脚本（Linux / macOS）
#  用法：
#    ./build.sh              # 构建 debug APK
#    ./build.sh release      # 构建 release APK（未签名）
#    ./build.sh kotlin       # 只编译 Kotlin（定位错误最快）
#    ./build.sh clean        # 清理
#
#  要求：JDK 17、Android SDK（platforms;android-35 + build-tools;35.0.0）、Gradle 8.9+
#  若使用 gradlew（仓库未内置 wrapper），把本脚本里的 gradle 换成 ./gradlew 即可。
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

GRADLE_BIN="${GRADLE_BIN:-gradle}"
if [ ! -x "$(command -v "$GRADLE_BIN" 2>/dev/null || true)" ] && [ ! -x "$GRADLE_BIN" ]; then
  echo "找不到 gradle。请安装 Gradle 8.9+，或用 GRADLE_BIN=/path/to/gradle 指定。" >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "提示：未设置 JAVA_HOME，将使用 PATH 中的 java。需要 JDK 17。"
fi

TASK=":app:assembleDebug"
case "${1:-}" in
  release) TASK=":app:assembleRelease" ;;
  kotlin)  TASK=":app:compileDebugKotlin" ;;
  clean)   TASK="clean" ;;
  ""|debug) TASK=":app:assembleDebug" ;;
  *)       TASK="$*" ;;
esac

# S10：debug 签名库自举。
# .gitignore 忽略了 .android/，仓库里没有 debug.keystore，本地首次构建会失败。
# 不存在时用 keytool 生成一个（口令是公开约定值，仅用于 debug，无安全影响）。
DEBUG_KEYSTORE=".android/debug.keystore"
if [ "$TASK" != "clean" ] && [ ! -f "$DEBUG_KEYSTORE" ]; then
  echo ">> 未找到 $DEBUG_KEYSTORE，正在生成 debug 签名库"
  mkdir -p .android
  keytool -genkeypair -v \
    -keystore "$DEBUG_KEYSTORE" \
    -storepass android -keypass android \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo ">> $GRADLE_BIN $TASK"
"$GRADLE_BIN" "$TASK" --console=plain

if [ -d app/build/outputs/apk ]; then
  echo ""
  echo "产物："
  find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
fi
