# Frogger hardware facts

Every device-specific value this tree relies on, with the command that produced
it. Values marked **same as Asteroids** were verified equal, not assumed.

## Identity

| Fact | Value | Source |
|---|---|---|
| Codename | `Frogger` | `ro.product.vendor.device` |
| Marketing name | Nothing Phone (4a) | `ro.product.brand_device_name` |
| Model | `A069` | `ro.product.vendor.model` |
| Product names | `FroggerEEA`, `FroggerIND`, `FroggerJPN`, `FroggerTUR` | `/odm/etc/build_*.prop` |
| Build used | `2603091830`, `BQ2A.250913.001-BP2A.250605.031.A3` | `ro.build.version.incremental` |
| Stock fingerprint | `Nothing/Frogger/Frogger:16/BQ2A.250913.001-BP2A.250605.031.A3/2603091830:user/release-keys` | verified against the stock B4.1-260309-1830 release, which is where our blobs come from |
| Bootloader | `02032-LANAI-1` | `ro.boot.bootloader` (Asteroids: `01955-LANAI-1`) |
| Baseband | `00099-MILOS_GEN_PACK-1` | `ro.boot.expect.baseband` (Asteroids: `00560.1-...`) |
| Boot SPL / Vendor SPL | 2026-01-05 / 2026-04-05 | `ro.vendor.boot_security_patch`, `ro.vendor.build.security_patch` — **same as Asteroids**, verified not assumed |

## SoC and platform — same as Asteroids

`ro.soc.model=SM7635`, `ro.board.platform=volcano`, `ro.product.cpu_info=7s Gen 4`,
`ro.board.api_level=34`, `ro.product.first_api_level=36`, 8 GB RAM
(`ro.boot.ddr_size=8192`). The whole Qualcomm side of `BoardConfig.mk` therefore
carries over unchanged.

## Display — **differs from Asteroids**

```
$ adb shell wm size
Physical size: 1224x2720            # Asteroids: 1080x2392
$ adb shell wm density
Physical density: 480               # Asteroids: 420
Override density: 440
$ adb shell dumpsys display | grep uniqueId
uniqueId="local:4630947107087237506"
```

* `TARGET_SCREEN_DENSITY := 480`
* Display config installs as `display_id_4630947107087237506.xml`
  (Asteroids used `display_id_4630946978939328130.xml`).
* Refresh rates 30 / 60 / 90 / 120 Hz, HDR max luminance 1600 nits,
  rounded corner radius 162 px, centred punch-hole cutout at x≈612 y≈82 r≈34.
* Panel is `Flexible_AMOLED` (`ro.vendor.display.panel.type`).

Stock ships three candidate display configs; `dumpsys` identified
`4630947107087237506` as the one this panel actually resolves to, so that is the
only one carried.

## Fingerprint — vendor same, location differs

Goodix optical UDFPS on both (`vendor/firmware/goodixfp64.*` present in both blob
sets). The sensor position had to be re-derived:

```
$ adb shell dumpsys activity service com.android.systemui/.SystemUIService | grep udfpsBounds
udfpsBounds=Rect(509, 2382 - 715, 2588)
```

→ centre (612, 2485), radius 103 → `ro.vendor.fingerprint.sensor_location=612|2485|103`
(Asteroids: `540|2176|92`). The ratios line up with the larger panel, which is a
good consistency check: 2485/2720 ≈ 0.914 vs 2176/2392 ≈ 0.910.

`dumpsys fingerprint` does *not* print the sensor location on this firmware, and
Nothing does not use AOSP's `config_udfpsProps` — no framework or SystemUI
overlay on the device defines it. The SystemUI dump above was the only way to
obtain it without root.

## Touchscreen — same as Asteroids

Focaltech, at the same SPI address:

```
/sys/devices/platform/soc/ac0000.qcom,qupv3_0_geni_se/a80000.spi/spi_master/spi0/spi0.0
```

`/proc/touchpanel/gesture_code` is present, so the `nothing_sensors` soong config
paths carry over. Panel firmware `focaltech_ts_fw_boe.bin` /
`focaltech_ts_fw_vxn.bin` (BOE and Visionox suppliers) — already in the blob list.

Note: the `fts_gesture_single_tap_pressed` / `fts_fod_pressed` sysfs nodes the
soong config points at do **not** exist on stock and are **not** in the OEM
kernel source — they are additions made by the LineageOS touchscreen driver in
`kernel/nothing/sm7635-modules`. See [open-items.md](open-items.md).

## Glyph — **differs from Asteroids**

```
ro.vendor.glyph.channels=6
ro.vendor.glyph.row=6
ro.vendor.glyph.column=1
```

Driver is `CONFIG_LEDS_AW20036_FROGGER` (Asteroids: `CONFIG_LEDS_AW20036`).
6 channels in a 6×1 layout is a different arrangement from the Phone (3a).

## Audio amplifier — **differs from Asteroids**

Frogger ships `vendor/firmware/aw882xx_acf.bin` and `vendor/bin/aw882xx_cali`
(Awinic AW882xx). Asteroids uses NXP TFA98xx (`tfa98xx.cnt`, `libtfadsp_rx.so`,
`libtfadsp_tx.so`) — none of which exist on Frogger.

## Camera — **differs from Asteroids**

Sensor complement, from `vendor/lib64/camera/com.qti.sensor.*`:

| Role | Frogger | Asteroids |
|---|---|---|
| Wide/main | `s5kgn9` (+ `_v2`) | `s5kgn9` / `s5kgnj` |
| Tele | `s5kjn5` (+ `_v2`, `_v3`) | `imx882` / `s5kjn5` |
| Ultrawide | `imx355` | `imx355` |
| Front | `s5kkd1` | `s5kjn1` / `s5kkd1` |

Module names are prefixed `frogger_` rather than Asteroids' `arcanine_`, so every
sensor/eeprom/sensormodule/tuned blob path changed. The `_v2`/`_v3` suffixes are
supplier variants, all carried.

Post-processing stack differs too: Frogger uses Morpho EIS and ArcSoft
(`com.morpho.node.*`, `com.arcsoft.node.*`); Asteroids' Vidhance and ArcSoft SAT
components (`com.vidhance.node.*`, `com.anc.node.sat.so`, `libvidhance.so`,
`libwa_sat.so`, `vidhance.lic`) are absent on Frogger.

## NFC — **differs from Asteroids**

* Chip is **ST54L** (`CONFIG_NFC_SE_STM_GPIO`, kernel comment `#add ST54L spi`).
  Config files are `libnfc-hal-st.conf`, `libnfc-hal-st-st54l-felica.conf`,
  `st54l_conf.txt`, `st54l_conf_felica.txt`, `st54l_fw.bin`. Asteroids' ST21/ST54J
  split (`libnfc-hal-st21-BASE/PRO`, `libnfc-hal-st54j-JPN/PRO`) does not apply.
* NFC is **SKU-gated**. Stock ships odm permissions only for
  `sku_EEA`, `sku_JPN`, `sku_ROW`, `sku_TUR`. There is **no `sku_IND`**.
* Verified on the reference IND unit:

```
$ adb shell pm list features | grep -i nfc      # no output
$ adb shell ls /odm/etc/permissions/
sku_EEA  sku_JPN  sku_ROW  sku_TUR
```

The IND variant has no NFC hardware — the `1-0008/hw_version` i2c node Asteroids
probes does not exist, and `vendor.nfc.config_file_name` is unset.

## Media — **differs from Asteroids**

```
$ adb shell getprop ro.media.xml_variant.codecs
_volcano_v0                          # Asteroids: _volcano_v1
```

So `media_profiles_volcano_v0_Base.xml` is the profile actually loaded, and the
`extract-files.py` codec fixup targets `media_codecs_volcano_v0.xml`. On Frogger
the v0 and v1 Base profiles are byte-identical (`md5 1f45ab57…`), but v0 is the
one referenced.

## Partitions / fstab

Layout matches Asteroids with one difference: stock Frogger has `spunvm`
**commented out** in `fstab.default` even though the partition exists
(`/dev/block/by-name/spunvm -> sde75`). See [decisions.md](decisions.md).

## SKUs

| SKU | build prop | odm permissions | NFC |
|---|---|---|---|
| EEA | yes | yes | yes |
| IND | yes | **no** | **no** |
| JPN | yes | yes | yes + eSE + eUICC |
| TUR | yes | yes | yes |
| ROW | **no** | yes | yes |

`ROW` has permissions and a vintf manifest but no `build_ROW.prop`; `IND` is the
inverse. Both asymmetries are reproduced from stock rather than smoothed over.
