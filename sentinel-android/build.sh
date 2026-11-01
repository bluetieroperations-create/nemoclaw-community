#!/bin/bash
# Build sentinel.apk with no Gradle and no network. javac -> d8 -> aapt2 -> sign.
set -euo pipefail
cd "$(dirname "$0")"
SDK=/opt/android-sdk
BT=$SDK/build-tools/35.0.0
AJ=$SDK/platforms/android-35/android.jar
B=build
PASS=sentinel

rm -rf $B/classes $B/dex $B/res $B/unsigned.apk $B/aligned.apk $B/sentinel.apk
mkdir -p $B/classes $B/dex $B/res

echo "1/5 javac"
javac --release 11 -nowarn -classpath "$AJ" -d $B/classes src/com/bluetier/sentinel/*.java

echo "2/5 d8"
"$BT/d8" --release --min-api 31 --lib "$AJ" --output $B/dex \
    $(find $B/classes -name '*.class')

echo "3/5 aapt2"
"$BT/aapt2" compile --dir res -o $B/res/res.zip
"$BT/aapt2" link -o $B/unsigned.apk -I "$AJ" \
    --manifest AndroidManifest.xml --min-sdk-version 31 --target-sdk-version 35 \
    $B/res/res.zip

echo "4/5 package dex"
( cd $B/dex && zip -q -X ../unsigned.apk classes.dex )

echo "5/5 align and sign"
"$BT/zipalign" -f -p 4 $B/unsigned.apk $B/aligned.apk
[ -f $B/k.jks ] || keytool -genkeypair -keystore $B/k.jks -alias k -storepass "$PASS" \
    -keypass "$PASS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Sentinel"
"$BT/apksigner" sign --ks $B/k.jks --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --ks-key-alias k --out $B/sentinel.apk $B/aligned.apk
"$BT/apksigner" verify --print-certs $B/sentinel.apk | head -4

base64 -w 76 $B/sentinel.apk > $B/b64.txt
printf '\nAPK  %s bytes\nsha256 %s\nbase64 %s lines\n' \
    "$(stat -c%s $B/sentinel.apk)" \
    "$(sha256sum $B/sentinel.apk | cut -c1-16)" \
    "$(wc -l < $B/b64.txt)"
