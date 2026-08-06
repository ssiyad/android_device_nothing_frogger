#!/system/bin/sh
#
# Re-load the dontaudit-stripped SELinux policy after every boot.
#
# Without this the experiment quietly ends at the first reboot: init loads the
# normal policy, 1358 dontaudit rules come back, and collection reverts to the
# suppressed set with nothing to indicate it happened.
#
# Safe by construction:
#   - the device is permissive, so no policy here can block anything
#   - a failed load leaves the policy init already set, which is the normal one
#   - deleting this file and rebooting reverts entirely
#
# The policy MUST be written in a single write(). /sys/fs/selinux/load rejects
# chunked writes with EINVAL, which is what `cat` does -- hence dd with bs=size.
#
POL=/data/adb/avc/policy/pol.nodontaudit
LOG=/data/adb/avc/policy/load.log

[ -f "$POL" ] || exit 0

SZ=$(stat -c %s "$POL" 2>/dev/null)
[ -n "$SZ" ] && [ "$SZ" -gt 0 ] || exit 0

if dd if="$POL" of=/sys/fs/selinux/load bs="$SZ" count=1 2>/dev/null; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') loaded dontaudit-stripped policy ($SZ bytes)" >> "$LOG"
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') FAILED to load $POL -- normal policy still active" >> "$LOG"
fi
