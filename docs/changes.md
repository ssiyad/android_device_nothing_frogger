# Per-file change log

Baseline: `device/nothing/asteroids` at commit `6d3840a`
("Revert asteroids: Set config_vibration_supported_intensity_levels").

## Renames

All done with `git mv` so history follows:

| From | To |
|---|---|
| `lineage_asteroids.mk` | `lineage_frogger.mk` |
| `init/init.asteroids.hw.rc` | `init/init.frogger.hw.rc` |
| `init/init.asteroids.nfc.sh` | `init/init.frogger.nfc.sh` |
| `init/init.asteroids.rc` | `init/init.frogger.rc` |
| `init/ueventd.asteroids.rc` | `init/ueventd.frogger.rc` |
| `vintf/manifest_asteroids.xml` | `vintf/manifest_frogger.xml` |
| `configs/display_id_asteroids.xml` | `configs/display_id_frogger.xml` |
| `rro_overlays/Asteroids*Overlay/` | `rro_overlays/Frogger*Overlay/` |

Then a tree-wide `Asteroids`→`Frogger` / `asteroids`→`frogger` substitution over
every remaining reference (soong module names, RRO package names, `DEVICE_PATH`,
`vendor/nothing/*` paths, sepolicy `file_contexts`, init service names). Only
`README.md` and the blob list were excluded and handled by hand.

## Deletions (Frogger-only scope)

* `rro_overlays/AsteroidsProSettingsProviderOverlay/`
* `rro_overlays/AsteroidsProWifiOverlay/`
* `sku/build_Pro{EEA,IND,ROW,TUR}.prop`
* `configs/media/media_profiles_volcano_v1_{Base,Pro}.xml`
* `configs/nfc/libnfc-{hal-st21-BASE,hal-st21-PRO,hal-st54j-JPN,hal-st54j-PRO,nci,nci-JPN}.conf`
* `device.mk`: `AsteroidsPro{MainlineWifi,SettingsProvider,Wifi}Overlay` packages

## New / replaced files

* `configs/display_id_frogger.xml` — replaced with stock Frogger's config for
  display `4630947107087237506`, pulled from the device.
* `configs/media/media_profiles_volcano_v0_Base.xml` — pulled from the device.
* `configs/nfc/{libnfc-hal-st.conf, libnfc-hal-st-st54l-felica.conf,
  libnfc-nci.conf, libnfc-nci-felica.conf, st21nfc_conf_base.txt}` — pulled from
  the device.
* `docs/` — this directory.

## Content edits

### `lineage_frogger.mk`
* `PRODUCT_MODEL` `A059` → `A069`
* `BuildDesc` / `BuildFingerprint` → the live `2606301839` build

### `BoardConfig.mk`
* `TARGET_SCREEN_DENSITY` `420` → `480`
* `TARGET_KERNEL_CONFIG`: `vendor/asteroids_perf.config` →
  `vendor/frogger_perf.config` (assumption — see open-items.md)
* `ODM_MANIFEST_SKUS` left at `JPN`, with a comment explaining why NFC is *not*
  gated here (see decisions.md)

### `android-info.txt`
* `version-bootloader` `01955-LANAI-1` → `02032-LANAI-1`
* `version-baseband` `00560.1-MILOS_GEN_PACK-1` → `00099-MILOS_GEN_PACK-1`

### `device.mk`
* Display config target filename → `display_id_4630947107087237506.xml`
* Media profile → `media_profiles_volcano_v0_Base.xml` only
* NFC config file list → the ST54L set
* NFC feature permissions moved from unconditional `vendor/etc/permissions/` to
  per-SKU `odm/etc/permissions/sku_{EEA,JPN,ROW,TUR}/`, via a new
  `frogger-nfc-perms` helper
* eUICC permission reduced to `sku_JPN` only
* SKU prop copies reduced to EEA/IND/JPN/TUR
* Glyph package `ParanoidGlyphPhone3a` → `ParanoidGlyphPhone4a`

### `odm.prop`
NT feature masks replaced with the live device's values:

```
ro.vendor.nothing.feature.base=0xe24800001004458438124a040106b4247b97ffL
ro.vendor.nothing.feature.diff.device.Frogger=0x2141269afafc0124c028d140ad04002d0842080L
```

The `diff.plus.*` line was **removed** — only Asteroids has a "plus" (pbid)
variant; Frogger has neither the mask nor `ro.boot.pbid`.

### `vendor.prop`
* `ro.vendor.fingerprint.sensor_location` `540|2176|92` → `612|2485|103`

### `sku/build_{EEA,IND,JPN,TUR}.prop`
Regenerated: Frogger fingerprints, `ro.product.odm.model=A069`, and each SKU's
`ro.vendor.nothing.feature.diff.product.Frogger<SKU>` mask from the live device.

### `vintf/manifest_JPN.xml`
Secure element reduced to `ISecureElement/eSE1`. Asteroids also declared
`SIM1`/`SIM2`; stock Frogger's JPN manifest declares only `eSE1`.

### `init/init.frogger.nfc.sh`
Rewritten. The Asteroids version probes `1-0008/hw_version` for ST21 vs ST54 and
branches on `ro.boot.pbid` (Base/Pro). Frogger has neither, and only ever ships
an ST54L, so the script reduces to a JPN/non-JPN FeliCa choice.

### `extract-files.py`
* Module name `asteroids` → `frogger`
* Codec blob fixup retargeted from `media_codecs_volcano_v1.xml` to
  `media_codecs_volcano_v0.xml`

### `modules.load.vendor_dlkm`

Validated against every module Frogger ships (465 `.ko` across `vendor_boot`,
`vendor_dlkm`, `system_dlkm`). The Nothing-specific entries map 1:1 onto the
OEM Kconfig diff between `Asteroids.config` and `Frogger.config`:

| Asteroids | Frogger | Kconfig |
|---|---|---|
| `hwid.ko` | `hardware_id.ko` | `CONFIG_HWID` → `CONFIG_NOTH_HARDWARE_ID` |
| `cable_detect.ko` | `cable_state.ko` | `CONFIG_NT_ANT_CABLE_DET` → `CONFIG_OEM_CABLE` |
| `st54spi.ko` | `st54spi_gpio.ko` | `CONFIG_NFC_SE_STM` → `CONFIG_NFC_SE_STM_GPIO` |
| `slot_detect.ko` | *(removed)* | `CONFIG_NT_SIM_SLOT_DET` is Asteroids-only |
| `ois_vdd_ctrl.ko` | *(removed)* | `CONFIG_NT_OIS_VDD_CTRL` is Asteroids-only |

Added: `bootinfo.ko`, `hwinfo.ko`, `errcode.ko` (`CONFIG_OEM_{BOOTINFO,HWINFO,ERRCODE}`),
`rpmb_state.ko`, `secure_state.ko` (`CONFIG_NT_{RPMB,SECURE}_STATE`), and
`aw882xx_dlkm.ko` for the amplifier.

`tfa98xx_dlkm.ko` was **kept** — Frogger ships and loads it even though the
amplifier is AW882xx; the QCOM audio-kernel builds all codec modules and binding
happens by devicetree match. 327 → 330 entries, file re-sorted (it is
alphabetical; modprobe resolves real load order via `modules.dep`).

### `device.mk` — Glyph disabled

`ParanoidGlyphPhone4a` / `GlyphAdapter` and their soong namespaces are commented
out so the tree builds. See open-items.md.

### `README.md`
Rewritten for the Phone (4a), listing only specifications read off the device.

### `proprietary-files.txt`
See [decisions.md](decisions.md) for method. Net: 1650 → 1624 entries, all of
which now resolve against Frogger. 60 Asteroids-only entries removed, 34 Frogger
entries added.
