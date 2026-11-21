// kokpit-echo: programowe usuwanie echa w rozmowach Bluetooth radia Witstek (Snapdragon, sterownik Qualcomm).
//
// Fabrycznie radio spina dźwięk rozmowy wewnątrz układu dźwiękowego (Bluetooth <-> głośnik / mikrofon) i echa
// nie usuwa. Ten program na czas rozmowy rozłącza fabryczne spięcie i sam przenosi dźwięk:
//     Bluetooth (głos rozmówcy) -> głośnik           + kopia jako sygnał odniesienia
//     mikrofon -> usuwanie echa -> Bluetooth (do rozmówcy)
// Wyłączony (polecenie „off”) oddaje dźwięk fabrycznemu torowi – radio działa jak bez programu.
//
// Sterowanie: TCP 127.0.0.1:47811, polecenia tekstowe „on”, „off”, „status” (jedno na połączenie).
// Wymaga roota (urządzenia /dev/snd). Nazwy przełączników miksera i numery urządzeń PCM pochodzą z pomiarów
// na radiu (YA32, karta bengal-qrd-snd-card); na innym radiu mogą być inne.
//
// Budowanie: ./build.sh (NDK). Wersja bez biblioteki WebRTC przenosi dźwięk bez zmian (do prób samego toru).

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <netinet/in.h>
#include <signal.h>
#include <sound/asound.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <string>
#include <vector>

#include "aec.h"

#define TAG "KokpitEcho"
static bool g_stderr = false;
static void logf(int prio, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char buf[512];
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    __android_log_write(prio, TAG, buf);
    if (g_stderr) fprintf(stderr, "%s\n", buf);
}
#define LOGI(...) logf(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) logf(ANDROID_LOG_WARN, __VA_ARGS__)

// ---------- ustawienia (wartości z radia użytkownika; do zmiany parametrami) ----------
struct Config {
    int card = 0;
    int pcmMic = 1;      // MultiMedia2: nagrywanie mikrofonu, odtwarzanie do głośnika
    int pcmBt = 9;       // MultiMedia5: nagrywanie z Bluetooth, odtwarzanie do Bluetooth
    int rate = 16000;
    int period = 320;    // 20 ms
    int periods = 4;
    int port = 47811;
    const char *stateFile = "/data/local/tmp/kokpit-echo.enabled";
} cfg;

// fabryczne spięcie rozmowy (ustawia je sterownik dźwięku radia na czas rozmowy)
static const char *FACTORY_DOWN = "RX_CDC_DMA_RX_0 Port Mixer SLIM_7_TX";       // Bluetooth -> głośnik
static const char *FACTORY_UP = "SLIMBUS_7_RX Audio Mixer MultiMedia6";         // mikrofon -> Bluetooth
static const char *CALL_FLAG = "HFP_SLIM7_UL_HL Switch";                        // włączone, gdy trwa dźwięk rozmowy
static const char *CALL_VOLUME = "SLIMBUS_7 LOOPBACK Volume";                   // głośność rozmówcy 0..8192
// nasz tor
static const char *OUR_MIC = "MultiMedia2 Mixer TX_CDC_DMA_TX_3";
static const char *OUR_SPK = "RX_CDC_DMA_RX_0 Audio Mixer MultiMedia2";
static const char *OUR_BT_IN = "MultiMedia5 Mixer SLIM_7_TX";
static const char *OUR_BT_OUT = "SLIMBUS_7_RX Audio Mixer MultiMedia5";

// ---------- mikser (bez tinyalsa: bezpośrednio ioctl) ----------
class Mixer {
    int fd = -1;
    std::vector<snd_ctl_elem_id> ids;

  public:
    bool open(int card) {
        char path[64];
        snprintf(path, sizeof path, "/dev/snd/controlC%d", card);
        fd = ::open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) { LOGW("mikser: %s: %s", path, strerror(errno)); return false; }
        snd_ctl_elem_list list;
        memset(&list, 0, sizeof list);
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return false;
        ids.resize(list.count);
        list.space = list.count;
        list.pids = ids.data();
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return false;
        return true;
    }
    const snd_ctl_elem_id *find(const char *name) const {
        for (auto &id : ids)
            if (!strcmp((const char *)id.name, name)) return &id;
        return nullptr;
    }
    /** pierwsza wartość przełącznika; -1 = nie ma takiego albo błąd */
    long get(const char *name) {
        const snd_ctl_elem_id *id = find(name);
        if (!id) return -1;
        snd_ctl_elem_value v;
        memset(&v, 0, sizeof v);
        v.id = *id;
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &v) < 0) return -1;
        return v.value.integer.value[0];
    }
    /** ustawia pierwszą wartość (pozostałe kanały zostają, jak były) */
    bool set(const char *name, long value) {
        const snd_ctl_elem_id *id = find(name);
        if (!id) { LOGW("mikser: brak „%s”", name); return false; }
        snd_ctl_elem_value v;
        memset(&v, 0, sizeof v);
        v.id = *id;
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &v) < 0) { LOGW("mikser: odczyt „%s”: %s", name, strerror(errno)); return false; }
        v.value.integer.value[0] = value;
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &v) < 0) { LOGW("mikser: zapis „%s”=%ld: %s", name, value, strerror(errno)); return false; }
        return true;
    }
};

// ---------- urządzenie PCM (bez tinyalsa) ----------
static void paramInit(snd_pcm_hw_params *p) {
    memset(p, 0, sizeof *p);
    for (int n = SNDRV_PCM_HW_PARAM_FIRST_MASK; n <= SNDRV_PCM_HW_PARAM_LAST_MASK; n++) {
        snd_mask *m = &p->masks[n - SNDRV_PCM_HW_PARAM_FIRST_MASK];
        m->bits[0] = ~0u;
        m->bits[1] = ~0u;
    }
    for (int n = SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; n <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL; n++) {
        snd_interval *i = &p->intervals[n - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
        i->min = 0;
        i->max = ~0u;
    }
    p->rmask = ~0u;
    p->info = ~0u;
}
static void paramMask(snd_pcm_hw_params *p, int n, unsigned bit) {
    snd_mask *m = &p->masks[n - SNDRV_PCM_HW_PARAM_FIRST_MASK];
    m->bits[0] = 0;
    m->bits[1] = 0;
    m->bits[bit >> 5] |= 1u << (bit & 31);
}
static void paramInt(snd_pcm_hw_params *p, int n, unsigned v) {
    snd_interval *i = &p->intervals[n - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    i->min = v;
    i->max = v;
    i->integer = 1;
}

class Pcm {
    int fd = -1;
    bool capture = false;
    std::string name;

  public:
    long xruns = 0;
    ~Pcm() { close(); }
    bool open(int card, int device, bool cap, int rate, int period, int periods) {
        capture = cap;
        char path[64];
        snprintf(path, sizeof path, "/dev/snd/pcmC%dD%d%c", card, device, cap ? 'c' : 'p');
        name = path;
        fd = ::open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) { LOGW("%s: %s", path, strerror(errno)); return false; }
        snd_pcm_hw_params hw;
        paramInit(&hw);
        paramMask(&hw, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
        paramMask(&hw, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
        paramMask(&hw, SNDRV_PCM_HW_PARAM_SUBFORMAT, SNDRV_PCM_SUBFORMAT_STD);
        paramInt(&hw, SNDRV_PCM_HW_PARAM_PERIOD_SIZE, period);
        paramInt(&hw, SNDRV_PCM_HW_PARAM_SAMPLE_BITS, 16);
        paramInt(&hw, SNDRV_PCM_HW_PARAM_FRAME_BITS, 16);
        paramInt(&hw, SNDRV_PCM_HW_PARAM_CHANNELS, 1);
        paramInt(&hw, SNDRV_PCM_HW_PARAM_PERIODS, periods);
        paramInt(&hw, SNDRV_PCM_HW_PARAM_RATE, rate);
        if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &hw) < 0) { LOGW("%s: parametry: %s", path, strerror(errno)); close(); return false; }
        snd_pcm_sw_params sw;
        memset(&sw, 0, sizeof sw);
        unsigned buffer = period * periods;
        sw.tstamp_mode = SNDRV_PCM_TSTAMP_ENABLE;
        sw.period_step = 1;
        sw.avail_min = period;
        sw.start_threshold = cap ? 1 : period * 2; // odtwarzanie rusza po dwóch okresach (zapas na nierówny rytm)
        sw.stop_threshold = buffer * 100;          // nie zatrzymuj przy chwilowym braku danych
        sw.silence_size = 0;
        sw.silence_threshold = 0;
        sw.boundary = buffer;
        while (sw.boundary * 2 <= (unsigned long)(INT_MAX - buffer)) sw.boundary *= 2;
        if (ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw) < 0) { LOGW("%s: parametry pracy: %s", path, strerror(errno)); close(); return false; }
        return prepare();
    }
    bool prepare() {
        if (ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0) { LOGW("%s: przygotowanie: %s", name.c_str(), strerror(errno)); return false; }
        if (capture && ioctl(fd, SNDRV_PCM_IOCTL_START) < 0) { LOGW("%s: start: %s", name.c_str(), strerror(errno)); return false; }
        return true;
    }
    /** czyta albo zapisuje dokładnie [frames] próbek; false = urządzenie nie działa */
    bool xfer(int16_t *buf, unsigned frames) {
        for (int attempt = 0; attempt < 3; attempt++) {
            pollfd pf = {fd, (short)(capture ? POLLIN : POLLOUT), 0};
            int pr = poll(&pf, 1, 500); // radio potrafi zatrzymać strumień przy zmianie swoich torów – nie czekaj w nieskończoność
            if (pr == 0) { LOGW("%s: brak danych przez 0,5 s", name.c_str()); return false; }
            if (pr < 0 && errno == EINTR) continue;
            snd_xferi x;
            x.result = 0;
            x.buf = buf;
            x.frames = frames;
            if (ioctl(fd, capture ? SNDRV_PCM_IOCTL_READI_FRAMES : SNDRV_PCM_IOCTL_WRITEI_FRAMES, &x) == 0) return true;
            if (errno == EINTR) continue;
            if (errno == EPIPE || errno == ESTRPIPE) { // przerwa w strumieniu – zacznij od nowa
                xruns++;
                if (!prepare()) return false;
                continue;
            }
            LOGW("%s: %s: %s", name.c_str(), capture ? "odczyt" : "zapis", strerror(errno));
            return false;
        }
        return false;
    }
    void drop() {
        if (fd >= 0) ioctl(fd, SNDRV_PCM_IOCTL_DROP);
    }
    void close() {
        if (fd >= 0) ::close(fd);
        fd = -1;
    }
};

// ---------- stan i sterowanie ----------
static volatile sig_atomic_t g_quit = 0;
static bool g_enabled = false;
static bool g_running = false;
static long g_frames = 0, g_xruns = 0;
static char g_note[160] = "czeka na rozmowę";

static void loadEnabled() {
    FILE *f = fopen(cfg.stateFile, "r");
    if (!f) return;
    int c = fgetc(f);
    fclose(f);
    g_enabled = c == '1';
}
static void saveEnabled() {
    FILE *f = fopen(cfg.stateFile, "w");
    if (!f) return;
    fputc(g_enabled ? '1' : '0', f);
    fclose(f);
}

static int g_server = -1;
static bool serverOpen() {
    g_server = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (g_server < 0) return false;
    int one = 1;
    setsockopt(g_server, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(cfg.port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // tylko z samego radia
    if (bind(g_server, (sockaddr *)&a, sizeof a) < 0 || listen(g_server, 4) < 0) {
        LOGW("sterowanie: port %d: %s", cfg.port, strerror(errno));
        return false;
    }
    return true;
}
/** obsługuje czekające polecenia (bez blokowania) */
static void serverPoll() {
    for (;;) {
        int c = accept4(g_server, nullptr, nullptr, SOCK_CLOEXEC);
        if (c < 0) return;
        timeval tv = {0, 200000};
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        char cmd[32] = {0};
        ssize_t n = recv(c, cmd, sizeof cmd - 1, 0);
        if (n > 0) {
            if (!strncmp(cmd, "on", 2)) { g_enabled = true; saveEnabled(); LOGI("polecenie: włącz"); }
            else if (!strncmp(cmd, "off", 3)) { g_enabled = false; saveEnabled(); LOGI("polecenie: wyłącz"); }
        }
        char out[320];
        int len = snprintf(out, sizeof out, "enabled=%d running=%d aec=%s frames=%ld xruns=%ld note=%s\n",
                           g_enabled ? 1 : 0, g_running ? 1 : 0, aecName(), g_frames, g_xruns, g_note);
        send(c, out, len, MSG_NOSIGNAL);
        close(c);
    }
}

static bool g_dump = false;
static long g_fixes = 0; // ile razy trzeba było ponownie rozłączyć tor fabryczny
static int g_errors = 0;  // błędy toru w bieżącej rozmowie

static void sleepMs(int ms) {
    timespec t = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&t, nullptr);
}

/** Jedna rozmowa na własnym torze; wraca, gdy rozmowa się skończy, program zostanie wyłączony albo coś zawiedzie. */
static void runCall(Mixer &mixer) {
    bool ok = true;
    // najpierw nasz tor, potem rozłączenie fabrycznego – żeby przerwa w dźwięku była jak najkrótsza
    ok &= mixer.set(OUR_MIC, 1) && mixer.set(OUR_BT_IN, 1) && mixer.set(OUR_SPK, 1) && mixer.set(OUR_BT_OUT, 1);
    Pcm mic, bt, spk, btOut;
    ok = ok && mic.open(cfg.card, cfg.pcmMic, true, cfg.rate, cfg.period, cfg.periods)
            && bt.open(cfg.card, cfg.pcmBt, true, cfg.rate, cfg.period, cfg.periods)
            && spk.open(cfg.card, cfg.pcmMic, false, cfg.rate, cfg.period, cfg.periods)
            && btOut.open(cfg.card, cfg.pcmBt, false, cfg.rate, cfg.period, cfg.periods);
    Aec *aec = ok ? aecCreate(cfg.rate) : nullptr;
    if (ok && !aec) { LOGW("nie udało się uruchomić usuwania echa"); ok = false; }
    if (ok) {
        mixer.set(FACTORY_DOWN, 0);
        mixer.set(FACTORY_UP, 0);
        mixer.set("ADC1 Volume", 20); // po starcie radia czułość mikrofonu bywa na 6 mimo ustawienia 20
        g_running = true;
        snprintf(g_note, sizeof g_note, "rozmowa na własnym torze");
        LOGI("przejmuję dźwięk rozmowy (%s, %d Hz, okres %d)", aecName(), cfg.rate, cfg.period);
    } else {
        snprintf(g_note, sizeof g_note, "nie udało się przejąć dźwięku – rozmowa na torze fabrycznym");
        LOGW("%s", g_note);
    }
    std::vector<int16_t> far(cfg.period), near(cfg.period);
    const int chunk = cfg.rate / 100; // usuwanie echa pracuje na kawałkach 10 ms
    long gain = 8192;
    long n = 0;
    FILE *dNear = nullptr, *dFar = nullptr, *dOut = nullptr; // --dump: zapis do strojenia (zawiera głos – prywatne)
    if (ok && g_dump) {
        dNear = fopen("/data/local/tmp/echo_near.raw", "ab");
        dFar = fopen("/data/local/tmp/echo_far.raw", "ab");
        dOut = fopen("/data/local/tmp/echo_out.raw", "ab");
    }
    while (ok && !g_quit && g_enabled) {
        if (!mic.xfer(near.data(), cfg.period) || !bt.xfer(far.data(), cfg.period)) { ok = false; break; }
        // koniec rozmowy trzeba zauważyć od razu: radio zaraz potem zamyka porty Bluetooth, a praca na zamykanych
        // portach skończyła się 20.11 restartem układu dźwięku
        if (mixer.get(CALL_FLAG) != 1) break;
        if (n % 10 == 0) { // co ok. 0,2 s: jaka głośność, czy przyszło polecenie, czy radio nie zmieniło toru
            // radio przy każdej zmianie swoich torów (np. dźwięk powiadomienia) potrafi przywrócić fabryczne spięcie
            if (mixer.get(FACTORY_DOWN) != 0) { mixer.set(FACTORY_DOWN, 0); g_fixes++; }
            if (mixer.get(FACTORY_UP) != 0) { mixer.set(FACTORY_UP, 0); g_fixes++; }
            if (mixer.get(OUR_MIC) != 1) mixer.set(OUR_MIC, 1);
            if (mixer.get(OUR_BT_IN) != 1) mixer.set(OUR_BT_IN, 1);
            if (mixer.get(OUR_SPK) != 1) mixer.set(OUR_SPK, 1);
            if (mixer.get(OUR_BT_OUT) != 1) mixer.set(OUR_BT_OUT, 1);
            long g = mixer.get(CALL_VOLUME);
            if (g >= 0) gain = g;
            serverPoll();
        }
        if (gain != 8192)
            for (auto &s : far) s = (int16_t)((long)s * gain / 8192);
        if (dNear) fwrite(near.data(), 2, cfg.period, dNear);
        if (dFar) fwrite(far.data(), 2, cfg.period, dFar);
        for (int i = 0; i + chunk <= cfg.period; i += chunk) {
            aec->farEnd(far.data() + i, chunk);
            aec->nearEnd(near.data() + i, chunk);
        }
        if (dOut) fwrite(near.data(), 2, cfg.period, dOut);
        if (!spk.xfer(far.data(), cfg.period) || !btOut.xfer(near.data(), cfg.period)) { ok = false; break; }
        n++;
        g_frames += cfg.period;
    }
    if (dNear) fclose(dNear);
    if (dFar) fclose(dFar);
    if (dOut) fclose(dOut);
    mic.drop(); bt.drop(); spk.drop(); btOut.drop(); // natychmiast zatrzymaj strumienie
    g_xruns += mic.xruns + bt.xruns + spk.xruns + btOut.xruns;
    bool callOn = mixer.get(CALL_FLAG) == 1;
    // oddanie dźwięku: gdy rozmowa jeszcze trwa, najpierw wraca fabryczne spięcie
    if (g_running && callOn) {
        mixer.set(FACTORY_DOWN, 1);
        mixer.set(FACTORY_UP, 1);
    }
    mic.close(); bt.close(); spk.close(); btOut.close();
    mixer.set(OUR_MIC, 0); mixer.set(OUR_BT_IN, 0); mixer.set(OUR_SPK, 0); mixer.set(OUR_BT_OUT, 0);
    delete aec;
    if (g_running) LOGI("oddaję dźwięk rozmowy (%ld s, przerw w strumieniu: %ld, poprawek toru: %ld)%s", n * cfg.period / cfg.rate, g_xruns, g_fixes, ok ? "" : " – po błędzie");
    g_running = false;
    if (!ok) snprintf(g_note, sizeof g_note, "błąd toru dźwięku – do końca tej rozmowy tor fabryczny");
    else snprintf(g_note, sizeof g_note, "czeka na rozmowę");
    // po błędzie: do 5 ponowień w jednej rozmowie, potem tor fabryczny do jej końca; po wyłączeniu – czekaj
    if (!ok && ++g_errors <= 5) { sleepMs(300); return; }
    while (!g_quit && mixer.get(CALL_FLAG) == 1 && (!ok || !g_enabled)) {
        serverPoll();
        if (ok && g_enabled) break; // włączono z powrotem w trakcie rozmowy
        sleepMs(200);
    }
    if (mixer.get(CALL_FLAG) != 1) g_errors = 0;
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-v")) g_stderr = true;
        else if (!strcmp(argv[i], "--dump")) g_dump = true;
        else if (!strcmp(argv[i], "--period") && i + 1 < argc) cfg.period = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--periods") && i + 1 < argc) cfg.periods = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--rate") && i + 1 < argc) cfg.rate = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--on")) g_enabled = true;
        else { fprintf(stderr, "kokpit-echo [-v] [--on] [--rate 16000] [--period 320] [--periods 4]\n"); return 2; }
    }
    if (cfg.period % (cfg.rate / 100)) { fprintf(stderr, "okres musi być wielokrotnością 10 ms\n"); return 2; }
    signal(SIGINT, [](int) { g_quit = 1; });
    signal(SIGTERM, [](int) { g_quit = 1; });
    signal(SIGPIPE, SIG_IGN);
    if (!g_enabled) loadEnabled();
    Mixer mixer;
    if (!mixer.open(cfg.card)) return 1;
    for (const char *name : {FACTORY_DOWN, FACTORY_UP, CALL_FLAG, CALL_VOLUME, OUR_MIC, OUR_SPK, OUR_BT_IN, OUR_BT_OUT})
        if (!mixer.find(name)) { LOGW("to radio nie ma przełącznika „%s” – kończę", name); return 1; }
    if (!serverOpen()) return 1;
    LOGI("start (%s), usuwanie echa %s", aecName(), g_enabled ? "włączone" : "wyłączone");
    while (!g_quit) {
        serverPoll();
        if (g_enabled && mixer.get(CALL_FLAG) == 1) runCall(mixer);
        else { if (mixer.get(CALL_FLAG) != 1) g_errors = 0; sleepMs(150); }
    }
    LOGI("koniec");
    return 0;
}
