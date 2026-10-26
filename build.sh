#!/bin/sh
# Compila el APK sin Android Studio: aapt + javac + dx + zipalign + apksigner
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/classes build/gen
aapt package -f -0 arsc -m -J build/gen -M AndroidManifest.xml -S res -I $SDK -F build/app.unsigned.apk
javac -nowarn --release 8 -classpath $SDK -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v "warning: \[options\]" || true
dalvik-exchange --dex --min-sdk-version=23 --output=build/classes.dex build/classes
(cd build && aapt add -f app.unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/app.unsigned.apk build/app.aligned.apk
[ -f nexa.keystore ] || keytool -genkeypair -keystore nexa.keystore -storepass nexadrone -keypass nexadrone \
  -alias nexa -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NEXA Drone" >/dev/null 2>&1
apksigner sign --ks nexa.keystore --ks-pass pass:nexadrone --ks-key-alias nexa --out NEXA-X.apk build/app.aligned.apk
apksigner verify NEXA-X.apk && echo "OK: NEXA-X.apk"
