#!/system/bin/sh
#
# Frogger SELinux denial collector.
#
# Appends each *distinct* "avc: denied" to /data/adb/avc/denials.log and keeps
# the normalised key in seen.keys so dedup survives reboots. Started from
# /data/adb/service.d so it comes back after every boot.
#
# Deliberately stores nothing under /data/local/tmp or /sdcard: /data/adb
# survives app data wipes and is root-only, and the whole point is a set that
# accumulates over days of real use.
#
DIR=/data/adb/avc
LOG=$DIR/denials.log
KEYS=$DIR/seen.keys
PIDF=$DIR/pid

mkdir -p "$DIR"
touch "$LOG" "$KEYS"

# Never run two collectors: the second would duplicate every line, since each
# holds its own in-memory copy of the seen set.
#
# Checking `kill -0` alone is NOT enough. PIDs are reused across reboots, and on
# 2026-08-06 the stale pid file held 2259, which after a reboot belonged to
# /vendor/bin/qms -- so the guard saw a live process, assumed a collector was
# already running, and exited. The collector silently missed two boots, which is
# precisely the data that matters most. Confirm the PID is actually us.
if [ -f "$PIDF" ]; then
    oldpid=$(cat "$PIDF" 2>/dev/null)
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null &&
       tr -d '\0' < "/proc/$oldpid/cmdline" 2>/dev/null | grep -q 'collect\.sh'; then
        exit 0
    fi
fi
echo $$ > "$PIDF"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# logcat can be killed by log rotation or low memory; keep coming back.
while true; do
    /system/bin/logcat -b all -v time 2>/dev/null | /system/bin/awk \
        -v KEYS="$KEYS" -v LOG="$LOG" '
    BEGIN {
        while ((getline k < KEYS) > 0) seen[k] = 1
        close(KEYS)
    }
    /avc: *denied/ {
        line = $0
        perms = ""; sc = ""; tc = ""; cls = ""; nm = ""; pth = ""
        if (match(line, /\{[^}]*\}/))      perms = substr(line, RSTART, RLENGTH)
        if (match(line, /scontext=[^ ]+/)) sc    = substr(line, RSTART, RLENGTH)
        if (match(line, /tcontext=[^ ]+/)) tc    = substr(line, RSTART, RLENGTH)
        if (match(line, /tclass=[^ ]+/))   cls   = substr(line, RSTART, RLENGTH)
        if (match(line, /name="[^"]*"/))   nm    = substr(line, RSTART, RLENGTH)
        if (match(line, /path="[^"]*"/))   pth   = substr(line, RSTART, RLENGTH)

        # Drop MLS categories. Without this every app UID (c186, c220, c244...)
        # looks like a separate denial and the file fills with the same rule
        # repeated once per installed app.
        gsub(/:c[0-9][0-9,c]*/, "", sc)
        gsub(/:c[0-9][0-9,c]*/, "", tc)

        # PIDs in paths do the same thing: /proc/8905/net/raw and
        # /proc/9170/net/tcp are one labelling problem, not two.
        gsub(/\/proc\/[0-9]+\//, "/proc/<pid>/", pth)

        key = perms " " sc " " tc " " cls " " nm " " pth
        if (!(key in seen)) {
            seen[key] = 1
            print key >> KEYS
            fflush(KEYS)
            print line >> LOG
            fflush(LOG)
        }
    }'
    sleep 5
done
