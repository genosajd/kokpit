// Usuwanie echa biblioteką WebRTC (webrtc-audio-processing 2.1): echo, filtr dolnozaporowy, odszumianie.
#include "aec.h"
#include <modules/audio_processing/include/audio_processing.h>

namespace {
class WebRtc : public Aec {
  public:
    explicit WebRtc(int rate) : stream(rate, 1) {
        apm = webrtc::AudioProcessingBuilder().Create();
        if (!apm) return;
        webrtc::AudioProcessing::Config c;
        c.echo_canceller.enabled = true;
        c.echo_canceller.mobile_mode = false;
        c.high_pass_filter.enabled = true;
        c.noise_suppression.enabled = true;
        c.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
        apm->ApplyConfig(c);
    }
    bool ok() const { return apm != nullptr; }
    void farEnd(const int16_t *s, int) override { apm->ProcessReverseStream(s, stream, stream, scratch); }
    void nearEnd(int16_t *s, int) override {
        apm->set_stream_delay_ms(100); // wskazówka; biblioteka sama szuka właściwego opóźnienia
        apm->ProcessStream(s, stream, stream, s);
    }

  private:
    rtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig stream;
    int16_t scratch[480];
};
} // namespace

Aec *aecCreate(int rate) {
    WebRtc *w = new WebRtc(rate);
    if (!w->ok()) { delete w; return nullptr; }
    return w;
}
const char *aecName() { return "webrtc"; }
