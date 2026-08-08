#!/system/bin/sh
#
# DIAGNOSTIC ONLY -- DO NOT INSTALL WHEN GOING ENFORCING.
#
# Recompiles the current on-disk SELinux policy with dontaudit stripped and
# loads it, from Magisk post-fs-data on every boot. Install to
# /data/adb/post-fs-data.d/ for a collection run, then remove it.
#
# Magisk patches the live policy at boot to add its own domain. The policy
# recompiled here comes from the shipped CIL and has no Magisk types, so loading
# it leaves magiskd and su as u:object_r:unlabeled:s0.
#
# Recompiling from /system, /vendor, /product and /system_ext rather than
# loading a saved binary keeps this tracking whatever the ROM ships; a stale
# snapshot would silently revert genfs labels the build shipped.
#
# Any failure leaves the policy init already loaded. Deleting this file and
# rebooting reverts entirely.
#
LOG=/data/adb/avc/policy/load.log
OUT=/data/adb/avc/policy/pol.nodontaudit
mkdir -p /data/adb/avc/policy

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

# Mapping version comes from the vendor partition. NOT from ro.board.api_level:
# that reads 34 while the real value is 202504, and the wrong one fails with
# "Failed to resolve expandtypeattribute statement".
VERS=$(cat /vendor/etc/selinux/plat_sepolicy_vers.txt 2>/dev/null)
GENFS=$(cat /vendor/etc/selinux/genfs_labels_version.txt 2>/dev/null)
[ -n "$VERS" ] || { log "no plat_sepolicy_vers.txt; leaving policy alone"; exit 0; }

PLAT=/system/etc/selinux/plat_sepolicy.cil
[ -f "$PLAT" ] || { log "no $PLAT; leaving policy alone"; exit 0; }

# Argument order follows system/core/init/selinux.cpp. -D is the only addition.
set -- "$PLAT" -m -M true -G -N -D -c 30 \
    "/system/etc/selinux/mapping/${VERS}.cil" -o "$OUT" -f /sys/fs/selinux/null

for f in \
    "/system/etc/selinux/mapping/${VERS}.compat.cil" \
    /system_ext/etc/selinux/system_ext_sepolicy.cil \
    "/system_ext/etc/selinux/mapping/${VERS}.cil" \
    "/system_ext/etc/selinux/mapping/${VERS}.compat.cil" \
    /product/etc/selinux/product_sepolicy.cil \
    "/product/etc/selinux/mapping/${VERS}.cil" \
    /vendor/etc/selinux/plat_pub_versioned.cil \
    /vendor/etc/selinux/vendor_sepolicy.cil \
    "/system/etc/selinux/plat_sepolicy_genfs_${GENFS}.cil" ; do
    [ -f "$f" ] && set -- "$@" "$f"
done

if ! /system/bin/secilc "$@" >/dev/null 2>&1; then
    log "secilc failed; leaving the policy init loaded"
    exit 0
fi

# /sys/fs/selinux/load needs the whole policy in ONE write(). cat chunks it and
# the kernel returns EINVAL, so use dd with bs set to the full file size.
SZ=$(stat -c %s "$OUT" 2>/dev/null)
[ -n "$SZ" ] && [ "$SZ" -gt 0 ] || { log "compiled policy is empty"; exit 0; }

if dd if="$OUT" of=/sys/fs/selinux/load bs="$SZ" count=1 2>/dev/null; then
    log "loaded recompiled dontaudit-stripped policy ($SZ bytes, vers=$VERS genfs=$GENFS)"
else
    log "load failed; normal policy still active"
fi
