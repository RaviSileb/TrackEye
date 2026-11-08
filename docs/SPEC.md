# TrackEye — specifikace (shrnutí zadání)

Zdroj: `TrackEye.docx` v kořenu repozitáře (původní zadání).

## Vize
Multiplatformní aplikace pro ovládání zařízení pohledem očí a mikro-gesty
obličeje. Cílová skupina: osoby s omezenou motorikou (třes, ochrnutí,
parkinsonismus, ALS) a bezbariérové použití.

## MVP (implementováno v tomto repozitáři)
1. Eye tracking kurzor / gaze dot (přední kamera, on-device ML).
2. Blink click + dwell click (nastavitelný dwell 300–1200 ms).
3. Scroll náklonem hlavy (head tilt), gaze gesta zpět/vpřed.
4. Minimální overlay (gaze dot, indikátor režimu, stav trackingu).
5. Profily + rychlá kalibrace (30–60 s).
6. Režimy: Kurzor / Scroll / Klávesnice / Scan & Select.
7. Nízká spotřeba: dynamické FPS, event-driven inference, vše lokálně.

## Platformy v tomto buildu
- Android: optimalizováno pro Samsung Galaxy S23 Ultra (SM-S918B),
  min. Android 10 (API 29), cíl API 35.
- Linux: Ubuntu 24.04 LTS (notebook, x86_64), GTK 3 + OpenCV.

## Soukromí
- Veškeré zpracování videa probíhá lokálně na zařízení.
- Android aplikace nemá oprávnění INTERNET, video se nikam neodesílá.
- Logy pouze lokálně, lze vypnout.
