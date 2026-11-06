#!/bin/bash
# 自动安装 debug APK 到华为设备，自动通过三道系统弹窗：
#   1. PackageInstaller「风险提示」-> 继续安装
#   2. 华为应用市场「安装检测」 -> 勾选 + 继续安装
#   3. 华为账号「锁屏密码验证」  -> 输入密码自动校验
#
# 用法: PIN=你的锁屏密码 bash scripts/install-apk.sh [apk路径]
# 密码只从环境变量读，不落盘、不写日志。

set -u
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
APK="${1:-android/app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-cn.MoeKoe.Music}"

[ -f "$APK" ] || { echo "APK 不存在: $APK"; exit 1; }

foc() { "$ADB" shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" | sed 's/.*u0 //;s/}.*//'; }
uidump() { "$ADB" shell uiautomator dump //sdcard/moe_ui.xml >/dev/null 2>&1; "$ADB" shell cat //sdcard/moe_ui.xml 2>/dev/null; }

# 在 dump 里按 resource-id 取该节点自身的 bounds（不跨节点，这是之前踩过的坑）
bounds_of() {
  local id="$1" xml="$2"
  printf '%s' "$xml" | tr '<' '\n' | grep "resource-id=\"$id\"" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1
}
tap_bounds() {
  local b="$1" c
  [ -z "$b" ] && return 1
  c=$(printf '%s' "$b" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')
  set -- $c
  "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
tap_id() { local b; b=$(bounds_of "$1" "$2"); tap_bounds "$b"; }

start_time() { "$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 lastUpdateTime; }
BEFORE=$(start_time)

echo "安装 $APK ..."
"$ADB" install -r -d "$APK" > /tmp/.apk-install.log 2>&1 &
INSTALL_PID=$!

LAST_F=""; SAME=0; SEEN_INSTALLER=0
for step in $(seq 1 60); do
  F=$(foc)
  # 同一窗口最多处理 3 次：首次可能是界面还没加载完（按钮还不在 dump 里），
  # 之后重复点击只会把页面点乱，所以次数用完就只等待。
  if [ "$F" = "$LAST_F" ]; then SAME=$((SAME+1)); else SAME=0; LAST_F="$F"; fi
  if [ "$SAME" -ge 3 ]; then sleep 1; continue; fi
  case "$F" in
    *InstallStaging*|*InstallDistActivity*|*UnifiedAuthenticationDialogActivity*) SEEN_INSTALLER=1 ;;
  esac
  case "$F" in
    *InstallStaging*)
      X=$(uidump)
      if printf '%s' "$X" | grep -q 'android:id/button1'; then
        echo "  [$step] 风险提示 -> 继续安装"
        tap_id "android:id/button1" "$X"
      fi
      ;;
    *InstallDistActivity*)
      X=$(uidump)
      echo "  [$step] 华为安装检测"
      # 复选框未勾选时「继续安装」是 disabled 的，必须先勾
      if printf '%s' "$X" | grep -q 'hidden_card_install_button_continue"'; then
        if printf '%s' "$X" | tr '<' '\n' | grep 'hidden_card_install_button_continue"' | grep -q 'enabled="false"'; then
          tap_id "com.huawei.appmarket:id/hidden_card_checkbox" "$X"
          sleep 0.8; X=$(uidump)
        fi
        tap_id "com.huawei.appmarket:id/hidden_card_install_button_continue" "$X"
      fi
      ;;
    *UnifiedAuthenticationDialogActivity*)
      echo "  [$step] 锁屏密码验证"
      [ -z "${PIN:-}" ] && { echo "  !! 需要密码但未设置 PIN 环境变量"; exit 2; }
      X=$(uidump)
      tap_id "com.huawei.coauthservice:id/pin_password_entry" "$X"; sleep 0.8
      # 逐位发送：input keyevent 一次只接受一个 keycode，且这种安全输入框
      # 认的是真实按键事件，commitText 方式不会被提交校验
      for ch in $(printf '%s' "$PIN" | fold -w1); do
        "$ADB" shell input keyevent "KEYCODE_$ch"; sleep 0.3
      done
      sleep 3
      ;;
    *MainActivity*|*launcher*|*UniHomeLauncher*)
      # 只有已经经历过安装器窗口，"回到桌面/App" 才算流程结束；
      # 否则安装刚启动、焦点还在桌面，会被误判成已完成。
      if [ "$SEEN_INSTALLER" = "1" ]; then
        echo "  [$step] 已退出安装流程"
        break
      fi
      ;;
  esac
  sleep 1
done

wait $INSTALL_PID 2>/dev/null
AFTER=$(start_time)
echo "--- 安装前: $BEFORE"
echo "--- 安装后: $AFTER"
if [ "$BEFORE" != "$AFTER" ]; then echo "OK 安装成功"; exit 0; fi
echo "FAIL 时间戳未变化，安装可能未完成"; exit 1
