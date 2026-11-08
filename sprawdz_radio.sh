#!/bin/bash
# Zbiera informacje z radia Witstek przez ADB do pliku radio_info.txt
ADB=${ADB:-$(command -v adb || echo ~/Android/sdk/platform-tools/adb)}
OUT="$(dirname "$0")/radio_info.txt"
{
echo "=== adb devices ==="; $ADB devices -l
echo "=== getprop (wybrane) ==="; $ADB shell getprop | grep -iE 'model|brand|version|build|can|mcu|ya3|witstek|fyt|ts10' 
echo "=== pakiety ==="; $ADB shell pm list packages -f
echo "=== launcher domyślny ==="; $ADB shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
echo "=== content providers ==="; $ADB shell dumpsys package providers | grep -iE 'authority|Provider\{' | grep -viE 'com\.google|com\.android\.(providers|externalstorage)' 
echo "=== usługi działające ==="; $ADB shell dumpsys activity services | grep -E 'ServiceRecord' | sort -u
echo "=== system properties CAN/auto ==="; $ADB shell getprop | grep -iE 'fuel|oil|door|car|canbus|speed' 
echo "=== /sys i /dev (can/mcu) ==="; $ADB shell 'ls -la /dev | grep -iE "tty|can|mcu"; ls /sys/class 2>/dev/null'
echo "=== pamięć/CPU ==="; $ADB shell 'cat /proc/meminfo | head -3; top -b -n 1 | head -20'
echo "=== Kokpit F11 ==="; $ADB shell dumpsys package pl.lawinski.radiolauncher | grep -E 'versionName|versionCode'
echo "=== Xiaomi Cam ==="; $ADB shell dumpsys package pl.lawinski.xiaomicam | grep -E 'versionName|versionCode'
} > "$OUT" 2>&1
echo "Zapisano: $OUT"
