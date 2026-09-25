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

## Highlights

- **Automatic tracking** – game start, both classes, your mulligan and draws, your deck, the opponent's played
  cards, turns and the result are detected from the screen. A floating overlay shows what is left in your deck
  and what the opponent is likely playing.
- **"Which decks can I build?"** – upload your collection once and see the missing cards and Arcane Dust cost for
  every deck: your own, meta decks and any pasted deck code.
- **Meta decks** from HSReplay.net with win rates, filterable by rank range and time frame, sortable by dust cost.
- **Match history & stats** – every game with its turn-by-turn timeline, matchups, streaks, going first vs. coin.
- **Speaks your game's language** – the app and the card data follow the language of your Hearthstone client.

## Features

### Automatic tracker & overlay
- Tap **Play & track** (home screen, or long-press the app icon): Hearthstone starts and everything else is automatic.
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
- A draggable, collapsible overlay; every detection can be corrected with a tap.
- Android asks for screen capture permission once per session (a system requirement) and once for
  "Display over other apps".

### Collection & crafting
- Upload your collection as a file or paste it:
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

Every push builds APKs with GitHub Actions: open **Actions → Android CI → latest run** and download the
`hs-deck-tracker-apk` artifact. Unzip it and install `app-release.apk` (allow installing from unknown sources).

Requires Android 8.0 or newer; automatic recognition works best in landscape with Hearthstone in full screen.

## Privacy

- Screen content is processed **only on the device** and never stored – unless you turn on the diagnostics mode,
  which keeps recognized texts and a few downscaled screenshots locally until you share or delete them.
- Network access is limited to downloading card data and images (HearthstoneJSON) and meta statistics (HSReplay.net,
  or a URL you configure). There is no account, no analytics and no tracking.

## Development

```bash
./gradlew :core:test            # unit tests of the platform-independent core
./gradlew :app:assembleDebug    # debug APK
```

| Module | Contents |
|---|---|
| `core/` | Pure Kotlin/JVM: deck code codec, card database, collection import, crafting, meta parsing, opponent prediction, statistics, persistence and the screen recognition state machine (`vision/`) |
| `app/` | Android app: Jetpack Compose + Material 3 UI, overlay service, MediaProjection capture and ML Kit text recognition |

The recognition logic lives in `core` and is tested without a device. With diagnostics enabled the app records a
session (recognized text per frame); such a session can be replayed on the desktop:

```bash
HS_DIAG_DIR=<session dir> HS_CARDS_DIR=<dir with cards.enUS.json / cards.deDE.json> ./gradlew :core:test --tests '*DiagnosticsReplay*'
```

## License

HS Deck Tracker is free software, licensed under the [GNU General Public License v3.0](LICENSE).

## Credits & disclaimer

Card data and images: [HearthstoneJSON](https://hearthstonejson.com) by HearthSim.
Meta statistics: [HSReplay.net](https://hsreplay.net) (unofficial use of the public website API).

This is an unofficial fan project and is not affiliated with or endorsed by Blizzard Entertainment, HSReplay.net,
HearthSim or HearthPwn. Hearthstone® is a registered trademark of Blizzard Entertainment, Inc.
