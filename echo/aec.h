// Usuwanie echa za wspólnym interfejsem: wersja „bez zmian” (do prób samego toru dźwięku) albo WebRTC.
#pragma once
#include <stdint.h>

class Aec {
  public:
    virtual ~Aec() {}
    /** 10 ms dźwięku, który zaraz zagra głośnik (głos rozmówcy) */
    virtual void farEnd(const int16_t *samples, int count) = 0;
    /** 10 ms z mikrofonu; wynik (bez echa) wraca w tym samym buforze */
    virtual void nearEnd(int16_t *samples, int count) = 0;
};

/** null = nie udało się uruchomić */
Aec *aecCreate(int rate);
const char *aecName();
