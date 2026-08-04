#!/usr/bin/env bash
# 폰 알림 테스트 메뉴. 폰이 adb로 연결돼 있어야 함(USB 디버깅 or 무선 adb).
ADB="${ADB:-$(command -v adb || echo /c/Users/smile/android-sdk/platform-tools/adb.exe)}"
PKG="com.hongcha.tesla"
RECV="$PKG/.TestReceiver"

send() { "$ADB" shell am broadcast -n "$RECV" --es which "$1" >/dev/null 2>&1 && echo "  → '$1' 알림 전송"; }

# 인자로 바로 실행도 가능: bash notify-test.sh sw
if [ -n "$1" ]; then send "$1"; exit; fi

echo "=== 폰 연결 확인 ==="
"$ADB" devices | grep -w device | grep -v "List" || { echo "폰이 adb에 연결 안 됨. USB 디버깅 켜고 연결하세요."; exit 1; }

while true; do
  echo ""
  echo "===== 테슬라 알림 테스트 ====="
  echo " [이벤트 알림]"
  echo "  1) 신규 소프트웨어"
  echo "  2) 충전 완료 (시간·요금)"
  echo "  3) 주행 종료"
  echo " [상태 칩 / 나우바]"
  echo "  4) 운전 중      칩: 02:50 (경과)"
  echo "  5) 충전 중      칩: 00:45 (남은시간)"
  echo "  6) 에어컨       칩: 00:50 (경과)"
  echo "  7) 트렁크       칩: 트렁크"
  echo "  8) 프렁크       칩: 프렁크"
  echo "  9) 센트리       칩: 감시중"
  echo " 10) 칩 끄기"
  echo "  q) 종료"
  read -p "선택: " c
  case "$c" in
    1) send sw ;;
    2) send chgdone ;;
    3) send driveend ;;
    4) send drive ;;
    5) send charge ;;
    6) send hvac ;;
    7) send trunk ;;
    8) send frunk ;;
    9) send sentry ;;
    10) send off ;;
    q|Q) exit 0 ;;
    *) echo "  ? 없는 번호" ;;
  esac
done
