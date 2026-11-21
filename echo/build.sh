#!/bin/bash
# Buduje kokpit-echo dla radia (arm64).
#   ./build.sh         – z biblioteką WebRTC (wymaga third_party/inst, patrz third_party/README)
#   ./build.sh none    – bez usuwania echa (dźwięk bez zmian, do prób samego toru)
set -euo pipefail
cd "$(dirname "$0")"
NDK=${ANDROID_NDK:-$(ls -d ~/Android/sdk/ndk/* | tail -1)}
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang++"
mkdir -p out
FLAGS="-O2 -Wall -Wextra -std=c++17 -fPIE -pie -static-libstdc++"
if [ "${1:-webrtc}" = none ]; then
  "$CXX" $FLAGS kokpit-echo.cpp aec_none.cpp -llog -o out/kokpit-echo
else
  W=third_party/inst; B=third_party/build-wap
  "$CXX" $FLAGS -Wno-unused-parameter -DWEBRTC_LIBRARY_IMPL -DWEBRTC_POSIX -DWEBRTC_ANDROID -DWEBRTC_LINUX \
    -I$W/include/webrtc-audio-processing-2 -I$(ls -d third_party/wap/subprojects/abseil-cpp-*/ | head -1) \
    kokpit-echo.cpp aec_webrtc.cpp \
    -Wl,--start-group $(find $B -name '*.a') -Wl,--end-group -llog -pthread -o out/kokpit-echo
fi
ls -la out/kokpit-echo
