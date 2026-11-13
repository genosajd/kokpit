# Kokpit F11

Launcher (ekran główny) na Androida dla radia Witstek (YA32, CAN NBT) w BMW F11 z iDrive NBT.
Kotlin + Jetpack Compose, ekran 2560x720.

## Co potrafi

- Bloki na siatce 8x2, które można przestawiać, powiększać i chować: skróty aplikacji, auto, nawigacja, kamera, pogoda i dowolne widżety Androida (bloków „Widżet” może być kilka, widżet zmienia się przyciskiem „Zmień” w edycji układu).
- Skróty przestawia się przytrzymaniem i przeciągnięciem; obsługa pokrętłem iDrive z wyraźnym zaznaczeniem.
- Wygląd do wyboru w Ustawieniach: Płaski, animowane BMW iDrive (niebieski), M Sport (czerwony) i iDrive 8 (ścięte rogi, ukośne światło w tle, niebiesko-fioletowy), tryb ciemny albo jasny; każdy wygląd można dostosować (kolor poświaty, tła i akcentu, krój i rozmiar pisma). Nowy wygląd to jeden wpis w `Themes.kt`.
- **Dane z auta przez moduł CAN radia:** paliwo w litrach, otwarte drzwi i klapa (widok z góry), bieg, ostrzeżenie o niezapiętym pasie (domyślnie wyłączone), temperatura zewnętrzna, prędkość (awaryjnie z GPS).
- Zdjęcie białego F11 w bloku auta (przy otwartych drzwiach widok z góry).
- Trasa z powiadomień Map Google i Waze; przyciski Dom i Praca otwierają miejsca zapisane w Mapach, plus trzecie miejsce z własną nazwą (np. etykieta z Map).
- **Blok „Asystent” (AI):** rozmowa z Claude (Anthropic) albo OpenAI, głosem lub pisaniem, odpowiedzi czytane na głos (głosy Google, OpenAI albo ElevenLabs – własny klucz, głosy z konta; po wyczerpaniu limitu znaków Kokpit sam przechodzi na głos Google; z modelem „ekspresyjny” asystent dodaje emocje i odgłosy – śmiech, westchnienie, złość – których nie widać w rozmowie). Zna stan auta, włącza nawigację, uruchamia i steruje YouTube/odtwarzaczem, zmienia głośność, jasność i wygląd, czyta i dopisuje terminy w kalendarzu Google (konto zalogowane w radiu), robi zdjęcie z kamery Xiaomi i opisuje je, obraca kamerą (punkty, zoom – wymaga Xiaomi Cam 1.35+). Wywołanie też przyciskiem asystenta (np. na kierownicy) – okienko asystenta nad dowolną aplikacją; przy pierwszym naciśnięciu wybierz „Kokpit F11 – Zawsze”. Okienko czeka chwilę na dalszy ciąg zdania po pauzie (czas do ustawienia) i ma przycisk powtórzenia odpowiedzi. Gdy asystent mówi, muzyka jest ściszana. Klucz API wpisuje się w Ustawieniach → Asystent AI i zostaje tylko w radiu; za zapytania płaci się u dostawcy.
- **Blok „Klima”:** klimatyzator ze Smart Life przez chmurę Tuya (darmowy projekt na platform.tuya.com, klucze w Ustawieniach → Dom) – temperatura i wilgotność w domu z czujnika Tuya (np. „T & H Sensor”; wybór w Ustawieniach → Dom), obok odczyt klimatyzatora, wł./wył., temperatura, tryb i nawiew; asystent też nim steruje. Po poleceniu przyciski czekają, aż urządzenie potwierdzi wykonanie.
- **Blok „Urządzenie Tuya”** (można dodać kilka): dowolne urządzenie z projektu Tuya wybrane przyciskiem „Zmień” w edycji układu – włączniki, jasność, tryb oraz odczyty temperatury, wilgotności, mocy i baterii.
- Ekran może mieć do 3 stron przewijanych w bok (Ustawienia → Ekran); przyciski reagują widocznym wciśnięciem. Układ można zapisać i przywrócić w edycji układu („Zapisz układ” / „Przywróć zapisany”).
- **Hasło „OK BMW”** (Ustawienia → Asystent AI, domyślnie wyłączone): rozpoznawane w całości w radiu, bez internetu i zewnętrznych usług (Vosk, polski model). Wyłączone nie zużywa mikrofonu ani procesora. Słucha też przy muzyce (z systemowym usuwaniem echa, jeśli radio je ma); nie słucha tylko, gdy mówi asystent. Czułość do wyboru (niska/średnia/wysoka). Wymaga zgody na mikrofon i na wyświetlanie nad innymi aplikacjami.
- **Slack** (opcjonalnie): asystent czyta nieprzeczytane, wiadomości i wątki, wyszukuje i pisze w Twoim imieniu – wysłanie zawsze po potwierdzeniu w kolejnej wypowiedzi. Potrzebna własna aplikacja Slack z uprawnieniami użytkownika; jej User OAuth Token wkleja się w Ustawieniach → Asystent AI → Slack.
- **Miejsca i trasy Google** (opcjonalnie): asystent szuka miejsc w pobliżu albo koło podanej miejscowości (adres, odległość, ocena, czy teraz otwarte, ceny paliw na stacjach), podaje czas dojazdu z korkami i trasy alternatywne oraz włącza nawigację z przystankami. Potrzebny własny klucz Google Maps Platform z włączonymi „Places API (New)” i „Routes API” – wpisuje się go w Ustawieniach → Nawigacja → „Klucz Google Maps”. Nawigacja z przystankami działa bez klucza.
- **Dostępy asystenta** (Ustawienia → Asystent AI): przełączniki dla auta, nawigacji, aplikacji, muzyki, radia, kamer, domu, kalendarza, Slacka, miejsc i tras Google oraz internetu – wyłączonego dostępu asystent nie dostaje.
- **Reguły** (Ustawienia → Reguły): własne „jeśli … to …” – paliwo, akumulator, temperatura na zewnątrz, prędkość, temperatura i wilgotność w domu (mniej / więcej niż wpisana liczba), Slack (napisała osoba, wiadomość na kanale, oznaczenie) albo kamera (asystent dostaje zdjęcie co ustawioną przerwę, po ruszeniu lub o godzinie i odzywa się tylko, gdy jest co zgłosić, np. otwarta furtka). Asystent wykonuje wpisane polecenie albo Kokpit czyta dokładnie wpisany tekst (bez tokenów).
- **Blok „Multimedia”:** to, co gra w dowolnym odtwarzaczu (YouTube, SoundCloud…) – okładka, tytuł, przewijanie, poprzedni / pauza / następny. Przesunięcie palcem po okładce przełącza okładkę i pięć wizualizacji dźwięku (słupki, lustro, segmenty, kreski ze szczytami, koło).
- **Własne hasło głosowe** zamiast „OK BMW” (słowa ze słownika modelu), automatyczne wzmocnienie cichego mikrofonu, wybór trybu mikrofonu i „Test mikrofonu” pokazujący, co radio słyszy.
- **Radio Witstek:** głośność wzmacniacza radia (0–40) w górnym pasku i przez asystenta, napięcie akumulatora auta, rozmowa telefoniczna → ekran iDrive, przechył kontrolera iDrive w górę i w dół.
- Na czas nawigacji Kokpit może wyłączyć wyskakujące komunikaty Map (wymaga `adb shell pm grant pl.lawinski.radiolauncher android.permission.WRITE_SECURE_SETTINGS`; bez tego kafelek otwiera ustawienia powiadomień Map).
- Edycja układu: przycisk „Widżety” otwiera okno wyboru bloków z podglądem. Okienko zgody na dzienniki można wyłączyć przełącznikiem „Dane auta z dzienników radia” (wtedy bez paliwa na żywo).
- **Eksport, import i reset ustawień** (Ustawienia → Radio i system): wszystkie ustawienia do pliku JSON (z kluczami albo bez), wczytanie z pliku oraz powrót do stanu początkowego z usunięciem kluczy, tokenów i rozmowy z asystentem. Instrukcje dla asystenta mają własny eksport i import pliku tekstowego.
- **Kontroler iDrive:** przechył przenosi zaznaczenie na sąsiedni blok (także na następną stronę ekranu), obrót chodzi po przyciskach.
- Asystent ma własne instrukcje (np. jak się do Ciebie zwracać) w Ustawieniach → Asystent AI.
- Rozmowa z asystentem zostaje po restarcie radia. Zaczyna się od nowa po przekroczeniu limitu tokenów (Ustawienia → Asystent AI, domyślnie 500 tys.) albo przyciskiem odświeżenia w bloku.
- Podgląd kamery Xiaomi na żywo (RTSP z aplikacji Xiaomi Cam z serwerem w tle); gdy dekoder sprzętowy nie da rady, przełącza się na programowy.
- Górny pasek: zasięg sieci komórkowej (LTE/5G/3G) i Wi-Fi, brak internetu, stan akumulatora (napięcie, jeśli radio je podaje) i głośność z suwakiem.
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
