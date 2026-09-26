# HS Deck Tracker für Android

[![Android CI](https://github.com/stroexd/hs-deck-tracker-android/actions/workflows/android.yml/badge.svg)](https://github.com/stroexd/hs-deck-tracker-android/actions/workflows/android.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

[English](README.md) · **Deutsch**

Deck-Tracker, Deck-Datenbank und Sammlungsverwaltung für Hearthstone – **komplett auf dem Handy**,
inspiriert von HSReplay.net und HearthPwn.

Die App trackt deine Partien, während du Hearthstone auf demselben Gerät spielst. Sie liest den Bildschirm per
Texterkennung auf dem Gerät – **ohne Root, ohne Shizuku, ohne PC und ohne Hilfs-App**.

## Highlights

- **Automatisches Tracking** – Spielstart, beide Klassen, Mulligan und gezogene Karten, dein Deck, die gespielten
  Karten des Gegners, Züge und Ergebnis werden am Bildschirm erkannt. Ein Overlay zeigt, was noch im Deck ist und
  welches Deck der Gegner vermutlich spielt.
- **„Welche Decks kann ich bauen?“** – Sammlung direkt aus Hearthstone synchronisieren und für jedes Deck fehlende
  Karten und Arkanstaub-Kosten sehen: eigene Decks, Meta-Decks und beliebige Deck-Codes.
- **Meta-Decks** von HSReplay.net mit Siegquoten, filterbar nach Rangbereich und Zeitraum, sortierbar nach Staubkosten.
- **Match-History & Statistik** – jede Partie mit Zugverlauf, Matchups, Serien, am Zug vs. Münze.
- **Spricht die Sprache deines Spiels** – App und Kartendaten folgen der Sprache deines Hearthstone-Clients.

## Funktionen

### Automatischer Tracker & Overlay
- **Spielen & tracken** antippen (Startseite oder App-Symbol lange drücken): Hearthstone startet, der Rest läuft automatisch.
- Die Erkennung nutzt ML Kit **auf dem Gerät** – kein Bildschirmfoto verlässt das Handy.
  - Spielstart am Versus-Bildschirm bzw. beim Mulligan – der Tracker startet von selbst.
  - **Beide Klassen** werden von den Namensschildern gelesen.
  - **Starthand** inkl. ausgetauschter Karten, danach jede gezogene Karte.
  - **Dein Deck** wird aus den ersten Karten erkannt (zuerst eigene Decks, sonst Meta-Decks der passenden Klasse).
  - **Gegner:** im gegnerischen Zug eingeblendete Karten (nur Karten, die seine Klasse spielen darf) plus Meta-Deck-Vorhersage.
  - Nur echte Kartennamen zählen – Namen im Kartentext, Sprechblasen und „Entdecken“-Auswahl werden ignoriert.
  - Züge, am Zug/Münze und Ergebnis – die Partie landet automatisch in der Match-History.
- **Akkuschonend:** Aufnahme nur im Takt (Partie ca. 2 Bilder/s, Menü alle 1,5 s), dazwischen ist die Aufnahme
  abgekoppelt; unveränderte Bilder sparen sich die Texterkennung; Pause bei ausgeschaltetem Bildschirm oder im
  Hochformat; im Stromsparmodus bzw. bei Überhitzung automatisch langsamer.
- Verschiebbares, minimierbares Overlay; jede Erkennung lässt sich per Antippen korrigieren.
- Android fragt einmal pro Sitzung nach der Bildschirmaufnahme (Systemvorgabe) und einmalig nach
  „Über anderen Apps einblenden“.

### Sammlung & Herstellen
- **Aus Hearthstone synchronisieren:** Sammlung im Spiel öffnen und durchblättern – die App liest Kartennamen und
  Anzahl vom Bildschirm. Im Spiel nach Set filtern, um nur neue Karten zu synchronisieren; Karten, an denen du nicht
  vorbeiblätterst, behalten ihre Anzahl. Nachdrucke mit gleichem Namen werden zuerst der kostenlosen Kernset- bzw.
  Standard-Version zugeordnet.
- **Packungen und Herstellen aktualisieren sie automatisch**, solange die Erkennung läuft: Karten aus geöffneten
  Packungen (einzeln oder die Zusammenfassung nach vielen Packungen) kommen dazu und werden als neu, weiteres Exemplar
  oder Duplikat markiert; hergestellte Karten kommen dazu, entzauberte werden entfernt – inklusive Staub. Nach
  „Überzählige Karten entzaubern“ fragt das Overlay nach, bevor die Extras entfernt werden, und jede Änderung lässt sich
  im Overlay rückgängig machen.
- Oder die Sammlung als Datei hochladen bzw. einfügen:
  - HSReplay-Sammlungs-JSON: `{"collection": {"<dbfId>": [normal, golden, diamant, signatur]}, "dust": 1234}`
  - JSON-Listen/-Maps mit dbfId, Karten-ID (`CS2_029`) oder Kartenname und Anzahl
  - CSV/Tabellen mit oder ohne Kopfzeile (`Name;Anzahl;Golden`, `dbfId,count,golden` …)
  - Freitext, eine Karte pro Zeile (`2x Feuerball`, `CS2_029 2`, `Leeroy Jenkins`)
- Staubkosten jedes Decks, Filter „Sofort baubar“ und „Mit meinem Staub“, günstigste Meta-Decks für deine Sammlung
- Lohnende Herstellungen: Karten, die die meisten beliebten Meta-Decks vervollständigen
- Set-Fortschritt, Staub zum Vervollständigen, Staub durch Entzaubern überzähliger Karten
- Karten einzeln pflegen oder ganze Sets/Decks als besessen markieren; Export als JSON (HSReplay-Format) oder CSV

### Decks
- Deck-Codes importieren – mehrere auf einmal oder per „Teilen“ aus anderen Apps
- Deckbauer mit Suche, Mana-/Klassen-/Formatprüfung, Manakurve und Staubwert
- Deck-Code direkt in Hearthstone einfügen; Sideboards (E.T.C., Zilliax) werden unterstützt

### Kartendatenbank
- Alle sammelbaren Karten mit Bildern, Suche nach Name, Text, Stamm oder Schlüsselwort
- Filter nach Format, Klasse, Mana, Seltenheit, Typ, Set und Besitz
- **Neue Sets kommen von selbst:** Die App prüft beim Start und bei jedem Tracking-Start, ob es einen neuen
  Spiel-Build gibt, und lädt die neuen Karten sofort. Set-Namen stammen aus Hearthstones eigenen Texten in deiner
  Sprache, und Standard folgt jeder Rotation – erkannt an den Sets, die aktuelle Standard-Decks spielen; brandneue
  Sets zählen sofort als Standard. Einzelne Sets lassen sich in den Einstellungen weiterhin umschalten.

### Match-History & Statistik
- Partien nach Tagen gruppiert, Volltextsuche (Deck, Archetyp, Gegnerkarte, Notiz) und Filter
- Je Partie: Ergebnis, Decks, vermutetes Meta-Deck des Gegners, Reihenfolge, Züge, Dauer, Gegnerkarten,
  eigene gezogene Karten und **Zugverlauf**
- Partien bearbeiten, löschen, teilen oder als CSV exportieren
- Siegquote gesamt, pro Deck und pro Gegnerklasse, am Zug vs. Münze, Serien und Verlaufsdiagramm
- Backup und Wiederherstellung aller Daten als eine JSON-Datei

## Sprachen

Die App folgt der **Sprache deines Hearthstone-Clients**: Sie wird während einer Partie am Bildschirmtext erkannt
und für Kartendaten und App verwendet. Bis dahin gilt die Gerätesprache. In den Einstellungen lässt sich die Sprache
auch fest wählen.

| | Oberfläche | Kartendaten | Automatische Erkennung |
|---|:-:|:-:|:-:|
| Deutsch, Englisch | ✓ | ✓ | ✓ |
| Französisch, Spanisch, Italienisch, Polnisch, Portugiesisch | Englisch | ✓ | ✓ |
| Russisch, Koreanisch, Japanisch, Chinesisch, Thai | Englisch | ✓ | – |

Die automatische Erkennung nutzt derzeit die Texterkennung für lateinische Schrift. Übersetzungen der Oberfläche sind
willkommen – alle Texte liegen in `app/src/main/res/values*/strings.xml`.

## Installation

Jeder Push baut per GitHub Actions APKs: **Actions → Android CI → neuester Lauf** öffnen, das Artefakt
`hs-deck-tracker-apk` herunterladen, entpacken und `app-release.apk` installieren (Installation aus unbekannten
Quellen erlauben).

Benötigt Android 8.0 oder neuer; die Erkennung funktioniert am besten im Querformat mit Hearthstone im Vollbild.

## Datenschutz

- Bildschirminhalte werden **nur auf dem Gerät** verarbeitet und nicht gespeichert – außer im Diagnose-Modus, der
  erkannte Texte und einige verkleinerte Bildschirmfotos lokal ablegt, bis du sie teilst oder löschst.
- Netzwerkzugriffe gibt es nur für Kartendaten und -bilder (HearthstoneJSON), Set-Namen (HearthSims Kopie der
  Spieltexte) und Meta-Statistiken (HSReplay.net oder eine selbst eingetragene URL). Kein Konto, keine Analyse, kein
  Tracking.

## Entwicklung

```bash
./gradlew :core:test            # Unit-Tests der plattformunabhängigen Kernlogik
./gradlew :app:assembleDebug    # Debug-APK
```

| Modul | Inhalt |
|---|---|
| `core/` | Reines Kotlin/JVM: Deck-Code-Codec, Kartendatenbank, Sammlungs-Import, Staubkosten, Meta-Parser, Gegner-Vorhersage, Statistik, Datenhaltung und die Zustandsmaschine der Bildschirmerkennung (`vision/`) |
| `app/` | Android-App: Jetpack Compose + Material 3, Overlay-Service, MediaProjection-Aufnahme und ML-Kit-Texterkennung |

Die Erkennungslogik liegt in `core` und wird ohne Gerät getestet. Mit aktivierter Diagnose zeichnet die App eine
Sitzung auf (erkannter Text je Bild); diese lässt sich am Rechner erneut abspielen:

```bash
HS_DIAG_DIR=<Sitzungsordner> HS_CARDS_DIR=<Ordner mit cards.enUS.json / cards.deDE.json> ./gradlew :core:test --tests '*DiagnosticsReplay*'
```

## Lizenz

HS Deck Tracker ist freie Software unter der [GNU General Public License v3.0](LICENSE).

## Quellen & Hinweis

Kartendaten und -bilder: [HearthstoneJSON](https://hearthstonejson.com) von HearthSim; Set-Namen und Menütexte:
[HearthSim/hsdata](https://github.com/HearthSim/hsdata).
Meta-Statistiken: [HSReplay.net](https://hsreplay.net) (inoffizielle Nutzung der öffentlichen Website-Schnittstelle).

Inoffizielles Fan-Projekt, nicht mit Blizzard Entertainment, HSReplay.net, HearthSim oder HearthPwn verbunden oder
von ihnen unterstützt. Hearthstone® ist eine eingetragene Marke von Blizzard Entertainment, Inc.
