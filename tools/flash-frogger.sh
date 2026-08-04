#!/usr/bin/env bash
#
# Frogger flasher -- installs a full image set to the INACTIVE slot.
#
# Modelled on spike0en/nothing_flasher (frogger branch). The structure matters
# more than the individual commands:
#
#   * everything runs in fastbootd, not the bootloader
#   * everything targets the inactive slot, with --set-active only at the end
#   * logical partitions are deleted and recreated, not merely flashed
#
# The point of the inactive slot is that the currently working install stays
# bootable throughout. If the new slot panics, the bootloader exhausts its
# retries and falls back on its own -- a warm path, which means pstore survives
# and the panic log can actually be read afterwards. That is the whole reason
# this script exists rather than flashing the active slot.
#
# Usage:
#   flash-frogger.sh <image-dir> [extra-dir]
#
#   <image-dir>  images to flash (e.g. a payload.bin extraction)
#   [extra-dir]  optional second dir searched for anything missing from the
#                first, e.g. super_empty.img which payload extraction lacks
#
# Env:
#   FASTBOOT=/path/to/fastboot   default: ~/sources/android/platform-tools/fastboot
#   WIPE_DATA=1                  erase userdata/metadata (needed stock -> custom)
#   ASSUME_YES=1                 skip confirmation prompts
#   ACTIVE_SLOT=a|b              skip slot detection (getvar often hangs)
#
set -uo pipefail

FASTBOOT="${FASTBOOT:-$HOME/sources/android/platform-tools/fastboot}"
IMG_DIR="${1:-}"
EXTRA_DIR="${2:-}"
WIPE_DATA="${WIPE_DATA:-0}"
ASSUME_YES="${ASSUME_YES:-0}"

# Partition groups, matching the reference flasher.
BOOT_PARTITIONS="boot dtbo init_boot recovery vendor_boot"
VBMETA_PARTITIONS="vbmeta vbmeta_system vbmeta_vendor"
DLKM_PARTITIONS="system_dlkm vendor_dlkm"
LOGICAL_PARTITIONS="odm product system system_ext vendor"

# Firmware is deliberately NOT flashed by default. Our vendor blobs are
# extracted from the same stock build the device already runs, so reflashing
# them changes nothing and costs 23 more transfers over a link that is not
# reliable. Set FLASH_FIRMWARE=1 if restoring a different build.
FLASH_FIRMWARE="${FLASH_FIRMWARE:-0}"
FIRMWARE_PARTITIONS="abl aop aop_config bluetooth cpucp cpucp_dtb devcfg dsp \
featenabler hyp imagefv keymaster modem multiimgoem pvmfw qupfw shrm tz uefi \
uefisecapp xbl xbl_config xbl_ramdump"

die()  { printf '\n[!] %s\n' "$*" >&2; exit 1; }
info() { printf '[*] %s\n' "$*"; }
ok()   { printf '    %-24s OK\n' "$1"; }
bad()  { printf '    %-24s FAILED\n' "$1"; }

confirm() {
    [ "$ASSUME_YES" = "1" ] && return 0
    read -rp "$1 (y/N) " r
    case "$r" in [yY]*) return 0 ;; *) return 1 ;; esac
}

# Locate an image in IMG_DIR, falling back to EXTRA_DIR.
find_img() {
    local n="$1"
    [ -f "$IMG_DIR/$n" ] && { printf '%s\n' "$IMG_DIR/$n"; return 0; }
    [ -n "$EXTRA_DIR" ] && [ -f "$EXTRA_DIR/$n" ] && { printf '%s\n' "$EXTRA_DIR/$n"; return 0; }
    return 1
}

# Every fastboot call goes through this. Without a timeout, fastboot blocks
# forever on "< waiting for any device >" whenever the link drops, and this
# link drops constantly.
FB_TIMEOUT="${FB_TIMEOUT:-90}"
fb() { timeout "$FB_TIMEOUT" "$FASTBOOT" "$@"; }

# getvar on this bootloader is unreliable in every form: targeted queries answer
# 'GetVar Variable Not found', and `getvar all` intermittently hangs outright
# even while `fastboot devices` still responds. Detect the mode from the devices
# listing instead, which prints "fastbootd" vs "fastboot" and never wedges.
is_fastbootd() { fb devices 2>/dev/null | grep -q "fastbootd"; }

# This bootloader's USB stack goes stale after an idle period or a failed
# transfer, and stays wedged until the device re-enumerates. Symptoms are
# 'Invalid argument size' or 'unknown command' -- the SAME command returning
# different errors across runs, which is a corrupted transport rather than a
# device fault. Retrying the bare command does not help; re-enumerating does.
reenumerate() {
    if is_fastbootd; then
        fb reboot fastboot >/dev/null 2>&1
    else
        fb reboot bootloader >/dev/null 2>&1
    fi
    sleep 8
}

# fastboot_retry <human-label> <args...>
fastboot_retry() {
    local label="$1"; shift
    local i out
    for i in 1 2 3 4 5; do
        out=$(fb "$@" 2>&1)
        if printf '%s' "$out" | grep -q "Finished\|OKAY"; then
            ok "$label"
            # Never swallow warnings. "does not support slots" in particular
            # means --slot= was IGNORED and the write went to the current slot,
            # which silently defeats the whole point of this script.
            printf '%s' "$out" | grep -i "warning" | sed 's/^/        !! /'
            return 0
        fi
        [ "$i" -lt 5 ] && reenumerate
    done
    bad "$label"
    printf '%s\n' "$out" | sed 's/^/        /'
    return 1
}

##----------------------------------------------------------------------------

[ -n "$IMG_DIR" ] || die "usage: $(basename "$0") <image-dir> [extra-dir]"
[ -d "$IMG_DIR" ] || die "no such directory: $IMG_DIR"
[ -x "$FASTBOOT" ] || die "fastboot not executable: $FASTBOOT"

info "fastboot: $FASTBOOT ($("$FASTBOOT" --version | head -1 | awk '{print $3}'))"
info "images:   $IMG_DIR"
[ -n "$EXTRA_DIR" ] && info "extra:    $EXTRA_DIR"

# The link drops in and out, so a single check is not evidence of absence.
wait_for_device() {
    local i
    for i in $(seq 1 12); do
        fb devices 2>/dev/null | grep -q . && return 0
        [ "$i" = "1" ] && info "waiting for device..."
        sleep 5
    done
    return 1
}
wait_for_device || die "no device in fastboot mode after 60s"

# Slot detection. getvar all is the only query that lists current-slot, but it
# hangs often enough that ACTIVE_SLOT can be supplied instead:
#   ACTIVE_SLOT=a ./flash-frogger.sh ...
# Read it from a booted system with: adb shell getprop ro.boot.slot_suffix
if [ -z "${ACTIVE_SLOT:-}" ]; then
    ACTIVE_SLOT=$(fb getvar all 2>&1 | grep -m1 "current-slot" | rev | cut -c1)
fi
case "$ACTIVE_SLOT" in
    a) INACTIVE_SLOT=b ;;
    b) INACTIVE_SLOT=a ;;
    *) die "could not determine active slot (got '$ACTIVE_SLOT').
    getvar is likely wedged -- power-cycle the phone, or pass it explicitly:
      ACTIVE_SLOT=a $(basename "$0") $*" ;;
esac
info "active slot: $ACTIVE_SLOT   -> flashing to: $INACTIVE_SLOT"

# Verify every image exists before writing anything.
MISSING=""
for p in $BOOT_PARTITIONS $VBMETA_PARTITIONS $DLKM_PARTITIONS $LOGICAL_PARTITIONS; do
    find_img "$p.img" >/dev/null || MISSING="$MISSING $p.img"
done
[ "$FLASH_FIRMWARE" = "1" ] && for p in $FIRMWARE_PARTITIONS; do
    find_img "$p.img" >/dev/null || MISSING="$MISSING $p.img"
done
[ -n "$MISSING" ] && die "missing images:$MISSING"
info "all images present"

confirm "Flash to slot $INACTIVE_SLOT?" || die "aborted"

if ! is_fastbootd; then
    info "rebooting to fastbootd"
    fastboot_retry "reboot fastbootd" reboot fastboot || die "cannot reach fastbootd"
    sleep 12
    is_fastbootd || die "not in fastbootd after reboot"
fi

# --slot= only works where the current mode advertises has-slot for that
# partition. The bootloader advertises it for almost nothing; fastbootd knows
# the full table. If it is missing here, --slot= is silently ignored and every
# write lands on the ACTIVE slot -- the opposite of what this script promises.
SLOTTED=$(fb getvar all 2>&1 | grep -c "has-slot:.*:yes")
info "partitions advertising has-slot in this mode: $SLOTTED"
if [ "${FORCE_UNSLOTTED:-0}" != "1" ] && [ "${SLOTTED:-0}" -lt 10 ]; then
    die "only $SLOTTED slotted partitions visible -- this is the bootloader, not
    fastbootd, whatever 'fastboot devices' claims. --slot= would be silently
    ignored and every write would land on the ACTIVE slot ($ACTIVE_SLOT),
    corrupting the running system. Refusing to continue.

    Verify with:  fastboot getvar all 2>&1 | grep -c has-slot
    fastbootd reports ~40; the bootloader reports 3.

    Override only if you truly mean to write the active slot: FORCE_UNSLOTTED=1"
fi

# create-logical-partition is the honest test: fastbootd implements it, the
# bootloader answers 'unknown command'. Do this before writing anything.
if ! fb getvar all 2>&1 | grep -q "is-userspace:yes"; then
    [ "${FORCE_UNSLOTTED:-0}" = "1" ] || die "is-userspace is not yes -- not in fastbootd. Refusing to continue."
fi

if [ "$WIPE_DATA" = "1" ]; then
    info "erasing userdata + metadata"
    fastboot_retry "userdata" erase userdata
    fastboot_retry "metadata" erase metadata
fi

info "boot partitions -> slot $INACTIVE_SLOT"
for p in $BOOT_PARTITIONS; do
    fastboot_retry "$p" flash "$p" "$(find_img "$p.img")" --slot="$INACTIVE_SLOT"
done

info "vbmeta -> slot $INACTIVE_SLOT"
for p in $VBMETA_PARTITIONS; do
    fastboot_retry "$p" flash "$p" "$(find_img "$p.img")" --slot="$INACTIVE_SLOT"
done

if [ "$FLASH_FIRMWARE" = "1" ]; then
    info "firmware -> slot $INACTIVE_SLOT"
    for p in $FIRMWARE_PARTITIONS; do
        fastboot_retry "$p" flash "$p" "$(find_img "$p.img")" --slot="$INACTIVE_SLOT"
    done
fi

info "dlkm -> slot $INACTIVE_SLOT (delete, recreate, flash)"
for p in $DLKM_PARTITIONS; do
    fb delete-logical-partition "${p}_${INACTIVE_SLOT}" >/dev/null 2>&1
    fastboot_retry "${p}_${INACTIVE_SLOT} create" create-logical-partition "${p}_${INACTIVE_SLOT}" 1
    fastboot_retry "${p}_${INACTIVE_SLOT}" flash "${p}_${INACTIVE_SLOT}" "$(find_img "$p.img")"
done

info "logical partitions"
if SUPER_EMPTY=$(find_img super_empty.img); then
    if ! fastboot_retry "wipe-super" wipe-super "$SUPER_EMPTY"; then
        info "wipe-super failed, falling back to delete/create"
        SUPER_EMPTY=""
    fi
else
    SUPER_EMPTY=""
fi
if [ -z "$SUPER_EMPTY" ]; then
    for p in $LOGICAL_PARTITIONS; do
        for s in a b; do
            fb delete-logical-partition "${p}_${s}-cow" >/dev/null 2>&1
            fb delete-logical-partition "${p}_${s}"     >/dev/null 2>&1
            fastboot_retry "${p}_${s} create" create-logical-partition "${p}_${s}" 1
        done
    done
fi
for p in $LOGICAL_PARTITIONS; do
    fastboot_retry "$p" flash "$p" "$(find_img "$p.img")" --slot="$INACTIVE_SLOT"
done

info "switching active slot to $INACTIVE_SLOT"
fastboot_retry "set-active $INACTIVE_SLOT" --set-active="$INACTIVE_SLOT" \
    || die "could not switch slot -- device still boots slot $ACTIVE_SLOT, which is intact"

printf '\n'
info "done. slot $INACTIVE_SLOT is now active."
info ""
info "What is NOT preserved: super is a single shared pool on virtual A/B, so"
info "the logical partitions (system, vendor, product, odm, system_ext) are now"
info "this image set for BOTH slots. Slot $ACTIVE_SLOT keeps only its own"
info "boot-class images -- it does not have a userspace of its own to fall back"
info "to. Switching slots alone will not give you a working phone."
info ""
info "If it fails to boot, do NOT hold Power -- that is a PMIC reset and it"
info "wipes pstore, which is the only place the panic log lives."
confirm "Reboot to system now?" && fb reboot
