#!/usr/bin/env bash
# 폰 WebView 앱 수동 빌드 → bridge/data/tesla.apk (다운로드 /app.apk)
set -e
SDK="/c/Users/smile/android-sdk"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-34/android.jar"
cd "$(dirname "$0")"

rm -rf build gen
mkdir -p build/classes gen

echo "=== aapt R.java 생성 ==="
"$BT/aapt.exe" package -f -m -M AndroidManifest.xml -S res -I "$(cygpath -w "$AJ")" -J gen

echo "=== javac ==="
javac -source 8 -target 8 -bootclasspath "$(cygpath -w "$AJ")" -d build/classes \
  gen/com/hongcha/tesla/R.java \
  src/com/hongcha/tesla/MainActivity.java 2>&1 | grep -viE "warning|obsolete|deprecat|^Note" || true

echo "=== d8 ==="
cmd //c "$(cygpath -w "$BT/d8.bat")" --min-api 24 --output build \
  --lib "$(cygpath -w "$AJ")" build/classes/com/hongcha/tesla/R.class \
  build/classes/com/hongcha/tesla/R\$string.class \
  build/classes/com/hongcha/tesla/MainActivity.class \
  build/classes/com/hongcha/tesla/MainActivity\$*.class

echo "=== aapt + 정렬 + 서명 ==="
"$BT/aapt.exe" package -f -M AndroidManifest.xml -S res -I "$(cygpath -w "$AJ")" -F build/app.unaligned.apk
( cd build && "$BT/aapt.exe" add app.unaligned.apk classes.dex >/dev/null )
"$BT/zipalign.exe" -f 4 build/app.unaligned.apk build/app.aligned.apk
cmd //c "$(cygpath -w "$BT/apksigner.bat")" sign \
  --ks tesla.keystore --ks-pass pass:teslapass --ks-key-alias tesla \
  --out build/tesla-signed.apk build/app.aligned.apk

cp build/tesla-signed.apk "/c/Users/smile/tesla-bridge/bridge/data/tesla.apk"
cmd //c "$(cygpath -w "$BT/apksigner.bat")" verify "/c/Users/smile/tesla-bridge/bridge/data/tesla.apk" && echo "빌드·서명 OK → bridge/data/tesla.apk"
