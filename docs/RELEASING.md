# Releasing

Everything runs in GitHub Actions, so a release needs nothing but a browser.

## Builds

| Flavor | Output | Differences |
|---|---|---|
| `github` | signed APK, attached to GitHub releases | background tracking through the app's accessibility service, bundled text recognition |
| `play` | signed App Bundle for Google Play | screen sharing only (Play restricts accessibility services), text recognition from Play services |

## One-time setup

1. Repository → Settings → Secrets and variables → Actions → add `UPLOAD_KEY_PASSWORD` (at least 20 random
   characters; keep it in a password manager).
2. Actions → **Create upload key** → Run workflow. It creates the upload key and commits it encrypted as
   `signing/upload.p12.enc`. The key itself never leaves the workflow unencrypted.

## Every release

- Push a tag like `v1.3.0` (or run **Release** by hand). The workflow tests, builds both flavors with an increasing
  version code, checks the 16 KB page alignment Google Play requires and uploads:
  - `play-bundle`: the `.aab` for the Play Console,
  - `hs-deck-tracker-apk`: the APK; with a tag it is also attached to a GitHub release.
- Optional: add `PLAY_SERVICE_ACCOUNT_JSON` (a Play Console service account key) and every release goes to the
  internal testing track by itself. The very first bundle has to be uploaded by hand in the Play Console.

## Google Play checklist

- Store texts: `fastlane/metadata/android/*`; privacy policy: `PRIVACY.md`.
- Declarations in the Play Console: foreground services (`mediaProjection` for screen capture, `specialUse` for the
  overlay), data safety (no data collected or shared), content rating, target audience.
- New personal developer accounts need a closed test with at least 12 testers for 14 days before production access.
