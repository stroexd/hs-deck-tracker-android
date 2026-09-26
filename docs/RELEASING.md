# Releasing

Everything runs in GitHub Actions, so a release needs nothing but a browser.

## One-time setup

Repository → Settings → Secrets and variables → Actions → **New repository secret**: `SIGNING_PASSWORD`, at least
20 random characters. Keep it in a password manager – without it no update can be signed.

The first release creates the signing keys and commits them encrypted with that password as `signing/keys.p12.enc`;
the keys never leave the workflow unencrypted.

## Every release

Raise `versionName` in `app/build.gradle.kts` (for example in the pull request) – when it reaches `main`, the
**Release** workflow publishes it. It can also be started by hand (Actions → Release → Run workflow; without a
version it takes the next patch version) or with a tag like `v1.4.0`. The workflow

1. runs the tests and builds the APK with version code `major·10000 + minor·100 + patch`, signed with the key
   `release`,
2. publishes a GitHub release with `hs-deck-tracker.apk` and notes generated from the merged pull requests,
3. rebuilds the F-Droid repository on the `fdroid` branch, signed with the key `fdroid`; its "what's new" text is taken
   from the release notes.

## Channels

| Channel | Address | Updates |
|---|---|---|
| GitHub | `https://github.com/stroexd/hs-deck-tracker-android/releases/latest/download/hs-deck-tracker.apk` | manual |
| F-Droid repository | `https://raw.githubusercontent.com/stroexd/hs-deck-tracker-android/fdroid/repo` (fingerprint on the `fdroid` branch) | F-Droid, Droid-ify, Neo Store |
| Obtainium | reads the GitHub releases | Obtainium |

Name, descriptions and icon in the F-Droid repository come from `fastlane/metadata/android/`. CI builds the
repository with a throwaway key on every push, so a broken setup shows up before a release.

## Keys

- `release` signs the APK. Android only installs updates signed with the same key: if the key or the password is
  lost, users have to uninstall before they can install a new version.
- `fdroid` signs the repository index; app stores check it against the fingerprint users added.
