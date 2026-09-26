#!/usr/bin/env bash
# Builds the F-Droid repository that the release workflow publishes on the `fdroid` branch.
#   scripts/fdroid-repo.sh <apk> <keystore> <output dir>
# The keystore holds the repository key under the alias "fdroid"; its password comes from SIGNING_PASSWORD.
set -euo pipefail

apk=$(realpath "$1")
keystore=$(realpath "$2")
out=$(realpath -m "$3")
root=$(cd "$(dirname "$0")/.." && pwd)
app_id=com.stroexd.hsdecktracker
repo_url=${FDROID_REPO_URL:-https://raw.githubusercontent.com/stroexd/hs-deck-tracker-android/fdroid/repo}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/repo" "$work/metadata/$app_id"
cp "$apk" "$work/repo/"
cp "$root/fastlane/metadata/android/en-US/images/icon.png" "$work/icon.png"
# Name, descriptions and icon come from the fastlane texts
cp -r "$root"/fastlane/metadata/android/* "$work/metadata/$app_id/"

cat > "$work/metadata/$app_id.yml" <<YAML
Categories:
  - Games
License: GPL-3.0-only
AuthorName: stroexd
SourceCode: https://github.com/stroexd/hs-deck-tracker-android
IssueTracker: https://github.com/stroexd/hs-deck-tracker-android/issues
Changelog: https://github.com/stroexd/hs-deck-tracker-android/releases
AntiFeatures:
  NonFreeComp:
    en-US: Uses Google's ML Kit to read the screen on the device.
    de-DE: Nutzt Googles ML Kit, um den Bildschirm auf dem Gerät zu lesen.
  NonFreeNet:
    en-US: Loads meta statistics from HSReplay.net.
    de-DE: Lädt Meta-Statistiken von HSReplay.net.
YAML

cat > "$work/config.yml" <<YAML
repo_url: $repo_url
repo_name: HS Deck Tracker
repo_description: >-
  Releases of HS Deck Tracker, a deck tracker for Hearthstone on Android.
  Built from https://github.com/stroexd/hs-deck-tracker-android by its release workflow.
archive_older: 0
keystore: $keystore
repo_keyalias: fdroid
keystorepass: {env: SIGNING_PASSWORD}
keypass: {env: SIGNING_PASSWORD}
YAML
chmod 600 "$work/config.yml"

(cd "$work" && fdroid update --rename-apks --pretty)

rm -rf "$out"
mkdir -p "$out"
cp -r "$work/repo" "$out/"
