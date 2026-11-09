#!/bin/sh
# Model mowy do hasła „OK BMW” (Vosk, polski mały, licencja Apache 2.0) – wbudowywany w APK.
set -e
cd "$(dirname "$0")"
curl -L -o launcher/app/src/main/assets/vosk-pl.zip https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip
