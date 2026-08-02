# Open items

Things this tree assumes, and work it depends on that lives outside it. Roughly
in the order they will block a first boot.

## Blocking: repositories that do not exist yet

| Path | Referenced by | Status |
|---|---|---|
| `vendor/nothing/frogger` | `device.mk`, `BoardConfig.mk`, `Android.bp`, `qcril-database/Android.bp` | produced by `extract-files.py` |
| `kernel/nothing/sm7635` | `TARGET_KERNEL_SOURCE`, soong namespace | **symlinked** to the OEM checkout |
| `kernel/nothing/sm7635-modules` | `TARGET_KERNEL_EXT_MODULE_ROOT` | **composed from symlinks** |
| `hardware/nothing` | `include hardware/nothing/config.mk` | needs syncing; not present |

`hardware/st` **is** present, so the NFC and secure element HALs are available.

### Kernel wiring (local symlinks, not repo-managed)

`~/sources/android/kernels/frogger` is `NothingOSS/android_kernel_msm-6.1_nothing_sm7635`
checked out on branch **`sm7635/b/mr_Frogger`** — the real Frogger branch, so
`build.config.nothing.Frogger` and `vendor/Frogger.config` come from it.

The OEM keeps external modules *inside* the kernel repo under `vendor/`, while
LineageOS expects a separate root with a `noth/` namespace. These symlinks bridge
that:

```
kernel/nothing/sm7635                        -> ~/sources/android/kernels/frogger
kernel/nothing/sm7635-modules/qcom           -> .../frogger/vendor/qcom
kernel/nothing/sm7635-modules/noth/fingerprint -> .../vendor/qcom/opensource/fingerprint
kernel/nothing/sm7635-modules/noth/touchscreen -> .../vendor/qcom/opensource/touch-drivers
```

These are **local symlinks outside git** — fine for building here, but they
should become proper repos (or `.repo/local_manifests` entries) before this is
shareable.

24 of the 25 `TARGET_KERNEL_EXT_MODULES` paths and all three
`TARGET_KERNEL_CONFIG` fragments now resolve.

**Note on device trees:** there is no Frogger-named DTS. Nothing differentiates
Frogger from Asteroids by compile-time define (`-DNT_PROJECT=Frogger` plus
`CONFIG_NOTHING_IS_FROGGER`) over the shared `volcano-qrd*` device trees, which
do carry Nothing nodes (`nothing,bootloader_log`, `nothing,secure_element`).
`TARGET_MERGE_DTBS_WILDCARD := *volcano*` is therefore correct as-is.

### The one real kernel gap: `.qca6750`

`TARGET_KERNEL_EXT_MODULES` contains
`qcom/opensource/wlan/qcacld-3.0/.qca6750`, and Lineage's `kernel.mk` uses each
entry as a literal directory (`make -C $(ROOT)/$(entry) M=...`). The OEM
`qcacld-3.0` has **no such subdirectory** — it builds its per-chipset variants
through **bazel** instead (`wlan_qcacld3_modules.bzl`,
`_define_module_for_target_variant_chipset`), which Lineage's Kbuild-based kernel
build does not invoke.

This matters: `modules.load.vendor_dlkm` expects `qca_cld3_qca6750.ko`, and
Frogger does ship exactly that (QCA6750 / WCN6750_V2, consistent with
`persist.vendor.wlan.firmware.version`).

No shim was invented for this, because guessing the CONFIG defines the bazel
target passes would quietly produce a wrong Wi-Fi module. Options, in order of
preference:

1. Fork the kernel properly and port the `.qca6750` Kbuild shim from LineageOS'
   `android_kernel_nothing_sm7635-modules` (or upstream qcacld-3.0, which has
   these variant directories for Kbuild builds).
2. Build wlan out-of-band with bazel and drop it in as a prebuilt.

This is the clearest evidence that symlinking the OEM tree is a stopgap, not a
substitute for a LineageOS kernel fork.

## Resolved: kernel defconfig fragments

`BoardConfig.mk` now asks for `gki_defconfig`, `vendor/pineapple_GKI.config` and
`vendor/Frogger.config`, all three of which exist on the `sm7635/b/mr_Frogger`
branch.

This was traced through the OEM's own chain rather than guessed:
`build.config.msm.gki` applies `vendor/${MSM_ARCH}_GKI.config` for the
production variant (consolidate is the debug one), and
`build.config.nothing.Frogger` (`TARGET_PRODUCT=Frogger`, `NT_PROJECT=Frogger`,
`-DNT_PROJECT=Frogger`) then layers `arch/arm64/configs/vendor/Frogger.config`:

```
CONFIG_NOTHING_IS_FROGGER=y
CONFIG_LEDS_AW20036_FROGGER=m
CONFIG_OEM_BOOTINFO=m   CONFIG_OEM_HWINFO=m   CONFIG_OEM_ERRCODE=m
CONFIG_NOTH_HARDWARE_ID=m   CONFIG_OEM_CABLE=m
CONFIG_NFC_SE_STM_GPIO=m    # ST54L
CONFIG_NT_SECURE_STATE=m    CONFIG_NT_RPMB_STATE=m
```

The OEM tree has **no** `*_perf.config` fragments at all — Asteroids'
`vendor/{pineapple,asteroids}_perf.config` are the LineageOS fork's renames of
`pineapple_GKI.config` / `Asteroids.config`. If this ever builds against a
Lineage kernel fork rather than the OEM tree, switch `TARGET_KERNEL_CONFIG` back
to the `*_perf.config` names.

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

## Audio: rebased on Frogger stock (needs on-device verification)

All six `audio/*.xml` were Asteroids' and wrong for Frogger. They have now been
replaced with Frogger's stock files, with the Lineage delta re-applied
selectively rather than wholesale.

The core problem: Asteroids' `mixer_paths_volcano_qrd.xml` drove an NXP TFA98xx
smart amp (9 × `TFA`, 9 × `TFA_CHIP_SELECTOR`). Frogger's has **zero** TFA
references and uses the Qualcomm path (`WSA2`, `WCD9378` codec,
`SpkrLeft`/`Spkr2Right` COMP/CPS/PBR/VISENSE), matching the blob-level finding
that Frogger ships `aw882xx_acf.bin` where Asteroids ships `tfa98xx.cnt`.

The Lineage delta was isolated from git history and evaluated per commit:

| Lineage commit | Ported? | Why |
|---|---|---|
| `0387e40` mixer_paths import | n/a | no edits on top of stock — replaced outright |
| `8eba5bf` nuke haptics output | **no** | Frogger's stock policy has no haptics output |
| `b77bc2f` drop `bluetooth_qti` | **no** | not present in Frogger's stock policy |
| `4284c18` trim A2DP formats | **no** | Frogger's stock already lists only SBC/AAC/APTX/APTX_HD/LDAC |
| `932734d` enable LVACFS mic | **yes** | LVACFS blobs confirmed present on Frogger (31 entries) |
| `1df56d0` disable speaker protection | **no** — see below | |
| `6a1be28` dolby effects | **no** | Frogger has **zero** Dolby files anywhere |

### Two things to check on first boot

**Speaker protection.** Frogger's stock sets `speaker_protection_enabled=1` and
its mixer paths do expose `SpkrLeft/Spkr2Right VISENSE` controls, so V/I sense is
genuinely wired — unlike Asteroids, where Lineage turned it off. Stock's `1` was
kept. If speaker playback is silent or distorted, flipping it to `0` in
`audio/resourcemanager_volcano_qrd.xml` is the first thing to try.

**Dolby.** `device.mk` still has
`$(call inherit-product-if-exists, hardware/dolby/dolby.mk)`. It is an
`-if-exists` call and Frogger has no Dolby, so it is inert — but it can be
dropped if it ever resolves to something.

## Verified fine: vibrator

`/dev/aac_richtap` exists on Frogger (`crw-rw-rw- system system 10, 114`), so
`vendor.vibrator.device` and the `android.hardware.vibrator.service.nothing-rt_ics`
HAL carry over unchanged. `100_Haptic.bin` / `101_Haptic.bin` / `libics_haptic.so`
are in both blob sets.

## NFC: removed, not supported

NFC support has been **deleted** from this tree, not deferred. Removed:
`configs/nfc/`, `init/init.frogger.nfc.sh` (+ its `sh_binary` and `PRODUCT_PACKAGES`
entry), the `frogger_nfc_detect` service and property triggers in
`init.frogger.rc`, the `android.hardware.nfc-service.st` package, the per-SKU NFC
permission gating, the NFC `file_contexts` / `property_contexts` / `.te` rules
(including `sepolicy/vendor/vendor_qti_init_shell.te`, which existed only for
NFC), the `# NFC` blob-list section, and `st21nfc.ko` from
`modules.load.vendor_dlkm`.

Left alone deliberately: NFC lines in `init/init.qcom.rc` and
`init/ueventd.qcom.rc` (upstream QCOM boilerplate, inert without a HAL), the
`nfc` group in `init.frogger.hw.rc`, and `nfc.ko` in `modules.load.system_dlkm`
(a standard GKI module).

The embedded secure element is **not** affected —
`android.hardware.secure_element-service.thales` and the JPN `eSE1` odm manifest
remain.

To restore NFC later, `git revert` this change: Frogger's ST54L config files, the
per-SKU gating design and the notes on what was unverified are all in the history.

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
* **Blob list drift.** All 1616 entries resolve against the `2603091830` factory
  images alone, so no live-device files are needed to extract. (An earlier note
  claimed three `system/` files were missing from the dump — that was a path bug
  in the checker: `system.img` is system-as-root, so `system/<x>` lives at
  `<dump>/system/system/<x>`, not `<dump>/system/<x>`.) The dump is one build
  older than the phone, so re-run `extract-files.py` and check its output.
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
