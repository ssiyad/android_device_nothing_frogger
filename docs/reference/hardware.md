# Hardware facts

## Identity

| Fact | Value |
|---|---|
| Codename | `Frogger` |
| Marketing name | Nothing Phone (4a) |
| Model | `A069` |
| Products | `FroggerEEA`, `FroggerIND`, `FroggerJPN`, `FroggerTUR` |
| Build | `2603091830`, `BQ2A.250913.001-BP2A.250605.031.A3` |
| Fingerprint | `Nothing/Frogger/Frogger:16/BQ2A.250913.001-BP2A.250605.031.A3/2603091830:user/release-keys` |
| Bootloader | `02032-LANAI-1` |
| Baseband | `00099-MILOS_GEN_PACK-1` |
| Boot / vendor SPL | 2026-01-05 / 2026-04-05 |

The fingerprint must match a real Nothing build. An invented-but-consistent value
is worse than a stale one.

## SoC

`ro.soc.model=SM7635`, `ro.board.platform=volcano`, soc_id 636,
`ro.product.cpu_info=7s Gen 4`, 8 GB RAM.

`ro.board.api_level=34` — the vendor partition is built at API 34 while the
system is Android 16.

## Display

```
Physical size     1224x2720
Physical density  480          (override 440)
uniqueId          local:4630947107087237506
```

Refresh rates 60 / 90 / 120 Hz, HDR max luminance 1600 nits, rounded corner
radius 162 px, centred punch-hole cutout at x≈612 y≈82 r≈34. Panel type
`Flexible_AMOLED`.

Panel is `nt37706a`, 120 Hz FHD+, in BOE and Visionox variants. The devicetree
default is `rm69220`; the bootloader selects the real panel by name at runtime,
so the default is not what matters.

Stock ships three candidate display configs. `4630947107087237506` is the one
this panel resolves to, and it is the only one carried.

## Fingerprint

Goodix optical UDFPS, `vendor/firmware/goodixfp64.*`.

```
udfpsBounds=Rect(509, 2382 - 715, 2588)
→ centre (612, 2485), radius 103
→ ro.vendor.fingerprint.sensor_location=612|2485|103
```

`dumpsys fingerprint` does not print the sensor location on this firmware, and
Nothing does not use AOSP's `config_udfpsProps`. The SystemUI service dump is the
only source without root.

## Touchscreen

Focaltech, at
`/sys/devices/platform/soc/ac0000.qcom,qupv3_0_geni_se/a80000.spi/spi_master/spi0/spi0.0`.

Panel firmware `focaltech_ts_fw_boe.bin` / `focaltech_ts_fw_vxn.bin`.

## Glyph

```
ro.vendor.glyph.channels=6
ro.vendor.glyph.row=6
ro.vendor.glyph.column=1
```

Driver `CONFIG_LEDS_AW20036_FROGGER`.

## Camera sensors

| Slot | CCI | csiphy | Part | Peripherals |
|---|---|---|---|---|
| cam-sensor0 | cci0 | 0 | S5KGN9 wide | eeprom, actuator, SOIS |
| cam-sensor1 | cci0 | 1 | S5KKD1 front | eeprom |
| cam-sensor2 | cci1 | 3 | IMX355 ultrawide | eeprom |
| cam-sensor3 | cci1 | 2 | S5KJN5 tele | eeprom, actuator |

All four probe on this IND unit, tele included, and the framework exposes five
cameras, four of them back-facing. The tele acquires, starts and streams.

Stock ships the same node set for both board-ids, so the node set is kept as-is.

### OIS

There is no `ois-src`, no `qcom,ois` and no `cam_ois` reference anywhere in the
devicetrees, so the Qualcomm OIS subdev is not in play on this device at all. The
only stabilisation path is Nothing's **SOIS** (sensor-shift), and it sits on the
**wide**, not the tele:

| Fact | Value |
|---|---|
| Node | `qcom,cam-sensor0` |
| Rail | `SGM_LDO6` — `nt_sois-supply` is its only consumer |
| Voltage | requested 3004000 µV; the LDO reports 2988000, which is its step, not a fault |
| Control | `/dev/nt_cam_dev`, ioctl `NT_DEV_CONTROL_CMD` |
| Masks | `enableCameraSOISMask=0x9`, `SOISOptimizationEnable=0x9` |

The masks are indexed by sensor slot, so `0x9` selects slots 0 and 3 — but slot 3
has no `nt_sois-supply`, so the driver skips it and only the wide is ever
powered. The OEM devicetree is identical here, so that is stock behaviour rather
than a porting gap.

Test OIS on the **main camera**. Testing the telephoto proves nothing about this
path.

Supplies come from two I2C LDO chips, **not** the PMIC:

| compatible | bus | rails |
|---|---|---|
| `nothing,sgm38120` | `qupv3_se7_i2c` | `SGM_LDO1..7` |
| `nothing,wr1241` | `qupv3_se7_i2c`, `qupv3_se1_i2c` | `WR_LDO1..4`, `WR_LDO*_UW` |

Module blobs are prefixed `frogger_`. Post-processing is Morpho EIS and ArcSoft.

## Media

`ro.media.xml_variant.codecs=_volcano_v0`, so
`media_profiles_volcano_v0_Base.xml` is the loaded profile and the
`extract-files.py` codec fixup targets `media_codecs_volcano_v0.xml`. On Frogger
the v0 and v1 Base profiles are byte-identical, but v0 is the one referenced.

## SKUs

| SKU | build prop | odm permissions | NFC |
|---|---|---|---|
| EEA | yes | yes | yes |
| IND | yes | **no** | **no** |
| JPN | yes | yes | yes + eSE + eUICC |
| TUR | yes | yes | yes |
| ROW | **no** | yes | yes |

`ROW` has permissions and a vintf manifest but no `build_ROW.prop`; `IND` is the
inverse. Both asymmetries are reproduced from stock.

## NFC

Chip is **ST54L** (`CONFIG_NFC_SE_STM_GPIO`). Config files are
`libnfc-hal-st.conf`, `libnfc-hal-st-st54l-felica.conf`, `st54l_conf.txt`,
`st54l_conf_felica.txt`, `st54l_fw.bin`.

NFC is SKU-gated through odm feature permissions rather than per-SKU vintf
manifests: LineageOS builds the HAL from `hardware/st/nfc`, whose soong module
installs its own vintf fragment declaring `INfc/default` unconditionally, so a
per-SKU manifest would double-declare it. `ODM_MANIFEST_SKUS := JPN` covers the
eSE alone.

The IND variant has no NFC hardware — no `android.hardware.nfc*` feature, no
`/dev/nq-nci`, no NFC HAL binary, and `vendor.nfc.config_file_name` unset.
