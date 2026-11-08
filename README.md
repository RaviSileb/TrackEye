# TrackEye

Eye & micro-gesture ovládání zařízení pro osoby s handicapem a bezbariérové použití.
Zadání: viz [docs/SPEC.md](docs/SPEC.md) (původní `TrackEye.docx`).

Pomocí přední/web kamery a on-device AI:
- sleduje pohled (gaze dot / kurzor),
- detekuje mikro-gesta (mrknutí, dvojmrknutí, dlouhé mrknutí, náklon hlavy),
- mapuje gesta na klik, scroll, zpět/vpřed, přepnutí režimu,
- nabízí režimy Kurzor / Scroll / Klávesnice / Scan & Select,
- rychlou kalibraci, lokální profily, trénink a statistiky (comfort score),
- minimalistický overlay a šetření baterie (dynamické FPS).

**Soukromí:** veškeré zpracování videa probíhá lokálně. Android aplikace nemá
oprávnění INTERNET, žádná aplikace neodesílá video na server.

## Stáhnout

Hotové balíčky jsou v [GitHub Releases](../../releases):
- **Android (Samsung Galaxy S23 Ultra a další, Android 10+):** `TrackEye-1.0.0.apk`
- **Ubuntu 24.04:** `trackeye_1.0.0_all.deb`

## Android (S23 Ultra)

Optimalizace pro Galaxy S23 Ultra (SM-S918B): přední kamera, API 35,
on-device ML Kit Face Detection.

```bash
cd android
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # jednotkové testy gaze enginu
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Systémové ovládání (klik/scroll/zpět kdekoli v Androidu): po instalaci povolte
*Nastavení → Zpřístupnění → TrackEye ovládání pohledem*. Bez tohoto povolení
funguje ovládání v testovací ploše uvnitř aplikace.

## Linux (Ubuntu 24.04)

```bash
# běh ze zdrojů (prostředí patří do projektu):
python3 -m venv .venv
.venv/bin/pip install opencv-python numpy   # nebo: sudo apt install python3-opencv python3-numpy
.venv/bin/python linux/trackeye.py

# .deb balíček:
./linux/build-deb.sh
sudo apt install ./build/trackeye_1.0.0_all.deb
trackeye
```

Volitelné systémové ovládání na X11: nainstalujte `xdotool` a zapněte
v Nastavení „Systémové ovládání přes xdotool". Na Waylandu zůstává ovládání
v rámci aplikace (omezení Waylandu).

## Struktura

- `android/` — Kotlin, CameraX + ML Kit, AccessibilityService
- `linux/` — Python 3 + GTK 3 + OpenCV, balení `.deb`
- `docs/SPEC.md` — shrnutí zadání a MVP

## Licence

MIT — viz [LICENSE](LICENSE).
