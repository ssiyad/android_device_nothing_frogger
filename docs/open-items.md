# Open items

Live tracker for Frogger bring-up. Resolved items are **removed** from here —
their rationale lives in git history and in [decisions.md](decisions.md),
[hardware-facts.md](hardware-facts.md) and [changes.md](changes.md).

## TODO

Roughly in the order they block progress.

- [ ] **Port the 36 `CONFIG_NOTHING_IS_FROGGER` hunks** in `sm7635-modules`
      (display 2 files, touch 6, audio 7) — display and touch are boot-relevant
- [ ] **Flash and boot.** Nothing has been flashed yet; this is the only real test
- [ ] **Verify the `CONFIG_NFC_SE_STM` fix** took effect (needs a real `mka` run)
- [ ] **`brunch frogger`** — system/vendor/product images have never been built
- [ ] Verify touchscreen sysfs nodes bind (single-tap, UDFPS)
- [ ] Verify audio; if speakers are silent, flip `speaker_protection_enabled` to `0`
- [ ] Verify display brightness curve resolves to the expected panel ID
- [ ] Decide `spunvm` — stock comments the mount out, we mount it
- [ ] Reconcile the 29 generic modules once the kernel actually builds them
- [ ] **Camera** — entirely unexamined; expect the largest remaining workstream
- [ ] Two deferred device-tree items (thermal NTC, pinctrl audio-pop fix)
- [ ] SELinux back to enforcing; drop `SELINUX_IGNORE_NEVERALLOWS` and
      `BOARD_API_LEVEL_PROP_OVERRIDE` before any release build
- [ ] Decide whether to version `vendor/nothing/frogger` (GitLab) or regenerate
- [ ] Re-enable Glyph if wanted (needs a `ParanoidGlyphPhone4a` upstream target)

---

## Frogger-conditional code in the external modules

The OEM guards Frogger behaviour with `#if IS_ENABLED(CONFIG_NOTHING_IS_FROGGER)`
in `vendor/qcom/opensource/`, which maps to `kernel/nothing/sm7635-modules`:

| Module | Files |
|---|---|
| `audio-kernel` | 7 |
| `touch-drivers` | 6 |
| `display-drivers` | 2 |
| **total** | **15 files, 36 sites** |

Small enough to port by hand, but it crosses 6.1 → 6.6, so each hunk needs
checking against the fork's version of the file rather than applied blind.

## Verify the NFC config fix

`CONFIG_NFC_SE_STM=m` arrives from **`pineapple_perf.config`** — the shared
platform fragment, not a Nothing one — so `st54spi.ko` was built and installed
into `vendor_dlkm` despite NFC being removed and the module being absent from
`modules.load`. `frogger_perf.config` now sets `# CONFIG_NFC_SE_STM is not set`.

Unproven: the kernel `.config` is regenerated from defconfig fragments by a
ninja rule that only a real `mka` run drives. After the next `mka bootimage`:

```
grep CONFIG_NFC_SE_STM out/target/product/frogger/obj/KERNEL_OBJ/.config
find out/target/product/frogger/obj/PACKAGING -name 'st54spi*.ko'
```

Both should come back empty.

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
Re-run the comparison once the kernel builds and drop whatever it doesn't produce.

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
