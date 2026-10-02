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

echo ">> $GRADLE_BIN $TASK"
"$GRADLE_BIN" "$TASK" --console=plain

if [ -d app/build/outputs/apk ]; then
  echo ""
  echo "产物："
  find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
fi
