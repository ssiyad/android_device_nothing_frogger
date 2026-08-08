# SELinux

Frogger runs **permissive** and is not close to enforcing. This is the working
record: how denials are collected, what is trustworthy in that data, and what has
been found so far.

```
androidboot.selinux=permissive      BOARD_BOOTCONFIG
SELINUX_IGNORE_NEVERALLOWS := true  BoardConfig.mk
getenforce                          Permissive
```

## Do not trust an unqualified denial count

Three numbers have been quoted for this device: **5**, then **0**, then **42**,
then **98**. All were measured honestly and none is the real figure.

- Permissive only logs code paths that actually execute. The boots that showed 5
  and 0 never touched Bluetooth, GPS, camera or telephony.
- **`dontaudit` suppresses logging even in permissive mode.** Every count taken
  so far understates the truth by an unknown margin. Policy must be rebuilt with
  `dontaudit` stripped before any number means anything.

## The collector

Denials are gathered continuously rather than snapshotted, because logcat rotates
in minutes and one-shot captures miss whatever you were not doing at the time.

```
/data/adb/avc/collect.sh            the collector
/data/adb/avc/denials.log           unique full lines
/data/adb/avc/seen.keys             normalised keys; dedup survives reboots
/data/adb/service.d/avc-collect.sh  Magisk starts it each boot
```

It normalises two things away before deduplicating. Without that the file fills
with the same rule repeated once per app and once per process:

- **MLS categories** — `s0:c186,c256` and `s0:c220,c256` are one rule, not two
- **PIDs in paths** — `/proc/8905/net/raw` and `/proc/9170/net/tcp` are one
  labelling problem

It reparents to init, so it survives `adb` disconnects and reboots.

## Retracted: "Magisk contaminates the data"

An earlier version of this document claimed Zygisk was polluting roughly a quarter
of the collected denials by attributing them to `zygote`. **That was wrong**, and
the evidence that disproved it is worth keeping.

The first flash of 2026-08-06 removed Magisk (a new `init_boot` overwrites the
patched one). With Magisk definitively absent, `zygote` still accounted for 608
of 998 denials. Zygisk cannot explain denials that occur without Zygisk.

The zygote-attributed Bluetooth denials — writing `/data/misc/bluedroid`,
renaming `bt_config.conf` — had a much simpler cause: **`com.android.bluetooth`
was running in the zygote domain**, from the certificate mismatch described
below. They were literally Bluetooth's own files, logged under the domain
Bluetooth was wrongly in. After that fix, zero non-property zygote denials remain.

The lesson: a surprising `scontext` is more often a domain-transition bug than
tooling interference, and "our instrumentation is lying" is a conclusion to reach
last, not first.

Denials with `scontext=u:r:shell:s0` *are* genuinely an artefact of investigation
over adb rather than of the device doing anything.

## Most of the count is two behaviours, not hundreds of bugs

The clean 998-denial snapshot breaks down as:

```
608   zygote              /dev/__properties__/u:object_r:*_prop:s0
332   hal_camera_default  /dev/__properties__/u:object_r:*_prop:s0
 58   everything else
```

Roughly 940 of 998 are `{ getattr }`, `{ map }` and `{ open }` on property files
— about 200 property types for zygote and 110 for the camera HAL, each counted
three times. It is **two processes walking the property area**, not 940 problems,
and it will likely collapse to a small number of policy lines.

This also explains why earlier collections looked so much smaller: they started
*after* boot, and the property enumeration happens during it. Any collection that
misses boot misses most of the set.

## Found: Bluetooth runs in the zygote domain

Not contamination — a real bug, and a regression from the release-keys work.

`com.android.bluetooth` never leaves the `zygote` domain:

```
$ ps -A -Z -o LABEL,NAME | grep zygote
u:r:zygote:s0    zygote64
u:r:zygote:s0    com.android.bluetooth      ← should be u:r:bluetooth:s0
```

Every other process transitions correctly. The rule that should move it is

```
user=bluetooth seinfo=bluetooth domain=bluetooth type=bluetooth_data_file
```

and `seinfo` comes from certificate matching in `plat_mac_permissions.xml`. Those
certificates did not match:

```
Bluetooth.apk signed with        CN=frogger, O=ssiyad, hello@ssiyad.com
seinfo="bluetooth" bound to      CN=com.android.bluetooth.services, O=Android
```

Every other tag — `platform`, `media`, `network_stack`, `nfc`, `sdk_sandbox` —
correctly used our certificate. Only Bluetooth used AOSP's, because
`build/make/core/config.mk:863` does not fall back to our key directory the way
its NetworkStack sibling does:

```make
ifdef PRODUCT_MAINLINE_BLUETOOTH_SEPOLICY_DEV_CERTIFICATES
  ... := $(PRODUCT_MAINLINE_BLUETOOTH_SEPOLICY_DEV_CERTIFICATES)
else ifneq (,$(filter com.google.android.bt,$(PRODUCT_PACKAGES)))
  ... := $(MAINLINE_SEPOLICY_DEV_CERTIFICATES)
else
  ... := $(dir build/make/target/product/security/testkey)
endif
```

We build `com.android.bt` from source instead of shipping Google's
`com.google.android.bt`, so the third branch won.

**Fixed** by setting `PRODUCT_MAINLINE_BLUETOOTH_SEPOLICY_DEV_CERTIFICATES` in
`vendor/lineage-priv/keys/keys.mk`. Not yet built.

Two things worth taking from this. Permissive mode hid it completely — nothing
was blocked, so Bluetooth worked and only the *attribution* of its denials was
wrong. And it would have been ugly in enforcing: an entire subsystem in the wrong
domain, failing in ways that look nothing like a missing `allow` rule.

**Verify after the next flash:**

```sh
adb shell 'ps -A -Z -o LABEL,NAME | grep com.android.bluetooth'   # want u:r:bluetooth:s0
```

## The real collection: 2698 denials over 2.5 days

Collected 2026-08-06 → 08-08 with `dontaudit` stripped, daily driving, no reboot.

```
2698   total unique
2243   /dev/__properties__ enumeration   (83%)  -- hal_camera_default 1300, zygote 896
 456   everything else
 111   device-relevant (vendor/HAL/init/system domains)
```

**The property enumeration is one behaviour, not 2243 problems.** Two processes
walk the property area and each property type counts separately. Address it with
a single rule when the time comes; do not let it distort the numbers.

Of the remaining 456, most are app-level (`untrusted_app` 148, `isolated_app` 31,
`gmscore_app` 20) — generic AOSP noise. Magisk contributes a visible set too,
identifiable by `trawcon="u:r:magisk:s0"` or `/debug_ramdisk/.magisk/` paths;
those are artefacts of root and must not be written into policy.

### Fixed: two labelling bugs, one of them functional

**Glyph notification LED.** `hal_light_default` writes `dev_color` on the
`aw20036_led` node, which was the one attribute missing from the `genfs_contexts`
list where five siblings were already labelled. It fell back to generic `sysfs`.
There was a second bug behind it: `hal_light_default` had no `sysfs_leds` rule at
all, only `led_device:chr_file`. Labelling alone would have moved the denial
rather than fixed it, so both were needed.

**Headset/accessory detection.** `system_server` reads the extcon cable name on
the WCD9378 codec to detect insertion. AOSP already permits this —
`system/sepolicy/private/system_server.te:494` has
`r_dir_file(system_server, sysfs_extcon)` — so **only the label was missing** and
the node fell back to generic `sysfs`.

That second one is the case this document warned about: `audit2allow` would have
emitted `allow system_server sysfs:file read`, granting access to *all* unlabeled
sysfs, when the correct fix is one `genfscon` line and no new allow rule at all.

### Remaining, categorised

**Labelling gaps** — `tcontext=u:object_r:sysfs:s0` with a specific path, so
`genfs_contexts` is the fix:

```
/sys/devices/platform/soc/1d84000.ufshc/.../mq/0/nr_tags   init      open
/sys/devices/virtual                                       hal_nt_charger  open read (dir)
/sys/block, sda2, read_ahead_kb, gc_urgent                 vold, vendor_qti_init_shell
```

`/sys/devices/virtual` needs care — it is a very large subtree and must not be
labelled wholesale to satisfy one directory read.

**Missing allow rules** — target type is already correct, so the `.te` is the fix:

```
hal_thermal_default → sysfs_thermal   { add_name write }  trip_point_1_temp/hyst
vendor_nicmd        → sysfs_net       rps_cpus, rx-0
netutils_wrapper    → vendor_sysfs_*  xt_idletimer timers
vendor_qccvendor    → vendor_sysfs_soc_sensitive  /sys/devices/soc0/serial_number
hal_fingerprint_default → default_prop, overlay_prop (persist.vendor.overlay.fp_serial)
mediametrics        → same_process_hal_file  /vendor/lib64/libutils.so
vendor_qtelephony   → default_android_service  nothing.radio.ntphone
```

> **Retracted.** This section previously called `hal_thermal_default` wanting
> `add_name`/`create` on `sysfs_thermal` suspicious, on the grounds that files
> cannot be created in sysfs, and guessed it meant a missing thermal node. Wrong:
> `trip_point_1_temp` exists on every zone. `create` on an existing file is the
> **O_CREAT signature** — `fopen(path, "w")` and shell `> file` both pass
> `O_CREAT|O_TRUNC`, so the kernel checks `create`/`add_name` regardless. Same
> for `vendor_nicmd` on `rps_cpus` and `vendor_qti_init_shell` on `defrag`. All
> three are ordinary allow rules, written 2026-08-08.

**Upstream/AOSP, not ours:** `cgroup_v2` creates by `init`/`system_server`/
`zygote`, `{ noatsecure }`, `netd` on `proc_net`, `dex2oat` searching app data,
`kernel` capabilities. Leave these alone.

## Stripping `dontaudit` — no build required

**1358 `dontaudit` rules** (724 plat + 634 vendor) were suppressing denials, in
permissive mode included. They are gone from the running policy as of
2026-08-06, and **this needed no build**: the CIL sources ship on the device, so
the policy can be recompiled and reloaded live.

The build system has no hook for this — nothing under
`system/sepolicy/build/soong/` references dontaudit — so patching Soong would
have been the alternative.

```sh
# 1. Version comes from the vendor partition, NOT from ro.board.api_level.
cat /vendor/etc/selinux/plat_sepolicy_vers.txt      # 202504
cat /vendor/etc/selinux/genfs_labels_version.txt    # 202504

# 2. Recompile on device, with the device's own secilc and init's exact
#    arguments (system/core/init/selinux.cpp), adding -D.
cd /data/adb/avc/policy/cil
/system/bin/secilc plat_sepolicy.cil -m -M true -G -N -D -c 30 \
    plat_map.cil -o /data/adb/avc/policy/pol.nodontaudit -f /sys/fs/selinux/null \
    system_ext_sepolicy.cil se_map.cil product_sepolicy.cil prod_map.cil \
    plat_pub_versioned.cil vendor_sepolicy.cil plat_sepolicy_genfs_202504.cil

# 3. Load it. MUST be a single write().
SZ=$(stat -c %s pol.nodontaudit)
dd if=pol.nodontaudit of=/sys/fs/selinux/load bs=$SZ count=1
```

Two traps, both hit:

- **The mapping version is `202504`, not `34.0`.** `ro.board.api_level` is 34 and
  the mapping directory contains both, so guessing looks plausible and fails with
  `Failed to resolve expandtypeattribute statement`. The authority is
  `/vendor/etc/selinux/plat_sepolicy_vers.txt`.
- **`cat > /sys/fs/selinux/load` fails with `EINVAL`.** The kernel requires the
  whole policy in one `write()`, and `cat` chunks it. Use `dd bs=<filesize>`.

The load is **not persistent**, so `/data/adb/post-fs-data.d/00-avc-policy.sh`
reapplies it every boot and appends to `/data/adb/avc/policy/load.log`. Without
that, the first reboot silently reverts to the suppressed set with nothing to
indicate it happened. Deleting the script and rebooting reverts everything.

Baselines kept in `/data/adb/avc/archive/`:

```
denials.prefix-bluetoothbug.log     1117   before the Bluetooth domain fix
denials.pre-dontaudit-strip.log     2427   after that fix, before the strip
```

Newly visible after stripping: `{ noatsecure }`, `netd → proc_net:file create`,
`system_server → cgroup_v2:file create`, and a batch with
`scontext=u:object_r:unlabeled:s0` on `/proc` — all classic AOSP `dontaudit`
targets, none of them present in the earlier sets.

## Step 3 resolved: the property enumeration needs no policy

83% of every collection was `getattr`/`map`/`open`/`read` on
`/dev/__properties__/u:object_r:*_prop:s0`. It is **by design** and must not be
given an allow rule.

bionic opens property context files by two paths, and they behave differently on
purpose (`bionic/libc/system_properties/`):

```
foreach()             CheckAccess() -> access(R_OK) -> audit_access -> dontaudit -> SILENT
GetPropAreaForName()  Open() directly
                      "we explicitly do not check no_access_ in this case because
                       unlike the case of foreach(), we want to generate an selinux
                       audit for each non-permitted property access in this function"
```

So AOSP deliberately logs a denial when a process looks up a property it is not
entitled to read. The denial *is* the intended behaviour; the read returns
nothing and the process carries on.

**Not an artefact of stripping dontaudit.** 992 of the 1117 denials in
`archive/denials.prefix-bluetoothbug.log` are these, collected with dontaudit
fully intact. The generic rule `dontaudit domain property_type:file audit_access`
covers only the `access()` probe, not the open.

**The obvious fix is a trap.** From `system/sepolicy/public/te_macros`:

```
define(`get_prop', `allow $1 $2:file { getattr open read map };')
```

`get_prop(domain, property_type)` grants a domain read on *every property on the
device*. It would delete property sandboxing to quieten a log — the same shape of
error as `audit2allow` handing over all of unlabeled sysfs, only larger.

The domains involved also change between boots — `zygote` and
`hal_camera_default` on one, `permissioncontroller_app` and `bluetooth` on
another — which is what a generic mechanism looks like, not a few misbehaving
processes.

**Handled by filtering, not policy.** `tools/avc-collect.sh` now excludes them
and writes a note every 500 suppressed, so the set stops being mostly noise.

## Order of work

1. ~~**Strip `dontaudit`**~~ — done 2026-08-06, live and persistent across reboots.
2. **Exercise the untouched subsystems.** GPS, telephony, camera, tethering, USB.
   Denials that never fired are the ones that matter.
3. **Write policy by hand.** `audit2allow` on the `hal_nt_charger` denial emits
   `allow hal_nt_charger sysfs:dir { open read }`, which grants access to *all*
   unlabeled sysfs. The correct fix is one `genfs_contexts` line —
   `sepolicy/vendor/genfs_contexts` already exists. Virtual filesystems cannot be
   labelled from `file_contexts`.
4. **Drop `SELINUX_IGNORE_NEVERALLOWS`.** Separate from flipping enforcing and
   usually harder: a neverallow violation means the policy is wrong, not merely
   incomplete.
5. **Flip to enforcing** and fix what breaks.

Camera being parked helps — it is a large denial surface that will not shift
while policy is written. Policy written now must be revisited when it returns.

## NFC

Frogger has **no NFC hardware** — no `android.hardware.nfc*` feature, no
`/dev/nq-nci`, no NFC HAL in `/vendor/bin/hw/`. There are no NFC denials to
collect and nothing to write policy for.
