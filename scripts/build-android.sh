#!/usr/bin/env bash
# 构建 Android 调试 APK。
#
# 为什么需要这个脚本：`gradlew assembleDebug` 只是三步里的最后一步。
# 少任何一步都会得到一个"能装但内容过期"的包：
#   1. vite build  —— 生成 dist
#   2. cap sync    —— 把 dist 拷进 android/app/src/main/assets/public（漏了它就是旧前端）
#   3. gradlew     —— 编译 APK
#
# 另外本机默认 JDK 26 会让 AGP 的 JdkImageTransform 直接失败，必须用 JDK 17/21。
# 脚本会自动挑一个可用的 JDK，并补齐 ANDROID_HOME。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

pick_jdk() {
    for candidate in \
        "${JAVA_HOME:-}" \
        "/c/Program Files/Java/jdk-21" \
        "/c/Program Files/Java/jdk-17" \
        "/c/Program Files/Eclipse Adoptium/jdk-21"* \
        "/usr/lib/jvm/java-21-openjdk-amd64" \
        "/usr/lib/jvm/java-17-openjdk-amd64"; do
        [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ] || continue
        # AGP 8.x 不接受 JDK 22+，jlink 会因为 core-for-system-modules 失败
        local major
        major="$("$candidate/bin/javac" -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
        if [ "$major" -ge 17 ] && [ "$major" -le 21 ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

pick_sdk() {
    for candidate in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "$LOCALAPPDATA/Android/Sdk" \
        "$HOME/Android/Sdk"; do
        [ -n "$candidate" ] && [ -d "$candidate/platforms" ] && { echo "$candidate"; return 0; }
    done
    return 1
}

JDK="$(pick_jdk)" || { echo "找不到 JDK 17-21，AGP 8.x 不支持更新的 JDK" >&2; exit 1; }
SDK="$(pick_sdk)" || { echo "找不到 Android SDK，请设置 ANDROID_HOME" >&2; exit 1; }
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

echo "JDK: $JAVA_HOME"
echo "SDK: $ANDROID_HOME"

npx cross-env VITE_APP_ENV=web vite build
npx cap sync android

cd android
# cap sync 改写 assets/public 后，Gradle 仍可能判定 packageDebug 为 UP-TO-DATE，
# 打出一个内容和前端对不上的旧包。先删掉旧 APK 再构建，保证产物一定重新生成。
rm -f app/build/outputs/apk/debug/app-debug.apk
./gradlew ${GRADLE_ARGS:---offline} :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "构建结束但找不到 $APK" >&2; exit 1; }
echo
echo "APK: $ROOT/android/$APK ($(du -h "$APK" | cut -f1))"
