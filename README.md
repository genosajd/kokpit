# Kokpit F11

Launcher (ekran główny) na Androida dla radia Witstek (YA32, CAN NBT) w BMW F11 z iDrive NBT.
Kotlin + Jetpack Compose, ekran 2560x720.

## Co potrafi

- Bloki na siatce 8x2, które można przestawiać, powiększać i chować: skróty aplikacji, auto, nawigacja, kamera, pogoda i dowolne widżety Androida (bloków „Widżet” może być kilka, widżet zmienia się przyciskiem „Zmień” w edycji układu).
- Skróty przestawia się przytrzymaniem i przeciągnięciem; obsługa pokrętłem iDrive z wyraźnym zaznaczeniem.
- Wygląd do wyboru w Ustawieniach: Płaski oraz animowane BMW iDrive (niebieski) i M Sport (czerwony), tryb ciemny albo jasny; każdy wygląd można dostosować (kolor poświaty, tła i akcentu, krój i rozmiar pisma). Nowy wygląd to jeden wpis w `Themes.kt`.
- **Dane z auta przez moduł CAN radia:** paliwo w litrach, otwarte drzwi i klapa (widok z góry), bieg, ostrzeżenie o niezapiętym pasie (domyślnie wyłączone), temperatura zewnętrzna, prędkość (awaryjnie z GPS).
- Zdjęcie auta albo model 3D obracany palcem (Filament).
- Trasa z powiadomień Map Google i Waze; przyciski Dom i Praca otwierają miejsca zapisane w Mapach, plus trzecie miejsce z własną nazwą (np. etykieta z Map).
- **Blok „Asystent” (AI):** rozmowa z Claude (Anthropic) albo OpenAI, głosem lub pisaniem, odpowiedzi czytane na głos (głosy Google albo OpenAI). Zna stan auta, włącza nawigację, uruchamia i steruje YouTube/odtwarzaczem, zmienia głośność, jasność i wygląd, robi zdjęcie z kamery Xiaomi i opisuje je, obraca kamerą (punkty, zoom – wymaga Xiaomi Cam 1.35+). Wywołanie też przyciskiem asystenta (np. na kierownicy) – okienko asystenta nad dowolną aplikacją; przy pierwszym naciśnięciu wybierz „Kokpit F11 – Zawsze”. Klucz API wpisuje się w Ustawieniach → Asystent AI i zostaje tylko w radiu; za zapytania płaci się u dostawcy.
- **Blok „Klima”:** klimatyzator ze Smart Life przez chmurę Tuya (darmowy projekt na platform.tuya.com, klucze w Ustawieniach → Dom) – temperatura w domu, wł./wył., temperatura, tryb i nawiew; asystent też nim steruje.
- Ekran może mieć do 3 stron przewijanych w bok (Ustawienia → Ekran); przyciski reagują widocznym wciśnięciem.
- **Hasło „OK BMW”** (Ustawienia → Asystent AI, domyślnie wyłączone): rozpoznawane w całości w radiu, bez internetu i zewnętrznych usług (Vosk, polski model). Wyłączone nie zużywa mikrofonu ani procesora. Gdy radio gra (muzyka, film, odpowiedź asystenta), nie słucha. Czułość do wyboru (niska/średnia/wysoka). Wymaga zgody na mikrofon i na wyświetlanie nad innymi aplikacjami.
- Asystent ma własne instrukcje (np. jak się do Ciebie zwracać) w Ustawieniach → Asystent AI.
- Rozmowa z asystentem zostaje po restarcie radia. Zaczyna się od nowa po przekroczeniu limitu tokenów (Ustawienia → Asystent AI, domyślnie 500 tys.) albo przyciskiem odświeżenia w bloku.
- Podgląd kamery Xiaomi na żywo (RTSP z aplikacji Xiaomi Cam z serwerem w tle); gdy dekoder sprzętowy nie da rady, przełącza się na programowy.
- Przycisk „iDrive” (oryginalny ekran BMW) i „Radio” (ustawienia Witstek).
- Podzielony ekran Mapy + YouTube.

## Instalacja w radiu

1. Zainstaluj `Kokpit-F11-<wersja>-radio.apk` z zakładki Releases (arm64).
2. Przycisk Home → „Kokpit F11” → „Zawsze”.
3. Dane CAN (drzwi, bieg, temperatura) wymagają jednorazowo przez ADB:
   ```bash
   adb shell pm grant pl.lawinski.radiolauncher android.permission.READ_LOGS
   ```
4. Android 13 blokuje dostęp do powiadomień i ułatwień dostępu dla APK spoza sklepu. Odblokowanie:
   ```bash
   adb shell appops set pl.lawinski.radiolauncher ACCESS_RESTRICTED_SETTINGS allow
   ```

Powrót do starego launchera: Ustawienia → Aplikacje → Domyślne aplikacje → Ekran główny.

## Budowanie

```bash
cd launcher
export JAVA_HOME=<JDK 17+> ANDROID_HOME=<Android SDK>
../pobierz_model_mowy.sh             # raz: model mowy do „OK BMW” (53 MB, assets/vosk-pl.zip)
./gradlew assembleRelease -PdoAuta   # tylko arm64, do radia
./gradlew assembleRelease            # arm64 + x86_64, także na emulator
```

## Testy na emulatorze

`symuluj_can.sh` podsyła udawane ramki CAN (drzwi, bieg, pas, temperatura, paliwo, prędkość):

```bash
./symuluj_can.sh drzwi LP
./symuluj_can.sh bieg D
./symuluj_can.sh pokaz
```
