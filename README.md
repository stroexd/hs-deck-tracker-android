# HS Deck Tracker für Android

Deck-Tracker, Deck-Datenbank und Sammlungsverwaltung für Hearthstone – inspiriert von HSReplay.net und HearthPwn, komplett auf dem Handy.

## Funktionen

### Sammlung & „Welche Decks kann ich bauen?“
- **Sammlung hochladen:** Datei (JSON/CSV/Text) oder Text einfügen. Unterstützt werden:
  - HSReplay-Sammlungs-JSON: `{"collection": {"<dbfId>": [normal, golden, diamant, signatur]}, "dust": 1234}`
  - JSON-Listen/-Maps mit dbfId, Karten-ID (`CS2_029`) oder Kartenname und Anzahl
  - CSV/Tabellen mit Kopfzeile (`Name;Anzahl;Golden`, `dbfId,count,golden` …) oder ohne
  - Freitext, eine Karte pro Zeile (`2x Feuerball`, `CS2_029 2`, `Leeroy Jenkins`)
- **Staubkosten für jedes Deck** (eigene Decks, Meta-Decks, eingefügte Deck-Codes): fehlende Karten, benötigter Arkanstaub, „mit meinem Staub herstellbar“, nicht herstellbare Karten
- **Filter „Sofort baubar“ / „Mit meinem Staub“** in der Meta-Liste und die günstigsten Meta-Decks für deine Sammlung
- **Deck-Code prüfen:** Codes einfügen und sofort sehen, was fehlt
- Set-Fortschritt inkl. Staub zum Vervollständigen, Überschuss-Staub durch Entzaubern
- Karten einzeln pflegen (antippen: 0 → 1 → 2), ganze Sets oder Decks als besessen markieren
- Export als JSON (HSReplay-Format) oder CSV

### Decks
- Deck-Codes importieren (auch mehrere auf einmal oder per „Teilen“ aus anderen Apps)
- Deckbauer mit Suche, Manafilter, Klassen-/Formatprüfung, Manakurve, Staubwert
- Deck-Code kopieren (direkt in Hearthstone einfügbar) und teilen
- Sideboards (E.T.C., Zilliax) werden unterstützt

### Meta-Decks (HSReplay)
- Aktuelle Decks mit Siegquote und Anzahl Spiele, Standard & Wild
- Filter nach Klasse, Rangbereich, Zeitraum; Sortierung nach Siegquote, Beliebtheit oder Staubkosten
- Alternativ: eigene Deck-Code-Liste per URL

### Kartendatenbank (wie HearthPwn)
- Alle sammelbaren Karten mit Bildern, Suche (Name, Text, Stamm, Schlüsselwort)
- Filter: Format, Klasse, Mana, Seltenheit, Typ, Set, Besitz

### Automatischer Tracker & Overlay
- **„Spielen & tracken“** (Startseite oder App-Symbol lange drücken): Hearthstone startet, danach läuft alles automatisch
- **Automatische Erkennung per Bildschirm-Texterkennung** (ML Kit, komplett auf dem Gerät):
  - Spielstart am Mulligan – der Tracker startet von selbst
  - **Eigenes Deck** wird aus den ersten Karten erkannt (eigene Decks, sonst Meta-Decks); gezogene Karten werden abgezogen
  - **Gegner:** gespielte Karten, Klasse und Deck-Vorhersage aus den Meta-Decks
  - Züge, am Zug/Münze und Ergebnis (Sieg/Niederlage) → Partie landet automatisch in der Match-History
- Hearthstone-Client auf Deutsch oder Englisch
- Verschiebbares, minimierbares **Overlay** über dem Spiel; Korrekturen per Antippen jederzeit möglich
- Android fragt einmal pro Sitzung nach der Bildschirmaufnahme (Systemvorgabe); einmalig muss „Über anderen Apps einblenden“ erlaubt werden
- Diagnose-Modus (Einstellungen): speichert erkannte Texte + einige Bildschirmfotos lokal, um die Erkennung für das eigene Gerät zu verbessern

### Match-History (wie „My Replays“ bei HSReplay)
- Alle Partien, nach Tagen gruppiert mit Tagesbilanz
- Suche (Deck, Archetyp, Gegnerkarte, Notiz) und Filter nach Ergebnis, Format, Deck, Gegnerklasse, Zeitraum
- Detailansicht je Partie: Ergebnis, Deck, Gegner & vermutetes Meta-Deck, Reihenfolge, Züge, Dauer,
  gespielte Gegnerkarten, eigene gezogene Karten und **Zugverlauf** („Replay light“ aus dem Tracker)
- Partien bearbeiten (Ergebnis, Gegner, Archetyp, Notiz), löschen, teilen, als CSV exportieren
- „Alle Partien mit diesem Deck“ direkt aus der Deck-Ansicht

### Statistik (wie „My Stats“ bei HSReplay)
- Siegquote gesamt, pro Deck, pro Gegnerklasse (Matchups), am Zug vs. Münze, Serien
- Verlaufsdiagramm, manuelle Einträge
- Backup & Wiederherstellung aller Daten

## Installation

Jeder Push baut per GitHub Actions eine APK:
**Actions → „Android CI“ → neuester Lauf → Artefakt `hs-deck-tracker-apk`** herunterladen, entpacken und `app-release.apk` auf dem Handy installieren (Installation aus unbekannten Quellen erlauben).

## Entwicklung

```bash
./gradlew :core:test            # Unit-Tests der Kernlogik (reines Kotlin)
./gradlew :app:assembleDebug    # Debug-APK
```

- `core/` – plattformunabhängige Logik: Deck-Code-Codec, Sammlungs-Import, Staubkosten, Tracker, Log-Parser, Statistik, HSReplay-Parser, Datenhaltung
- `app/` – Android-App (Kotlin, Jetpack Compose, Material 3, Coil)

Datenquellen: [HearthstoneJSON](https://hearthstonejson.com) (Kartendaten und -bilder), HSReplay.net (Meta-Statistiken, inoffizielle Website-Schnittstelle).

Inoffizielles Fan-Projekt – nicht mit Blizzard Entertainment, HSReplay.net, HearthSim oder HearthPwn verbunden.
