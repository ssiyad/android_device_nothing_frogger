# Open items

Live tracker for Frogger bring-up. Resolved items are **removed** from here —
their rationale lives in git history and in [decisions.md](decisions.md),
[hardware-facts.md](hardware-facts.md) and [changes.md](changes.md).

## TODO

In dependency order. Nothing below "flash and boot" can be checked until the
device actually comes up.

**Status:** the kernel side is complete and verified — Frogger drivers, device
trees, display/touch conditionals, the AW882xx codec and the module lists all
build and package together, `boot.img` at 96 MB with zero errors and zero
unresolved symbols. That validates compilation and packaging only; nothing has
been flashed.

### Get to a flashable image

- [ ] **`brunch frogger`** — system/vendor/product images have never been built;
      only `boot.img` exists, so there is nothing to flash yet. Expect a fresh
      crop of failures the kernel work never exercised: sepolicy, missing
      packages, blob issues
- [ ] **Port the 13 audio `CONFIG_NOTHING_IS_FROGGER` hunks** — `wcd9378` (5),
      `wcd9378-mbhc` (2), `aw882xx` (2), and one each in `wcd-mbhc-v2.c`,
      `wcd-mbhc-v2.h`, `msm_dailink.h`, `internal.h`. Not boot-critical; easier
      to reason about once audio can be watched failing on a running device

### First boot

- [ ] **Flash and boot.** The only real test; everything so far is static analysis
      plus a clean compile
- [ ] Triage the boot log — `dmesg`, `last_kmsg`, `logcat` — and let it drive the
      order of everything below

### Needs a booted device

- [ ] Verify touchscreen sysfs nodes bind (single-tap, UDFPS)
- [ ] Verify audio; if speakers are silent, flip `speaker_protection_enabled` to `0`
- [ ] Verify display brightness curve resolves to the expected panel ID
- [ ] Decide `spunvm` — stock comments the mount out, we mount it
- [ ] Reconcile the 29 generic modules once the kernel actually builds them
- [ ] **Camera** — entirely unexamined; expect the largest remaining workstream

### Cleanup, no boot needed

- [ ] Two deferred device-tree items (thermal NTC, pinctrl audio-pop fix)
- [ ] SELinux back to enforcing; drop `SELINUX_IGNORE_NEVERALLOWS` and
      `BOARD_API_LEVEL_PROP_OVERRIDE` before any release build
- [ ] Decide whether to version `vendor/nothing/frogger` (GitLab) or regenerate
- [ ] Re-enable Glyph if wanted (needs a `ParanoidGlyphPhone4a` upstream target)
- [ ] Re-enable `DeviceExtras` — needs either a `system/sepolicy` fork carrying a
      `device_extras` compat entry, or making the type private

---

## Frogger-conditional code in the external modules — audio remains

The OEM guards Frogger behaviour with `#if IS_ENABLED(CONFIG_NOTHING_IS_FROGGER)`
in `vendor/qcom/opensource/`, mapping to `kernel/nothing/sm7635-modules`.

**Display and touch are done** (commit `dd79941698`) and build clean:

* `dsi_display.c` — DSI reads in low-power mode; record the booted panel name
  (`nt37706a` 120Hz FHD+, BOE or Visionox) into `panel_name_find`
* `sde_connector.c` — skip `backlight_update_status()` during a fingerprint scan
* `focaltech_core.{c,h}` — `vendor_name` field; Frogger FOD-recovery semantics
* `focaltech_flash.c` — publish TP firmware as `ft3683g-<vendor>-0x<ver>`

Two OEM sites were correctly *not* ported: `sde_connector.c` declares
`fp_status` extern, but this tree already defines and exports it there with the
LHBM integration Asteroids added and Frogger reuses. Four more are skipped
permanently — they call `fts_test_init`/`fts_test_exit` from `focaltech_test.c`,
a factory self-test this tree does not carry; referencing them would break the
link.

**Audio is outstanding — 13 sites:**

| File | Sites |
|---|---|
| `asoc/codecs/wcd9378/wcd9378.c` | 5 |
| `asoc/codecs/wcd9378/wcd9378-mbhc.c` | 2 |
| `asoc/codecs/aw882xx/aw882xx.c` | 2 |
| `asoc/codecs/wcd-mbhc-v2.c`, `include/asoc/wcd-mbhc-v2.h`, `asoc/msm_dailink.h`, `wcd9378/internal.h` | 1 each |

It crosses 6.1 → 6.6, so each hunk needs checking against this tree's version of
the file rather than applied blind.

## Touchscreen sysfs nodes

`device.mk` points the sensors HAL at `fts_gesture_single_tap_{pressed,enabled}`
and `fts_fod_{pressed,enabled}` under
`/sys/devices/platform/soc/ac0000.qcom,qupv3_0_geni_se/a80000.spi/spi_master/spi0/spi0.0`.

These exist in **no OEM tree** — they are LineageOS additions, confirmed present
in `sm7635-modules/noth/touchscreen/focaltech_core.c`. Frogger uses the same
Focaltech panel, so they should work, but nothing has been exercised on device.

`/proc/touchpanel/gesture_code` **was** confirmed present on stock.

## Audio

All six `audio/*.xml` were rebased on Frogger's stock files, with the LineageOS
delta re-applied selectively (only the LVACFS mic param ported; the haptics,
`bluetooth_qti`, A2DP-format and Dolby patches were all inapplicable).

Two things to check on first boot:

**Speaker protection.** Frogger's stock sets `speaker_protection_enabled=1` and
its mixer paths expose `SpkrLeft`/`Spkr2Right VISENSE`, so V/I sense is wired —
unlike Asteroids, where LineageOS turned it off. Stock's `1` was kept. If
playback is silent or distorted, flip it to `0` in
`audio/resourcemanager_volcano_qrd.xml` first.

**Dolby.** `device.mk` still has `inherit-product-if-exists hardware/dolby`. It
is inert — Frogger ships zero Dolby files — but can be dropped.

## Display brightness curve

`configs/display_id_frogger.xml` is stock's config, carried verbatim. Once
booted, confirm the panel still resolves to `local:4630947107087237506` via
`dumpsys display`. If it resolves to `4630947039571902850` or `…851` (the other
two configs stock ships), the `PRODUCT_COPY_FILES` destination filename must
change to match.

## `spunvm`

Stock Frogger comments this mount out in `fstab.default` even though the
partition exists (`/dev/block/by-name/spunvm -> sde75`). We still mount it,
inherited from Asteroids. If first-stage mount fails, comment it out.

## 29 generic modules

`modules.load.*` references 29 modules Frogger's stock images do not ship:

```
9pnet.ko  9pnet_fd.ko  clk-scmi.ko  governor_gpubw_mon.ko
governor_msm_adreno_tz.ko  leds-qcom-lpg.ko  macsec.ko  ntfs3.ko
pps_core.ko  ptp.ko  ptp_kvm.ko  q2spi-geni.ko  qcom-amoled-regulator.ko
qcom_ice.ko  qrtr-tun.ko  qti_pmic_glink.ko  tls.ko  ucsi_qti_glink.ko
ufs-qcom.ko  usbmon.ko  vcpu_stall_detector.ko  virtio_*.ko (6)
vmw_vsock_virtio_transport.ko  xhci-sideband.ko
```

Probably fine — GKI/virtualisation modules, or drivers stock compiles *into* the
kernel. A few look like cross-branch renames (stock has `pmic_glink.ko` /
`ucsi_glink.ko` where the list wants `qti_pmic_glink.ko` / `ucsi_qti_glink.ko`).

The kernel now builds, and depmod reports **no** unresolved symbols, so nothing
here breaks the build. Re-run the comparison against
`out/target/product/frogger/obj/PACKAGING/kernel_modules_intermediates` and drop
whatever the kernel does not actually produce — cosmetic, but it keeps the load
lists honest.

## Camera — entirely unexamined

Nothing here has looked at camera beyond the blob list. Known differences:

* sensor complement — `frogger_*` sensor/eeprom/sensormodule/tuned modules
  replace Asteroids' `arcanine_*` (see hardware-facts.md)
* post-processing — Morpho EIS and ArcSoft, where Asteroids uses Vidhance
* camera PMIC — SGM38120 rather than WL28681, now in the device tree

The `libarcsoft_*` blobs are among the largest in `vendor/` (one is 118 MB).

## Deferred device-tree items

Both live in the fork's shared `qcom/` base rather than `noth/`, so editing them
in place would affect Asteroids; each wants expressing as an override:

* thermal NTC restructure (`sys-therm-13` → `sub_board_ntc`, and adding
  `qupv3_se1_i2c` to thermal `qcom,critical-devices`)
* one pinctrl `bias-disable` → `bias-pull-down` audio-pop fix (BELL-5845)

## DeviceExtras — disabled (sepolicy Treble test)

`treble_sepolicy_tests_202404` fails with:

```
The following public types were found added to the policy without an entry into
the compatibility mapping file(s) ... device_extras
```

`hardware/nothing/sepolicy/DeviceExtras/public/device_extras.te` declares
`type device_extras, domain;`, and `hardware/nothing/config.mk` only adds that
public sepolicy dir when `DeviceExtras` is in `PRODUCT_PACKAGES`. A **public**
type must have an entry in
`system/sepolicy/private/compat/<ver>/<ver>.ignore.cil` under `new_objects`.

It has to be public: our `sepolicy/vendor/device_extras.te` grants it access to
`vendor_proc_power_supply`, and vendor policy can only reference public types.

Disabled for now — `DeviceExtras` commented out in `device.mk`, and the two
vendor rules commented out with it. Nothing else depends on it; Lineage Health
charging control is separate (`vendor.lineage.health-service.default` plus the
`lineage_health` soong config).

This is a **build-time** check on policy API compatibility. It is unrelated to
`androidboot.selinux=permissive` and would fail identically with a fully
enforcing policy.

There is no device-side hook to extend the mapping list.
`BOARD_PLAT_PUBLIC_SEPOLICY_DIR` adds public policy, not mappings, and the
compat files live only in `system/sepolicy/private/compat/`.

**Ways to restore it, most correct first:**

1. **Stop a system_ext app touching vendor files directly.** Route the access
   through a HAL and move `device_extras` from `public/` to `private/` in
   `hardware/nothing`. The HAL already exists and already has the permission:
   `hal_lineage_health_default` has
   `rw_dir_file(hal_lineage_health_default, vendor_proc_power_supply)`.
   Architecturally right; means patching `hardware/nothing` (a fork) and
   possibly the app itself.
2. **Fork `system/sepolicy`** (`LineageOS/android_system_sepolicy`) and add
   `device_extras` to `new_objects` in `202404.ignore.cil` and
   `202504.ignore.cil`. This is what AOSP's own error message suggests -- it
   links two gerrit changes doing exactly this. Two lines, but a permanent
   rebase burden. LineageOS adds none of its own types to those lists, so there
   is no in-tree precedent.

Note what does **not** work: deleting our `sepolicy/vendor/device_extras.te`
rules alone. The type is public because of where it is declared
(`DeviceExtras/public/`), not because we reference it.

## Glyph — disabled

`ParanoidGlyphPhone4a` does not exist upstream, and Frogger's Glyph is a
different layout from the Phone (3a) (6 channels, 6×1,
`CONFIG_LEDS_AW20036_FROGGER`), so it needs a real new target rather than a
rename. The `PRODUCT_PACKAGES` block and the
`packages/apps/{ParanoidGlyph,GlyphAdapter}` soong namespaces are commented out
in `device.mk`. NullDebris publishes `packages_apps_ParanoidGlyph`
(`lineage-23.1`) and `packages_apps_GlyphAdapter` (`15`) when it's time.

## Bring-up hacks to remove before release

* `androidboot.selinux=permissive` in `BOARD_BOOTCONFIG`
* `SELINUX_IGNORE_NEVERALLOWS := true` in `BoardConfig.mk`
* `BOARD_API_LEVEL_PROP_OVERRIDE := 34` — the build warns it is test-only

## `vendor/nothing/frogger`

1.8 GB, 1642 files, not repo-managed. GitHub is not an option: `modem.img`
(182 MB) and `libarcsoft_tfe_hdr.so` (118 MB) both exceed its 100 MB per-file
limit. Options: GitLab (what NullDebris does for Asteroids), or don't version it
and regenerate with `extract-files.py` from the firmware dump.

All 1616 blob entries resolve against the `2603091830` factory images alone, so
no live-device files are needed. The dump is one build older than the phone, so
re-run `extract-files.py` and check its output.

## Lower priority

* **`ro.boot.pbid` dead code.** `nothing-fwk`'s `NtFeaturesUtils` still reads
  `ro.vendor.nothing.feature.diff.plus.<device>` and `ro.boot.pbid`. Frogger has
  neither, so the path is inert.
* **`FroggerEuiccOverlay`** lists `Frogger` with `esim-slot-ids:[1]`. eUICC
  permission ships only on JPN, so this is inert elsewhere and untested on a JPN
  unit.
* **Second touch panel driver.** Frogger ships `goodix_ts.ko` alongside
  `focaltech_fts.ko`, suggesting dual sourcing. The reference unit is Focaltech.
  `goodix_ts.ko` was not added, because referencing a module the kernel does not
  build would break module loading. Add it if a Goodix-panel unit turns up.
* **Verizon packages** (`MyVerizonServices`, `WfcActivation`, `VZWAPNLib`,
  `AppDirectedSMSService`) were dropped — absent from both Frogger inventories.

---

## Note on validating module-list changes

`BOARD_VENDOR_*_KERNEL_MODULES_LOAD` is expanded at config time via
`$(shell cat modules.load.*)` and baked into the generated
`Image.gz.rsp`. Re-running that `.rsp` directly rebuilds the image from an
existing `.config` and an existing module list, so it **cannot** validate
changes to either `modules.load.*` or `arch/arm64/configs/vendor/*.config`.

Only a real `mka`/`brunch` run regenerates them. This cost three wasted
debugging rounds; don't shortcut it.

## Reference: repositories

Managed via `.repo/local_manifests/frogger.xml`.

| Path | Repo | Branch |
|---|---|---|
| `device/nothing/frogger` | `ssiyad/android_device_nothing_frogger` | `6.6/lineage-23.2` |
| `kernel/nothing/sm7635` | `ssiyad/android_kernel_nothing_sm7635` | `6.6/lineage-23.2` |
| `kernel/nothing/sm7635-modules` | `ssiyad/android_kernel_nothing_sm7635-modules` | `6.6/lineage-23.2` |
| `kernel/nothing/sm7635-devicetrees` | `ssiyad/android_kernel_nothing_sm7635-devicetrees` | `6.6/lineage-23.2` |
| `hardware/nothing` | `NullDebris/android_hardware_nothing` | `lineage-23.2` |
| `vendor/nothing/frogger` | — | generated by `extract-files.py` |

The kernel repos are forks of **NullDebris**', carrying the Frogger port on top;
rebase on upstream periodically. Asteroids support is deliberately **kept** in
them — it does not compile for Frogger (gated by config and `noth/Makefile`) and
removing it would conflict permanently with upstream.

`LineageOS/android_hardware_nothing` is **not** the right repo despite the
matching name — it is the Phone (1)/(2) generation, with no `config.mk` and none
of the required HALs.

The Asteroids tree this was forked from is
`NullDebris/android_device_nothing_asteroids`; that org publishes a manifest at
`NullDebris/manifest` listing every dependency. Worth watching for changes.
