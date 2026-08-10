# Collect SELinux denials

Denials feed every other SELinux task. `dontaudit` rules suppress logging even
in permissive mode, so any collection taken against the shipped policy
understates the set by an unknown margin.

How a denial is then classified is in [selinux.md](../reference/selinux.md).

## Strip `dontaudit`

The CIL sources ship on the device, so policy can be recompiled and reloaded
without a build. The build system has no hook for this.

```sh
# Mapping version comes from the vendor partition.
cat /vendor/etc/selinux/plat_sepolicy_vers.txt

cd /data/adb/avc/policy/cil
/system/bin/secilc plat_sepolicy.cil -m -M true -G -N -D -c 30 \
    plat_map.cil -o /data/adb/avc/policy/pol.nodontaudit -f /sys/fs/selinux/null \
    system_ext_sepolicy.cil se_map.cil product_sepolicy.cil prod_map.cil \
    plat_pub_versioned.cil vendor_sepolicy.cil plat_sepolicy_genfs_202504.cil

SZ=$(stat -c %s pol.nodontaudit)
dd if=pol.nodontaudit of=/sys/fs/selinux/load bs=$SZ count=1
```

- `ro.board.api_level` is **not** the mapping version. Guessing it fails with
  `Failed to resolve expandtypeattribute statement`.
- `/sys/fs/selinux/load` requires the whole policy in a single `write()`. `cat`
  chunks it and fails with `EINVAL`.
- The reload is not persistent.

`tools/00-avc-policy.sh` automates this from Magisk `post-fs-data`. Install it
for a collection run and remove it afterwards: the recompiled policy carries no
Magisk types, so leaving it in place wipes the domain Magisk patches in at boot
and leaves `magiskd` and `su` as `u:object_r:unlabeled:s0`.

## Collector

`tools/avc-collect.sh` gathers denials continuously, because logcat rotates in
minutes and one-shot captures miss whatever was not running at the time.

| Path | Contents |
|---|---|
| `/data/adb/avc/collect.sh` | the collector, started from `/data/adb/service.d` |
| `/data/adb/avc/denials.log` | unique full lines |
| `/data/adb/avc/seen.keys` | normalised keys; dedup survives reboots |
| `/data/adb/avc/archive/` | logs from earlier builds |

It normalises two things before deduplicating, without which the log fills with
one entry per app and per process:

- **MLS categories** — `s0:c186,c256` and `s0:c220,c256` are one rule
- **PIDs in paths** — `/proc/8905/net/raw` and `/proc/9170/net/tcp` are one
  labelling problem

Property-file denials are counted and dropped. They are recognised by the
context file the line names, not by the type name — QTI ships property types
that do not end in `_prop` (`vendor_confqmaa`, `vendor_wifi_version`), and a
suffix test lets those through into the log looking like real findings.

## Resetting between builds

Dedup makes the log cumulative across builds, so a denial fixed three builds ago
still sits in it and a fresh reading has no way to tell. Copy `denials.log` and
`seen.keys` into `archive/` under the build they came from, truncate both, and
restart the collector whenever a policy change ships. Re-observing a denial
against the current build is the only evidence that it is still outstanding.

## Coverage

- Collection must span a boot. Property enumeration and every init-time labelling
  gap happen during boot, and a collection starting afterwards misses them.
- Exercise every subsystem before trusting a count. Permissive only logs code
  paths that execute.
- Keep `adb` use in mind while reading the result. A shell session listing
  `/vendor` or running `dumpsys` writes a hundred denials that describe the
  investigation and nothing else.
