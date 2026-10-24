#!/usr/bin/env bash
# Compilation de l'APK « Carnet de rêves », sans Android Studio ni Gradle.
# Outils (Ubuntu / Debian) :
#   apt install aapt android-sdk-platform-23 dalvik-exchange zipalign apksigner default-jdk python3-pil curl
# Usage : ./build.sh  ->  CarnetReves-v<version>.apk dans ce dossier
# Variables facultatives (utilisées par la compilation automatique GitHub) :
#   PAGE=chemin/index.html  VERSION_CODE=12  VERSION_NAME=1.0.11  APK_NAME=Carnet-de-reves.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK=/usr/lib/android-sdk/platforms/android-23/android.jar   # bibliothèque Android de compilation
KS=keystore/carnetreves.p12                                 # clé de signature : TOUJOURS la même pour les mises à jour
KS_PASS=carnetreves
VERSION=${VERSION_NAME:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)}
PAGE=${PAGE:-../index.html}
OUT=build
APK=${APK_NAME:-CarnetReves-v${VERSION}.apk}

rm -rf "$OUT"; mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/assets/fonts"
cp "$PAGE" "$OUT/assets/index.html"
sed -i "s/const VERSION = '[^']*';/const VERSION = '${VERSION}';/" "$OUT/assets/index.html"   # n° affiché en bas du journal

# 1) polices (licence SIL OFL), intégrées pour un fonctionnement 100 % hors ligne
G=https://fonts.gstatic.com/s
while read -r nom url; do
  curl -sSfL --retry 3 -o "$OUT/assets/fonts/$nom" "$G/$url"
done <<'POLICES'
atkinson-400.woff2 atkinsonhyperlegible/v12/9Bt23C1KxNDXMspQ1lPyU89-1h6ONRlW45G04pIoWQeCbA.woff2
atkinson-400i.woff2 atkinsonhyperlegible/v12/9Bt43C1KxNDXMspQ1lPyU89-1h6ONRlW45G056IqUwCgblWm.woff2
atkinson-700.woff2 atkinsonhyperlegible/v12/9Bt73C1KxNDXMspQ1lPyU89-1h6ONRlW45G8Wbc9dCWPRl-uFQ.woff2
instrument-400.woff2 instrumentserif/v5/jizBRFtNs2ka5fXjeivQ4LroWlx-6zUTjnTLgNs.woff2
instrument-400i.woff2 instrumentserif/v5/jizHRFtNs2ka5fXjeivQ4LroWlx-6zAjjH7Motmp5g.woff2
POLICES

# 2) icônes
python3 tools/make_icons.py res

# 3) clé de signature (créée une seule fois, puis conservée dans le dépôt)
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass "$KS_PASS" -alias carnetreves \
    -keyalg RSA -keysize 2048 -validity 12000 -dname "CN=Carnet de reves, O=Laurent Brasselet, C=FR"
fi

# 4) manifeste + ressources + page (assets) ; resources.arsc non compressé (exigé depuis Android 11)
VER=()
if [ -n "${VERSION_CODE:-}" ]; then VER=(--version-code "$VERSION_CODE" --version-name "$VERSION" --replace-version); fi
aapt package -f -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$SDK" -F "$OUT/app.unsigned.apk" -0 arsc -0 woff2 "${VER[@]}"

# 5) Java -> classes (le talon RenderProcessGoneDetail ne sert qu'à compiler, il n'entre pas dans l'APK)
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" -d "$OUT/stubs" stubs/android/webkit/*.java
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" -classpath "$OUT/stubs" \
  -d "$OUT/classes" src/fr/brasselet/carnetreves/*.java

# 6) classes -> classes.dex, ajouté à l'APK
dalvik-exchange --dex --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && aapt add -f ../app.unsigned.apk classes.dex > /dev/null)

# 7) alignement puis signature (schémas v2 et v3)
zipalign -f -p 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
apksigner sign --v2-signing-enabled true --v3-signing-enabled true --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias carnetreves --out "$APK" "$OUT/app.aligned.apk"
apksigner verify "$APK"
echo "APK prêt : $APK ($(du -h "$APK" | cut -f1))"
