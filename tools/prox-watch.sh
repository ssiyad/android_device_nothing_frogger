#!/usr/bin/env bash
#
# Watch the proximity path across a call.
#
# The moment that decides this cannot be read by hand -- the screen is dark, the
# phone is at an ear, and what matters is a transition rather than a value. Start
# this before dialling, hang up, stop it with ctrl-c, and read the log.
#
# It separates the faults that look identical from outside:
#
#   prox stays Positive                the part is latched near and the
#                                      thresholds are the problem
#   prox reaches Negative while        the release arrived and the display
#   soff stays true                    server did not act on it
#   wl=0 with the screen dark          the dialer never took the lock, so this
#                                      is audio routing and not the sensor
#
# useProx is the display server being asked to use proximity at all, and is what
# distinguishes "the dialer never asked" from "it asked and got nothing".
#
# The thresholds themselves are not readable: SEE publishes a two-state value.
# docs/reference/proximity.md has them, and what changing them costs.
#
# Usage: prox-watch.sh [output-file]
#
set -uo pipefail

ADB="${ADB:-$HOME/sources/android/platform-tools/adb}"
OUT="${1:-./prox-watch-$(date +%Y%m%d-%H%M%S).log}"

[ -x "$ADB" ] || { echo "adb not executable: $ADB" >&2; exit 1; }
state=$("$ADB" get-state 2>/dev/null)
[ "$state" = "device" ] || {
    echo "device state is '${state:-none}', expected 'device'" >&2; exit 1
}

echo "sampling into $OUT -- place the call, then stop with ctrl-c"

# One adb shell for the whole run, with the loop and the greps device-side: a
# sample then costs one dumpsys pair rather than a round trip per field, which
# matters because both dumps are large and the interesting window is seconds
# long.
#
# The dumps go to files rather than into variables. `dumpsys power` carries the
# whole wake lock log and does not fit in an argument list, so feeding it to
# printf or grep as "$var" fails with E2BIG and every field from it silently
# reads as unmatched.
#
# No apostrophes below -- the block is single-quoted all the way to the device.
"$ADB" shell '
    # Swept at start as well as trapped at exit: the trap runs in this shell,
    # and killing the adb process on the host does not always reach it, so a
    # run that ends by having its connection torn out leaves both files behind.
    rm -f /data/local/tmp/prox-watch.*
    d=/data/local/tmp/prox-watch.$$
    trap "rm -f $d.power $d.display" EXIT INT TERM
    g() { grep -m1 -oE "$1" "$2" || echo "${3}=?"; }
    while true; do
        dumpsys power > "$d.power"
        dumpsys display > "$d.display"
        printf "%s %s %s %s %s %s %s %s wl=%s\n" \
            "$(date +%H:%M:%S)" \
            "$(g "mWakefulness=[A-Za-z]+" "$d.power" wake)" \
            "$(g "policy=[A-Z_]+" "$d.display" policy)" \
            "$(g "useProximitySensor=[a-z]+" "$d.display" useProx)" \
            "$(g "mProximitySensorEnabled=[a-z]+" "$d.display" sensorEnabled)" \
            "$(g "mProximity=[A-Za-z]+" "$d.display" prox)" \
            "$(g "mScreenOffBecauseOfProximity=[a-z]+" "$d.display" soff)" \
            "$(g "mWaitingForNegativeProximity=[a-z]+" "$d.display" waitNeg)" \
            "$(grep -cE "WAKE_LOCK.*ProximitySensor" "$d.power")"
        sleep 1
    done
' | tee "$OUT"
