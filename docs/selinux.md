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

## Magisk contaminates the data — know this before reading the log

Zygisk injects into zygote, and forked processes can be logged before they finish
transitioning to their app domain. The result is denials attributed to `zygote`
that belong to something else:

```
{ call }       zygote → gmscore_app   binder
{ transfer }   zygote → system_server binder
{ wake_alarm } zygote → zygote        capability2
```

Zygote does not make binder calls to app domains or take wake locks. **None of
this exists on an unrooted build.** Writing policy for it would be writing policy
for Magisk.

Denials with `scontext=u:r:shell:s0` are usually an artefact of investigation over
adb, not of the device doing anything.

Between them these were roughly a quarter of the first collection. Filter before
running anything over the log.

**Decided 2026-08-06: keep Magisk and live with the contamination for now.** Root
is worth more than clean data at this stage — it is what made the Bluetooth
domain bug findable, and what allowed the LVACFS fix to be tested with a bind
mount instead of a build cycle. The plan is a separate clean collection later:
uninstall Magisk, use the device normally for a day, and collect an uncontaminated
set to diff against this one. Until that happens, **treat every `zygote`-sourced
denial as unattributed rather than as a fact about zygote.**

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

## Genuinely device-specific denials so far

Small, and both already suspected:

```
hal_fingerprint_default → default_prop:file   { getattr map open read }
hal_nt_charger          → sysfs:dir           { open read }   /sys/devices/virtual
```

The remainder is generic app noise — `untrusted_app` reading `proc_net` and
`proc_filesystems`, apps creating profile directories — the sort AOSP normally
`dontaudit`s.

## Order of work

1. **Exercise the untouched subsystems.** GPS, telephony, camera, tethering, USB.
   Denials that never fired are the ones that matter.
2. **Rebuild with `dontaudit` stripped**, then collect again. Bundle this with
   another build rather than spending a cycle on it alone.
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
