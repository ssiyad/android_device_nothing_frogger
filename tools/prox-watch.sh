#!/usr/bin/env bash
#
# Watch the proximity path across a call.
#
# The moment that decides this cannot be read by hand -- the screen is dark, the
# phone is at an ear, and what matters is a transition rather than a value. Start
# this before dialling, hang up, stop it with ctrl-c, and read the log.
#
# It separates the two faults that look identical from outside:
#
#   mProximity stays near              the part is latched near and the
#                                      thresholds are the problem
#   mProximity reaches far while       the release arrived and the display
#   mScreenOffBecauseOfProximity=true  server did not act on it
#
# wl counts held proximity wake locks. The dialer takes one only while audio
# routes to the earpiece, so a sample with the screen dark and wl=0 is a
# different fault again -- audio routing, not the sensor.
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
# long. No apostrophes below -- the block is single-quoted all the way to the
# device.
"$ADB" shell '
    while true; do
        pw=$(dumpsys power)
        dp=$(dumpsys display)
        pstate=$(printf %s "$dp" | grep -m1 -oE "Display Power: state=[A-Z_]+")
        prox=$(printf %s "$dp" | grep -m1 -oE "mProximity=[^ ,]*")
        soff=$(printf %s "$dp" | grep -m1 -oE "mScreenOffBecauseOfProximity=[^ ,]*")
        wneg=$(printf %s "$dp" | grep -m1 -oE "mWaitingForNegativeProximity=[^ ,]*")
        ppos=$(printf %s "$pw" | grep -m1 -oE "mProximityPositive=[^ ,]*")
        wl=$(printf %s "$pw" | grep -cE "WAKE_LOCK.*ProximitySensor")
        printf "%s %s %s %s %s %s wl=%s\n" \
            "$(date +%H:%M:%S)" \
            "${pstate:-state=?}" \
            "${prox:-mProximity=?}" \
            "${soff:-mScreenOffBecauseOfProximity=?}" \
            "${wneg:-mWaitingForNegativeProximity=?}" \
            "${ppos:-mProximityPositive=?}" \
            "$wl"
        sleep 1
    done
' | tee "$OUT"
