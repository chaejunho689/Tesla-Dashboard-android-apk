#!/usr/bin/env bash
# 폰 알림 테스트 메뉴. 폰이 adb로 연결돼 있어야 함(USB 디버깅 or 무선 adb).
ADB="/c/Users/smile/android-sdk/platform-tools/adb.exe"
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
  echo "  2) 트렁크 열림"
  echo "  3) 프렁크 열림"
  echo "  4) 충전 완료 (시간·요금)"
  echo " [나우바/상시 알림]"
  echo "  5) 운전 중"
  echo "  6) 충전 중 (완충까지 카운트다운)"
  echo "  7) 에어컨 가동 중"
  echo "  8) 에어컨 완료"
  echo "  9) 센트리모드 켜짐"
  echo " 10) 주차 됨"
  echo "  q) 종료"
  read -p "선택: " c
  case "$c" in
    1) send sw ;;
    2) send trunk ;;
    3) send frunk ;;
    4) send chgdone ;;
    5) send drive ;;
    6) send charge ;;
    7) send hvac ;;
    8) send hvacdone ;;
    9) send sentry ;;
    10) send park ;;
    q|Q) exit 0 ;;
    *) echo "  ? 없는 번호" ;;
  esac
done
