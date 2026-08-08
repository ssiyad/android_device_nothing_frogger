#!/system/bin/sh
#
# DIAGNOSTIC ONLY -- DO NOT INSTALL WHEN GOING ENFORCING.
#
# Removed from the device 2026-08-08. It clobbers Magisk: Magisk patches the
# live policy at boot to add its own domain, and this script then loads a policy
# recompiled from the shipped CIL, which has no Magisk types. The result was
# magiskd and su running as u:object_r:unlabeled:s0. Harmless under permissive,
# but in enforcing it would break root outright and would present as an
# enforcing bug rather than a tooling one.
#
# Kept because the technique is worth having: it strips dontaudit with no build
# and no flash. Install to /data/adb/post-fs-data.d/ for a collection run, then
# remove it again.
#
# Recompile the CURRENT on-disk SELinux policy with dontaudit stripped, then
# load it. Runs from Magisk post-fs-data on every boot.
#
# It recompiles rather than loading a saved binary, and that matters. The first
# version of this script loaded a fixed pol.nodontaudit captured on 2026-08-06.
# After the 2026-08-08 flash it dutifully reloaded that stale snapshot over the
# new policy, silently reverting the genfs labels that build had just shipped --
# the labels looked broken when in fact they were correct on disk.
#
# Recompiling from /system, /vendor, /product and /system_ext means this always
# tracks whatever the ROM ships, and only ever removes dontaudit.
#
# Safe by construction:
#   - the device is permissive, so no policy loaded here can block anything
#   - any failure leaves the policy init already loaded, which is the normal one
#   - deleting this file and rebooting reverts entirely
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
