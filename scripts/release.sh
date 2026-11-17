#!/usr/bin/env bash
# Builds a signed release, bumps the version and publishes it to GitHub Releases, where the
# app's over-the-air updater picks it up.
#   scripts/release.sh 0.3.0 "What's new line 1" "What's new line 2"
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?usage: release.sh <version> [notes...]}"; shift
NOTES="$(printf '%s\n' "$@")"
# Uses your JDK 17 from JAVA_HOME (set it before running).
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17}"
GH="${GH:-gh}"
REPO="$(grep '^updateRepo=' gradle.properties | cut -d= -f2)"
[ -n "$REPO" ] || { echo "Set updateRepo=owner/repo in gradle.properties"; exit 1; }

CODE=$(( $(grep '^appVersionCode=' gradle.properties | cut -d= -f2) + 1 ))
sed -i '' "s/^appVersionCode=.*/appVersionCode=$CODE/; s/^appVersionName=.*/appVersionName=$VERSION/" gradle.properties

./gradlew testDebugUnitTest assembleRelease -q
cp app/build/outputs/apk/release/app-release.apk HamukSwims.apk
"$GH" release create "v$VERSION" HamukSwims.apk --repo "$REPO" --title "Hamuk Swims $VERSION" --notes "${NOTES:-Improvements and fixes.}"
echo "Released v$VERSION (code $CODE) to $REPO"
