# HS Deck Tracker for Android

[![Android CI](https://github.com/stroexd/hs-deck-tracker-android/actions/workflows/android.yml/badge.svg)](https://github.com/stroexd/hs-deck-tracker-android/actions/workflows/android.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**English** · [Deutsch](README.de.md)

A deck tracker, deck database and collection manager for Hearthstone that runs **entirely on your phone** –
inspired by HSReplay.net and HearthPwn.

It tracks your games while you play Hearthstone on the same device: it reads the screen with on-device text
recognition, so it needs **no root, no Shizuku, no PC and no helper app**.

**[Install](#installation)** – as an APK, from its own F-Droid repository or with Obtainium.

## Highlights

- **Automatic tracking** – game start, both classes, your mulligan and draws, your deck, the opponent's played
  cards, turns and the result are detected from the screen. A floating overlay shows what is left in your deck
  and what the opponent is likely playing.
- **"Which decks can I build?"** – sync your collection straight from Hearthstone and see the missing cards and
  Arcane Dust cost for every deck: your own, meta decks and any pasted deck code.
- **Meta decks** from HSReplay.net with win rates, filterable by rank range and time frame, sortable by dust cost.
- **Match history & stats** – every game with its turn-by-turn timeline, matchups, streaks, going first vs. coin.
- **Speaks your game's language** – the app and the card data follow the language of your Hearthstone client.

## Features

### Automatic tracker & overlay
- **Runs in the background (Android 11+):** set it up once, then just open Hearthstone – the overlay appears, games
  are tracked and saved with their result, no button and no screen-sharing prompt. This uses the app's own
  accessibility service, which only notices which app is in front and takes screenshots while Hearthstone is.
- Without it: tap **Play & track** and allow screen sharing once per session.
- Recognition uses ML Kit text recognition **on the device** – no screenshot ever leaves the phone.
  - The game start is detected on the versus screen or at the mulligan; the tracker starts by itself.
  - **Both classes** are read from the name plates.
  - **Opening hand** including mulliganed cards, then every drawn card.
  - **Your deck** is identified from the first cards (your decks first, then meta decks of the right class).
  - **Opponent:** cards shown during their turn (only cards their class can play) plus a meta deck prediction.
  - Only real card names count – names inside card text, speech bubbles and Discover choices are ignored.
  - Turns, going first/coin and the result – the game lands in the match history automatically.
- **Battery friendly:** the screen is captured in intervals (about 2 frames/s in a game, one every 1.5 s in menus)
  and the capture is detached in between; unchanged frames skip text recognition; recognition pauses while the
  screen is off or in portrait mode and slows down in battery saver or when the device is hot.
- **The overlay sits in the empty margin next to the board**, so on wide phones it covers no cards: its size follows
  the screen, it keeps clear of the camera and the rounded corners, and it snaps to the left or right margin when you
  drag it. It collapses to a bubble, and every detection can be corrected with a tap.

### Collection & crafting
- **Sync from Hearthstone:** open your collection in the game and flip through the pages – the app reads card names
  and copy counts from the screen. Filter by set in the game to sync only new cards; cards you don't flip past keep
  their count. Reprints that share a name are assigned to the free Core or Standard printing first.
- **Packs and crafting update it automatically** while recognition is running: cards from opened packs (single
  packs and the summary after opening many) are added and marked as new, extra copy or duplicate; crafted cards are
  added and disenchanted ones removed, dust included. After "Disenchant Extra Cards" the overlay asks before removing
  the extras, and every change can be undone in the overlay.
- Or upload your collection as a file or paste it:
  - HSReplay collection JSON: `{"collection": {"<dbfId>": [normal, golden, diamond, signature]}, "dust": 1234}`
  - JSON lists or maps with dbfId, card ID (`CS2_029`) or card name and count
  - CSV / tables with or without a header (`Name;Count;Golden`, `dbfId,count,golden`, …)
  - Free text, one card per line (`2x Fireball`, `CS2_029 2`, `Leeroy Jenkins`)
- Dust cost of every deck, "buildable now" and "craftable with my dust" filters, cheapest meta decks for your collection
- Most valuable crafts: cards that complete the most popular meta decks
- Set progress, dust needed to complete a set, dust from disenchanting extra copies
- Maintain cards one by one or mark whole sets/decks as owned; export as JSON (HSReplay format) or CSV

### Decks
- Import deck codes – several at once or shared from any other app
- Deck builder with search, mana/class/format checks, mana curve and dust value
- Copy a deck code straight into Hearthstone; sideboards (E.T.C., Zilliax) are supported

### Card database
- All collectible cards with images and search by name, text, tribe or keyword
- Filters for format, class, mana, rarity, type, set and ownership
- **New sets arrive by themselves:** the app checks for a new game build at start and whenever tracking starts, and
  loads the new cards right away. Set names come from Hearthstone's own texts in your language, and Standard follows
  each rotation – detected from the sets current Standard decks play, while brand-new sets count as Standard at once.
  Single sets can still be switched by hand in the settings.

### Match history & stats
- Games grouped by day, full-text search (deck, archetype, opponent card, note) and filters
- Per game: result, decks, likely opponent meta deck, turn order, turns, duration, opponent cards,
  your drawn cards and the **turn timeline**
- Edit, delete, share or export games as CSV
- Win rate overall, per deck and per opponent class, going first vs. coin, streaks and a trend chart
- Backup and restore of all data as a single JSON file

## Languages

The app follows your **Hearthstone client language**: it is detected from the text on screen during a game and
used for the card data and the app itself. Until a game has been seen, the device language is used. You can also
pick a language manually in the settings.

| | Interface | Card data | Automatic recognition |
|---|:-:|:-:|:-:|
| English, German | ✓ | ✓ | ✓ |
| French, Spanish, Italian, Polish, Portuguese | English | ✓ | ✓ |
| Russian, Korean, Japanese, Chinese, Thai | English | ✓ | – |

Automatic recognition currently uses the Latin script text recognizer. Translations of the interface are welcome –
all texts live in `app/src/main/res/values*/strings.xml`.

## Installation

<p>
  <a href="https://github.com/stroexd/hs-deck-tracker-android/releases/latest/download/hs-deck-tracker.apk"><img src="https://img.shields.io/github/v/release/stroexd/hs-deck-tracker-android?label=APK&logo=github&style=for-the-badge" alt="Download the APK" height="40"></a>
  <a href="https://github.com/stroexd/hs-deck-tracker-android/tree/fdroid"><img src="https://img.shields.io/badge/F--Droid-repository-1976D2?logo=fdroid&logoColor=white&style=for-the-badge" alt="F-Droid repository" height="40"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.stroexd.hsdecktracker%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fstroexd%2Fhs-deck-tracker-android%22%2C%22author%22%3A%22stroexd%22%2C%22name%22%3A%22HS%20Deck%20Tracker%22%7D"><img src="docs/badges/obtainium.png" alt="Get it on Obtainium" height="40"></a>
</p>

The same signed APK comes three ways – pick one:

| | How | Updates |
|---|---|---|
| **GitHub** | [Download the APK](https://github.com/stroexd/hs-deck-tracker-android/releases/latest/download/hs-deck-tracker.apk) on the phone and open it (allow your browser to install apps) | download the new version, it installs over the old one |
| **F-Droid repository** | In F-Droid, Droid-ify or Neo Store: Settings → Repositories → + and add `https://raw.githubusercontent.com/stroexd/hs-deck-tracker-android/fdroid/repo` – or scan the QR code on the [repository page](https://github.com/stroexd/hs-deck-tracker-android/tree/fdroid), which also shows the key fingerprint | through the app store |
| **Obtainium** | [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.stroexd.hsdecktracker%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fstroexd%2Fhs-deck-tracker-android%22%2C%22author%22%3A%22stroexd%22%2C%22name%22%3A%22HS%20Deck%20Tracker%22%7D) – it installs straight from the GitHub releases | through Obtainium |

Requires Android 8.0 or newer (background tracking: Android 11). For background tracking tap **Set up** in the app
and turn on "HS Deck Tracker" under Accessibility. If Android shows it greyed out ("restricted setting", mostly after
installing from the browser): App info → ⋮ → **Allow restricted settings**, then try again.

## Feedback

A card that isn't recognized, a bug or an idea? [Report a problem](https://github.com/stroexd/hs-deck-tracker-android/issues/new?template=problem.yml) or
[share an idea](https://github.com/stroexd/hs-deck-tracker-android/issues/new?template=idea.yml) – from the app too, under Settings → About. English or German, both are fine. For
recognition problems a screenshot or a diagnostics recording (Settings → Record diagnostics, then Share diagnostics)
helps most.

## Privacy

Full policy: [PRIVACY.md](PRIVACY.md).

- The accessibility service only reads which app is in front and takes screenshots only while Hearthstone is; it
  never reads the content of other apps.
- Screen content is processed **only on the device** and never stored – unless you turn on the diagnostics mode,
  which keeps recognized texts and a few downscaled screenshots locally until you share or delete them.
- Network access is limited to downloading card data and images (HearthstoneJSON), set names (HearthSim's copy of the
  game texts) and meta statistics (HSReplay.net, or a URL you configure). There is no account, no analytics and no
  tracking.

## Development

```bash
./gradlew :core:test            # unit tests of the platform-independent core
./gradlew :app:assembleDebug    # debug APK
```

| Module | Contents |
|---|---|
| `core/` | Pure Kotlin/JVM: deck code codec, card database, collection import, crafting, meta parsing, opponent prediction, statistics, persistence and the screen recognition state machine (`vision/`) |
| `app/` | Android app: Jetpack Compose + Material 3 UI, overlay, screen capture (screen sharing or accessibility screenshots) and ML Kit text recognition |

The recognition logic lives in `core` and is tested without a device. With diagnostics enabled the app records a
session (recognized text per frame); such a session can be replayed on the desktop:

```bash
HS_DIAG_DIR=<session dir> HS_CARDS_DIR=<dir with cards.enUS.json / cards.deDE.json> ./gradlew :core:test --tests '*DiagnosticsReplay*'
```

## License

HS Deck Tracker is free software, licensed under the [GNU General Public License v3.0](LICENSE).

Releases are built by GitHub Actions, see [docs/RELEASING.md](docs/RELEASING.md).

## Credits & disclaimer

Card data and images: [HearthstoneJSON](https://hearthstonejson.com) by HearthSim; set names and menu texts:
[HearthSim/hsdata](https://github.com/HearthSim/hsdata).
Meta statistics: [HSReplay.net](https://hsreplay.net) (unofficial use of the public website API).

This is an unofficial fan project and is not affiliated with or endorsed by Blizzard Entertainment, HSReplay.net,
HearthSim or HearthPwn. Hearthstone® is a registered trademark of Blizzard Entertainment, Inc.
