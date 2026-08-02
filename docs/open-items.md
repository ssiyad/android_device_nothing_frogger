# Open items

Things this tree assumes, and work it depends on that lives outside it. Roughly
in the order they will block a first boot.

## Blocking: repositories that do not exist yet

The tree references four paths that are absent from the local LineageOS checkout:

| Path | Referenced by | Status |
|---|---|---|
| `vendor/nothing/frogger` | `device.mk`, `BoardConfig.mk`, `Android.bp`, `qcril-database/Android.bp` | produced by `extract-files.py` |
| `kernel/nothing/sm7635` | `TARGET_KERNEL_SOURCE`, soong namespace | needs syncing; not present |
| `kernel/nothing/sm7635-modules` | `TARGET_KERNEL_EXT_MODULE_ROOT` | needs syncing; not present |
| `hardware/nothing` | `include hardware/nothing/config.mk` | needs syncing; not present |

`hardware/st` **is** present, so the NFC and secure element HALs are available.

## Blocking: kernel defconfig fragment name is an assumption

`BoardConfig.mk` asks for:

```
TARGET_KERNEL_CONFIG := gki_defconfig vendor/pineapple_perf.config vendor/frogger_perf.config
```

The OEM kernel at `~/sources/android/kernels/frogger/` has a genuine Frogger
target — `build.config.nothing.Frogger` (`TARGET_PRODUCT=Frogger`,
`NT_PROJECT=Frogger`, `-DNT_PROJECT=Frogger`), which applies
`arch/arm64/configs/vendor/Frogger.config`:

```
CONFIG_NOTHING_IS_FROGGER=y
CONFIG_LEDS_AW20036_FROGGER=m
CONFIG_OEM_BOOTINFO=m   CONFIG_OEM_HWINFO=m   CONFIG_OEM_ERRCODE=m
CONFIG_NOTH_HARDWARE_ID=m   CONFIG_OEM_CABLE=m
CONFIG_NFC_SE_STM_GPIO=m    # ST54L
CONFIG_NT_SECURE_STATE=m    CONFIG_NT_RPMB_STATE=m
```

But the OEM tree has **no** `*_perf.config` fragments at all — `pineapple_perf`
and `asteroids_perf` are LineageOS additions living in `kernel/nothing/sm7635`,
which is not checked out. So `frogger_perf.config` follows the Lineage naming
convention but does not exist yet; whoever creates the Lineage Frogger kernel
branch has to add it, merging `Frogger.config` above with the perf tuning.
Fix the filename in `BoardConfig.mk` if they pick a different one.

Also note the OEM tree keeps external modules under `vendor/qcom/opensource/`
(including `fingerprint` and `touch-drivers`), whereas `BoardConfig.mk` expects
the Lineage layout `noth/fingerprint` and `noth/touchscreen` under
`kernel/nothing/sm7635-modules`. The remap is Lineage's, not the OEM's.

## Glyph: deliberately disabled

`ParanoidGlyphPhone4a` does not exist upstream and Frogger's Glyph is a
different layout from the Phone (3a) (6 channels, 6×1,
`CONFIG_LEDS_AW20036_FROGGER`), so it needs a real new target rather than a
rename. Rather than block the build, the Glyph packages and their soong
namespaces are **commented out** in `device.mk`. Re-enable both the
`PRODUCT_PACKAGES` block and the `packages/apps/{ParanoidGlyph,GlyphAdapter}`
soong namespaces once a Phone (4a) target exists.

## Needs verification: touchscreen sysfs nodes

`device.mk` points the sensors HAL at:

```
.../spi0.0/fts_gesture_single_tap_pressed
.../spi0.0/fts_gesture_single_tap_enabled
.../spi0.0/fts_fod_pressed
.../spi0.0/fts_fod_enabled
```

None of these exist on stock Frogger, and none appear anywhere in the OEM kernel
source. Stock exposes `fts_fod_mode`, `fts_gesture_mode`, `fts_gesture_bm`,
`fts_gesture_buf` instead. The four node names above are additions made by
LineageOS' own touchscreen driver, so the paths are correct **provided** the
Frogger kernel carries the same patches. Both phones use Focaltech at the same
SPI address, so porting should be mechanical — but confirm after syncing
`kernel/nothing/sm7635-modules`.

`/proc/touchpanel/gesture_code` **was** confirmed present on stock, so
`tp_single_tap_coords_path` is fine as-is.

## Needs verification: NFC, on hardware that has it

Everything NFC in this tree is derived from stock config files, not observed.
The reference device is an IND unit with no NFC hardware at all
(`pm list features` lists no `android.hardware.nfc`; the `1-0008/hw_version` node
Asteroids probes is absent; `vendor.nfc.config_file_name` is unset).

Specifically unverified:

* `init/init.frogger.nfc.sh` — the JPN/non-JPN split and the
  `vendor.nfc_model=ST54L` value are inferred from the shipped filenames.
* Whether `libnfc-nci-felica.conf` is selected by the same mechanism, or by a
  separate property.
* Whether the per-SKU permission gating in `device.mk` produces working NFC on an
  EEA/JPN/ROW/TUR unit.

Test on a non-IND device before trusting any of it.

## Needs verification: display brightness curve

`configs/display_id_frogger.xml` is stock's config, carried verbatim. Once the
device boots LineageOS, confirm the panel still resolves to
`local:4630947107087237506` via `dumpsys display`; if it resolves to
`4630947039571902850` or `…851` (the other two configs stock ships) the
`PRODUCT_COPY_FILES` destination filename must change to match.

## Needs verification: `spunvm`

Stock comments this mount out; the tree currently mounts it. See decision 5. If
first-stage mount fails, comment it out in `init/fstab.default`.

## Needs reconciling: 29 generic modules

`modules.load.*` was validated against every module Frogger actually ships
(465 `.ko` files across `vendor_boot`, `vendor_dlkm` and `system_dlkm`). All
Nothing- and device-specific mismatches are fixed (see changes.md). 29 generic
entries remain that Frogger's stock images do not contain:

```
9pnet.ko  9pnet_fd.ko  clk-scmi.ko  governor_gpubw_mon.ko
governor_msm_adreno_tz.ko  leds-qcom-lpg.ko  macsec.ko  ntfs3.ko
pps_core.ko  ptp.ko  ptp_kvm.ko  q2spi-geni.ko  qcom-amoled-regulator.ko
qcom_ice.ko  qrtr-tun.ko  qti_pmic_glink.ko  tls.ko  ucsi_qti_glink.ko
ufs-qcom.ko  usbmon.ko  vcpu_stall_detector.ko  virtio_*.ko (6)
vmw_vsock_virtio_transport.ko  xhci-sideband.ko
```

These are almost certainly fine: they are GKI/virtualisation modules or drivers
stock compiles **into** the kernel rather than shipping as modules, and Lineage's
own kernel config decides which get built. A few look like cross-branch renames —
stock has `pmic_glink.ko` / `ucsi_glink.ko` where the list wants
`qti_pmic_glink.ko` / `ucsi_qti_glink.ko`. None of this can be settled until
`kernel/nothing/sm7635` is synced and actually built; re-run the comparison then
and drop whatever the build does not produce.

## Lower priority

* **`ro.boot.pbid` removal.** `nothing-fwk`'s `NtFeaturesUtils` still reads
  `ro.vendor.nothing.feature.diff.plus.<device>` and `ro.boot.pbid`. Frogger has
  neither, so the code path is inert — harmless, but it could be dropped if this
  tree never gains a Pro variant.
* **`FroggerEuiccOverlay` device list.** `sim_slot_mappings_json` now lists
  `Frogger` with `esim-slot-ids:[1]`. eUICC permission ships only on JPN, so this
  is inert elsewhere, but it has not been tested on a JPN unit.
* **Blob list drift.** Validated against build `2603091830` images unioned with
  the live `2606301839` listing. Re-run `extract-files.py` and fix anything it
  reports.
* **Second touch panel driver.** Frogger ships `goodix_ts.ko` alongside
  `focaltech_fts.ko`, suggesting a dual-sourced touch panel. The reference unit
  is Focaltech (its sysfs exposes `fts_*` nodes), and `modules.load.vendor_dlkm`
  loads only `focaltech_fts.ko`. `goodix_ts.ko` was **not** added, because it
  would have to be built by `noth/touchscreen` and referencing a module the
  Lineage kernel does not produce would break module loading. Add it if a
  Goodix-panel unit turns up.
* **Verizon packages** (`MyVerizonServices`, `WfcActivation`, `VZWAPNLib`,
  `AppDirectedSMSService` and their permission XMLs) were dropped — they are in
  neither Frogger inventory. Restore only if a carrier build needs them.
