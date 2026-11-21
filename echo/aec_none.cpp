// Wersja bez usuwania echa: dźwięk przechodzi bez zmian. Służy do sprawdzenia samego przenoszenia dźwięku w radiu.
#include "aec.h"

namespace {
class Passthrough : public Aec {
  public:
    void farEnd(const int16_t *, int) override {}
    void nearEnd(int16_t *, int) override {}
};
} // namespace

Aec *aecCreate(int) { return new Passthrough(); }
const char *aecName() { return "bez-zmian"; }
