# Collect SELinux denials

Denials feed every other SELinux task. `dontaudit` rules suppress logging even in
permissive mode, so any collection taken against the shipped policy understates
the set by an unknown margin.

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
| `/data/adb/avc/collect.sh` | the collector |
| `/data/adb/avc/denials.log` | unique full lines |
| `/data/adb/avc/seen.keys` | normalised keys; dedup survives reboots |

It normalises two things before deduplicating, without which the log fills with
one entry per app and per process:

- **MLS categories** — `s0:c186,c256` and `s0:c220,c256` are one rule
- **PIDs in paths** — `/proc/8905/net/raw` and `/proc/9170/net/tcp` are one
  labelling problem

## Coverage

- Collection must span a boot. The property enumeration below happens during
  boot, and a collection starting afterwards misses most of the set.
- Exercise every subsystem before trusting a count. Permissive only logs code
  paths that execute.

## Excluded from the log

**Property enumeration.** `getattr`/`map`/`open`/`read` on
`/dev/__properties__/u:object_r:*_prop:s0` accounts for the large majority of any
collection and must not be given an allow rule. bionic opens property context
files by two paths and they differ deliberately
(`bionic/libc/system_properties/`): `foreach()` probes with `access(R_OK)` and is
covered by `dontaudit`, while `GetPropAreaForName()` opens directly and is
documented as intending to generate an audit per non-permitted access. The denial
is the designed behaviour; the read returns nothing and the process continues.

`get_prop(domain, property_type)` from `system/sepolicy/public/te_macros` expands
to `allow $1 $2:file { getattr open read map }`, granting a domain read on every
property on the device. Filtering, not policy, is the answer, and the collector
already does it.

**Root artefacts.** Denials naming `trawcon="u:r:magisk:s0"` or
`/debug_ramdisk/.magisk/` paths come from root and must not be written into
policy.

**Investigation artefacts.** `scontext=u:r:shell:s0` denials come from adb, not
from the device doing anything.

## Reading a surprising `scontext`

A process appearing in an unexpected domain is more often a domain-transition bug
than tooling interference. `seinfo` comes from certificate matching in
`plat_mac_permissions.xml`; a certificate mismatch leaves a whole subsystem in
the launching domain and its denials attributed there.
