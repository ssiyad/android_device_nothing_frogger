#!/usr/bin/env bash
#
# Frogger log grabber. Run from LineageOS recovery (which has a root shell --
# stock recovery only offers sideload and cannot read pstore).
#
# Collects, in order of usefulness for a failed boot:
#
#   /sys/fs/pstore/dmesg-ramoops-*   panic backtrace, written by the panic
#                                    handler and kept across reboots
#   /sys/fs/pstore/console-ramoops-* previous boot's console log
#   /sys/fs/pstore/pmsg-ramoops-*    previous boot's userspace ring buffer
#   nt_log:boot_log/                 Nothing's per-boot archives -- only written
#                                    by Android userspace, so useless if the
#                                    boot never got that far
#   nt_kmsg, rawdump                 Nothing/Qualcomm dump partitions
#
# IMPORTANT: pstore lives in RAM at 0x81f20000 (4 MB: 2 console + 2 pmsg). It
# survives a warm reboot but NOT a power-hold, which is a PMIC-level reset. If
# the device has been forced off since the failure, pstore will be empty and
# there is nothing to collect -- that is why flash-frogger.sh installs to the
# inactive slot, so a failure falls back warm instead of needing a power-hold.
#
# Usage: grab-logs.sh [output-dir]
#
set -uo pipefail

ADB="${ADB:-$HOME/sources/android/platform-tools/adb}"
OUT="${1:-./frogger-logs-$(date +%Y%m%d-%H%M%S)}"

info() { printf '[*] %s\n' "$*"; }
warn() { printf '[!] %s\n' "$*"; }

[ -x "$ADB" ] || { echo "adb not executable: $ADB" >&2; exit 1; }

state=$("$ADB" get-state 2>/dev/null)
[ "$state" = "recovery" ] || warn "device state is '${state:-none}', expected 'recovery'"

mkdir -p "$OUT"
info "collecting into $OUT"

## pstore -- the one that matters -------------------------------------------
n=$("$ADB" shell 'ls /sys/fs/pstore/ 2>/dev/null | wc -l' | tr -d '\r')
if [ "${n:-0}" -gt 0 ]; then
    info "pstore: $n entries"
    "$ADB" pull /sys/fs/pstore/ "$OUT/pstore" >/dev/null 2>&1
    ls -la "$OUT/pstore" 2>/dev/null | sed 's/^/    /'
else
    warn "pstore is EMPTY"
    warn "  either this is the first boot after a power-hold (RAM lost),"
    warn "  or the failure did not reach the kernel panic handler."
fi

## Nothing's per-boot archives ---------------------------------------------
if "$ADB" shell 'mkdir -p /tmp/ntlog && mount -o ro /dev/block/by-name/nt_log /tmp/ntlog' 2>/dev/null; then
    info "nt_log mounted"
    "$ADB" pull /tmp/ntlog/boot_log     "$OUT/nt_boot_log"     >/dev/null 2>&1
    "$ADB" pull /tmp/ntlog/recovery_log "$OUT/nt_recovery_log" >/dev/null 2>&1
    "$ADB" shell 'umount /tmp/ntlog' 2>/dev/null
    # Which kernel each archive belongs to.
    if [ -d "$OUT/nt_boot_log" ]; then
        info "boot archives (kernel version per archive):"
        for f in "$OUT"/nt_boot_log/*.tar.gz; do
            [ -f "$f" ] || continue
            v=$(tar -xzOf "$f" --wildcards '*kernel_log.txt' 2>/dev/null \
                | grep -m1 -oE "Linux version [0-9.]+")
            printf '    %-46s %s\n' "$(basename "$f")" "${v:-unknown}"
        done
    fi
else
    warn "could not mount nt_log"
fi

## Raw dump partitions ------------------------------------------------------
for p in nt_kmsg rawdump; do
    info "$p: sampling"
    "$ADB" shell "dd if=/dev/block/by-name/$p bs=1M count=16 2>/dev/null" > "$OUT/$p.bin" 2>/dev/null
    if [ -s "$OUT/$p.bin" ]; then
        strings "$OUT/$p.bin" > "$OUT/$p.txt" 2>/dev/null
        v=$(grep -m1 -oE "Linux version [0-9.]+" "$OUT/$p.txt" 2>/dev/null)
        pc=$(grep -cE "Kernel panic|Unable to handle kernel|Internal error" "$OUT/$p.txt" 2>/dev/null)
        printf '    %-10s %s  panics=%s\n' "$p" "${v:-no kernel banner}" "${pc:-0}"
    fi
done

## Summary ------------------------------------------------------------------
printf '\n'
info "summary"
if ls "$OUT"/pstore/dmesg-ramoops-* >/dev/null 2>&1; then
    info "  PANIC FOUND -- read $OUT/pstore/dmesg-ramoops-0 first"
    grep -m5 -E "Kernel panic|Unable to handle|PC is at|Call trace" \
        "$OUT"/pstore/dmesg-ramoops-* 2>/dev/null | sed 's/^/    /'
elif ls "$OUT"/pstore/console-ramoops-* >/dev/null 2>&1; then
    info "  no panic record, but previous boot's console log is present"
    info "  tail it: tail -100 $OUT/pstore/console-ramoops-0"
else
    warn "  nothing from the failed boot was recovered"
fi
info "done: $OUT"
