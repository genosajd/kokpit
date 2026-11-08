#!/bin/bash
# Udawane dane auta dla Kokpitu F11 na emulatorze (xc_car).
# Kokpit czyta ramki CAN z logu systemu (tag McuManager), tak jak w radiu Witstek,
# więc wystarczy wpisać do logu linię w tym samym formacie.
#
# Użycie:
#   ./symuluj_can.sh drzwi LP|PP|LT|PT|klapa|wszystkie|zamknij
#   ./symuluj_can.sh bieg P|R|N|D|S|M
#   ./symuluj_can.sh pas on|off          (ostrzeżenie o niezapiętym pasie)
#   ./symuluj_can.sh temp -3.5           (temperatura zewnętrzna, krok 0,5)
#   ./symuluj_can.sh paliwo 45           (litry)
#   ./symuluj_can.sh predkosc 87         (km/h, właściwość can.speed)
#   ./symuluj_can.sh pokaz               (krótki pokaz wszystkiego po kolei)

ADB=${ADB:-$(command -v adb || echo ~/Android/sdk/platform-tools/adb)}
DEV=${DEV:-emulator-5554}
STATE=${XDG_RUNTIME_DIR:-/tmp}/kokpit_can_state
A="$ADB -s $DEV"

# stan: drzwi bieg pas temp paliwo
[ -f "$STATE" ] && . "$STATE"
DOORS=${DOORS:-0}; GEAR=${GEAR:-0}; BELT=${BELT:-0}; TEMP=${TEMP:-13}; FUEL=${FUEL:-26}

save() { printf 'DOORS=%s\nGEAR=%s\nBELT=%s\nTEMP=%s\nFUEL=%s\n' $DOORS $GEAR $BELT $TEMP $FUEL > "$STATE"; }

send() {
    local frame
    frame=$(printf '09 12 01 %02x 00 01 %02x %02x %02x %02x 00' $GEAR $DOORS $FUEL $BELT $TEMP)
    $A shell log -p d -t McuManager "doMcuDataYA82:$frame"
    echo "ramka: $frame"
    save
}

case "$1" in
    drzwi)
        case "$2" in
            LP) DOORS=$((DOORS | 0x80)) ;; PP) DOORS=$((DOORS | 0x40)) ;;
            LT) DOORS=$((DOORS | 0x20)) ;; PT) DOORS=$((DOORS | 0x10)) ;;
            klapa) DOORS=$((DOORS | 0x08)) ;; wszystkie) DOORS=$((0xF8)) ;;
            zamknij) DOORS=0 ;; *) echo "drzwi LP|PP|LT|PT|klapa|wszystkie|zamknij"; exit 1 ;;
        esac; send ;;
    bieg)
        case "$2" in P) GEAR=0 ;; R) GEAR=1 ;; N) GEAR=2 ;; D) GEAR=4 ;; S) GEAR=5 ;; M) GEAR=6 ;;
            *) echo "bieg P|R|N|D|S|M"; exit 1 ;; esac; send ;;
    pas) [ "$2" = on ] && BELT=$((0x40)) || BELT=0; send ;;
    temp)
        t=$(awk -v t="$2" 'BEGIN{ v=int((t<0?-t:t)*2+0.5); if (t<0) v+=128; print v }'); TEMP=$t; send ;;
    paliwo) FUEL=$2; send ;;
    predkosc) $A root >/dev/null 2>&1; sleep 1; $A shell setprop can.speed "$2" && echo "can.speed=$2" ;;
    pokaz)
        "$0" paliwo 48; "$0" temp 6.5; "$0" bieg P; sleep 2
        "$0" drzwi LP; sleep 3; "$0" drzwi PT; sleep 3; "$0" drzwi klapa; sleep 3; "$0" drzwi zamknij; sleep 2
        "$0" pas on; "$0" bieg D; for v in 10 30 50 70 50 20 0; do "$0" predkosc $v; sleep 1; done
        "$0" pas off; "$0" bieg R; sleep 3; "$0" bieg P ;;
    *) sed -n '2,15p' "$0" ;;
esac
