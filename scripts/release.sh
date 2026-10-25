#!/usr/bin/env bash
# Uso: scripts/release.sh 1.0.1 "notas del release"
# Sube versionName/versionCode, compila los 5 APK firmados, hace commit+tag y publica en GitHub Releases.
set -euo pipefail

VERSION="${1:?uso: scripts/release.sh <version> [notas]}"
NOTES="${2:-Bona apps $VERSION}"
cd "$(dirname "$0")/.."

export JAVA_HOME="$HOME/toolchain/jdk21"
export ANDROID_HOME="$HOME/toolchain/android-sdk"
export PATH="$JAVA_HOME/bin:$HOME/toolchain/gh/bin:$PATH"

CODE=$(grep -oP 'versionCode = \K\d+' app/build.gradle.kts)
sed -i "s/versionCode = $CODE/versionCode = $((CODE + 1))/; s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts

./gradlew :app:assembleRelease --console=plain

STAGE=$(mktemp -d)
for pair in super:BonaSuper cuentas:BonaCuentas gym:BonaGym planos:BonaPlanos hongolandia:Hongolandia; do
  cp "app/build/outputs/apk/${pair%%:*}/release/app-${pair%%:*}-release.apk" "$STAGE/${pair##*:}.apk"
done

git add -A
git commit -m "Bona apps $VERSION"
git tag "v$VERSION"
git push origin main "v$VERSION"

gh release create "v$VERSION" "$STAGE"/*.apk --repo guardvalker/bona-apks --title "Bona apps $VERSION" --notes "$NOTES"
