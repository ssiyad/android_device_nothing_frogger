# Camera

Everything learned bringing the Frogger camera up, in the order the layers
failed. Written so that picking this up cold does not mean rediscovering it.

**Status as of 2026-08-05 — parked.** Five sensors enumerate, flash and torch
work, pipelines build. The camera app still cannot open a capture session. Four
distinct defects were found and fixed on the way; two known gaps remain, both
identified and neither yet closed.

**Disabling SOIS did not help — tested 2026-08-05.** With
`enableCameraSOISMask=0x0` and `SOISOptimizationEnable=0x0` on the device, opening
the camera still produces the identical crash:

```
#00 __cfi_slowpath+28                     libdl.so
#01 ChiSWPNCNode::~ChiSWPNCNode()+164     com.qti.node.swpnc.so
#03 SWPNCNodeCreate                       → SIGSEGV
```

So `swpnc` fails in `LoadLib()` regardless of the SOIS mask. The mask gates *use*
of OIS data, not the node's initialisation. **This makes the SOIS kernel port the
wrong next step** — it was the larger of the two remaining jobs and it would not
have fixed this. The earlier note here said the opposite; it was wrong.

**Where to start next.** `camxchinodeswpnc.cpp:512 LoadLib()` opens something that
is not in the blob's `DT_NEEDED` — all fifteen of those are present and correctly
labelled `vendor_file`. Find what it actually opens before shipping anything else:
`camxoverridesettings.txt` is on the device now and can raise CamX logging
(`chiLogInfoMask`, commented out in stock) to make the HAL name the file. That is
cheaper than another round of blob diffing. The Morpho node conflict (gap B) is
also still unresolved and is required by a preview pipeline.

---

## Hardware

| Slot | CCI | csiphy | Part | Peripherals |
|---|---|---|---|---|
| cam-sensor0 | cci0 | 0 | S5KGN9 wide | eeprom, actuator |
| cam-sensor1 | cci0 | 1 | S5KKD1 front | eeprom |
| cam-sensor2 | cci1 | 2 | IMX355 ultrawide | eeprom |
| cam-sensor3 | cci1 | 3 | S5KJN5 tele | eeprom, actuator, OIS |

The tele is a Phone (4a) Pro part and is absent on a plain Frogger. Stock ships
the same node set for both board-ids and lets the sensor fail to probe, so the
node is kept rather than stripped.

Supplies come from two I2C LDO chips, **not** the PMIC:

| compatible | bus | rails |
|---|---|---|
| `nothing,sgm38120` | `qupv3_se7_i2c` | `SGM_LDO1..7` |
| `nothing,wr1241` | `qupv3_se7_i2c`, `qupv3_se1_i2c` | `WR_LDO1..4`, `WR_LDO*_UW` |

---

## What was wrong, in the order it surfaced

### 1. Camera was disabled on a bad bisect

`95d70251` disabled the camera overlay after a hardware bisect showed our dtbo
would not boot. The bisect was right that the dtbo was at fault and wrong about
which part — the real defect was the missing panel topology, fixed later in
`ed86e855`. Camera was never the boot blocker.

That commit also recorded a premise that does not hold: that the base
`volcano.dtb` has no camera block, so sensor nodes cannot resolve `&cam_cci0/1`
without dragging `qcom/camera/volcano-camera.dtsi` into the overlay.

**The truth:** the merge script assembles the base as `volcano.dtb` **plus its
platform dtbos**, `volcano-camera.dtbo` among them. The result exports 1330
symbols including `cam_cci0`, `cam_cci1`, `cam_csiphy0-3`, `camcc`,
`cam_cc_camss_top_gdsc`, the twelve `cam_sensor_*` pinctrl entries and the
`pmxr2230` flash/torch/switch labels. An overlay resolves them through
`__fixups__`, exactly as stock's sensors-only overlay does.

Fixed in devicetrees `b1576f21` — sensor include only. **Do not re-add the
platform block.**

Two build breaks followed, both mine:
- `CAM_CC_MCLK0..3_CLK` undefined. That header arrives via `volcano.dtsi`, but
  this overlay includes `volcano-qrd.dtsi`, which does not pull it in. Fixed by
  including `qcom,camcc-volcano.h` directly, as QCOM's standalone camera `.dts`
  files do (`4a6fdedc`).
- `SGM_LDO*/WR_LDO*` written inside a C block comment. The `*/` terminated the
  comment and the rest was parsed as device tree source (`25582e45`).

### 2. Regulator drivers did not exist

Sensors applied, `status=ok`, correct compatible, platform devices created — and
never probed:

```
/sys/bus/platform/devices/…:qcom,cam-sensor0/waiting_for_supplier
```

No driver in the tree bound `nothing,sgm38120` or `nothing,wr1241`, so the
regulators never registered and `fw_devlink` blocked probe forever. Nothing
reached the media graph, CamX found no hardware in `HwInterface::Create()`,
dereferenced NULL inside a static initialiser while dlopening `camera.qcom.so`,
and the provider died on a five-second restart loop.

Ported from the OEM 6.1 kernel in `sm7635` `74498e7feb26`. One API change for
6.6: `i2c_driver.probe` lost its second argument. Upstream's Kconfig gates both
on `MFD_I2C_PMIC`, which neither driver uses — they are plain `module_i2c_driver`
users of regmap and the regulator API — so ours depend on `I2C` and select
`REGMAP_I2C`.

**Result:** 5 camera devices, 25 v4l2 subdevs, flash and torch working.

### 3. Media profiles were the generic ones

```
E CapabilitiesByQuality: No supported EncoderProfiles
W EncoderProfilesResolver: doesn't contain any supported Quality
```

`init.frogger.rc` set the variant to `"_volcano_v1_${ro.boot.pbid}"` — Asteroids'
way of choosing `_Base` or `_Pro`. Frogger has no `ro.boot.pbid`, so init could
not expand the reference and **skipped the setprop entirely**, leaving the
property empty and the framework falling back to `media_profiles.xml`.

That fallback is QCOM's generic file. For cameraId 0 it declares `480p 720p
1080p cif qvga` and **no `low`, no `high`**, and describes six cameras where this
device has five. `CamcorderProfile` requires `low` and `high`, so every lookup
came back empty and CameraX would not bind a video use case.

Fixed in `291e42b`: extract `media_profiles_volcano_v1.xml` (declares `low high
qcif qvga vga 480p 720p 1080p 2160p` across cameraId 0–4) and set the variant to
`_volcano_v1`, consistent with the codec variant already in use.

### 4. Camera blobs were incomplete

Pipeline creation failed on a node that was never extracted:

```
Failed to load Chi interface for com.qti.node.swpnc
CreateNode() Node type 255 is not supported or created
Pipeline[RealTimeFeatureZSLPreviewRaw_0_cam_0] Initialize failed!
Camera3-Device: Unable to configure streams with HAL: Function not implemented (-38)
```

Comparing every camera directory against stock — rather than chasing one name at
a time — found the real extent:

| Path | stock | ours | missing |
|---|---|---|---|
| `vendor/etc/camera` | 41 | 18 | **23** |
| `vendor/lib64/camera` | 56 | 51 | 5 |
| `vendor/lib64/camera/components` | 82 | 77 | 5 |

Missing config included `camxoverridesettings.txt`, which CamX reads at startup,
every Nothing `sdk_params_*.json`, `ntcamperflocksettings.json` and
`camera_temp_power_ctrl.json`. Fixed in `2dbf3c1`, `c7b6801` and `2cc3744`. Both
directories now match stock exactly.

**Lesson:** the blob list was inherited from Asteroids and never reconciled
against Frogger. Diff whole directories, not individual filenames.

---

## Open gaps

### A. SOIS — kernel driver not ported

Extracting `camxoverridesettings.txt` enabled Nothing's sensor-OIS path:

```
enableCameraSOISMask=0x9   SOISOptimizationEnable=0x9   SOISEarlyPower=0:1|4:1
```

SOIS talks to `/dev/nt_cam_dev`, which does not exist here:

```
OpenSOIS() Open failed for Device /dev/nt_cam_dev — No such file or directory
camxchinodeswpnc.cpp:512 LoadLib() Unable to open library
  #00 __cfi_slowpath+28                  libdl.so
  #01 ChiSWPNCNode::~ChiSWPNCNode()+164  com.qti.node.swpnc.so
  #03 SWPNCNodeCreate                    → SIGSEGV → Broken pipe (-32)
```

`com.qti.node.swpnc` is an OIS consumer — its error strings are `Unable to get
OIS data`, `Cannot get Ois interval`, `Unable to get ois sample from iterator`.
With the device node absent it fails to initialise and takes the provider down.

Worked around in `066a32c` by forcing both masks to `0x0` with a `regex_replace`
blob fixup. **This is a workaround, not a fix.**

**To do it properly.** `/dev/nt_cam_dev` is created by `cam_sensor_nothing.c` —
the *only* file our camera-kernel lacks; 561 of 563 sources match the OEM tree.
The port is bounded and every addition is marked `// xft add for nothing custom`:

| File | Change |
|---|---|
| `cam_sensor_nothing.c` | new, 633 lines |
| `cam_sensor_nothing.h` | new, 103 lines |
| `Kbuild` | one line, `cam_sensor_nothing.o` after `cam_sensor_soc.o` |
| `cam_sensor_dev.c` | `#include`, then `cam_nt_driver_init()` |
| `cam_sensor_soc.c` | `#include`, then `cam_nt_get_ois_power(s_ctrl)` |
| `cam_sensor_dev.h` | five `extern_*` fields for extern i2c probe |
| `cam_sensor_core.c` | `#include`, `cam_nt_driver_errcode()` and `cam_nt_sctrl_save()` at probe/power/init/i2c error sites |

**Risk:** `cam_sensor_core.c` sits on the sensor probe path that currently works.
Five sensors enumerate today. Do this deliberately, not while chasing something
else, and verify probe still succeeds afterwards.

Source: `~/sources/android/kernels/frogger`, OEM 6.1 tree, path
`vendor/qcom/opensource/camera-kernel/drivers/cam_sensor_module/cam_sensor/`.

### B. Morpho nodes — deferred, and that was wrong

`com.morpho.node.eisv2`, `eisv3`, `gme` and `libmorpho_video_stabilizer.so` are
in stock and not shipped. Adding them breaks the Soong bootstrap:

```
module "com.morpho.node.eisv2": depends on multiple versions of the same aidl_interface
  via libcommonchiutils             → android.hardware.graphics.allocator-V1-ndk
  via libmorpho_video_stabilizer → libui → android.hardware.graphics.allocator-V2-ndk
```

They were deferred in `c7b6801` on the reasoning that they are video
stabilisation and not on the preview path. **That reasoning was wrong.** The log
shows:

```
Failed to load Chi interface for com.morpho.node.gme
Pipeline[MultiCameraBayerSATNoBPSFrogger0_0_cam_2] Initialize failed!
```

`MultiCameraBayerSATNoBPSFrogger` is the multi-camera SAT pipeline — preview.

**To fix:** break one edge of the version conflict. Both `allocator-V1-ndk` and
`libui` exist in `/vendor/lib64` at runtime, so removing either from the
generated `shared_libs` works on device. The obstacle is that `lib_fixups` keys
on library *name* and applies globally, so `lib_fixup_remove` on `libui` would
alter every blob linking it. Look for a per-blob mechanism in `extract_utils`
before reaching for the global one.

---

## Diagnosing this

The camera stack fails in layers and each layer names its own failure. Do not
infer — read the log and let it name the thing.

```sh
adb logcat -c && adb logcat -b crash -c
# open the camera, let it fail
adb logcat -b crash -d | grep -E "Executable|signal|#0[0-6] pc"
adb logcat -d | grep -E " E (CamX|ChiX)" | grep -viE "PreLoadLiberary|PopulateFuseId"
```

`PreLoadLiberary` and `PopulateFuseId` are logged at ERROR and are noise —
filter them or they drown everything.

**Reading the framework error tells you which layer:**

| Framework error | Means |
|---|---|
| `Number of camera devices: 0` | sensors did not probe — kernel/DT/regulator |
| `Function not implemented (-38)` | HAL rejected the config — usually a missing node or blob |
| `Broken pipe (-32)` | HAL **died** — get the tombstone, it names the library |

**State checks, cheapest first:**

```sh
adb shell 'lsmod | grep -E "sgm38120|wr1241"'
adb shell 'cat /sys/class/regulator/*/name | grep -E "SGM|WR_"'
adb shell 'ls /sys/bus/platform/devices/*cam-sensor0/waiting_for_supplier'   # should not exist
adb shell 'ls /dev/v4l-subdev* | wc -l'                                      # expect 25
adb shell 'dumpsys media.camera | grep "Number of camera"'                   # expect 5
adb shell 'ls /sys/bus/platform/drivers/qcom,camera/'                        # sensors bound here
```

**dmesg rotates in about four minutes on this device.** Capture kernel logs
promptly after boot or they are gone.

**Sensor nodes live under the CCI nodes, not `/soc`:**

```sh
adb shell "ls '/sys/firmware/devicetree/base/soc/qcom,cci0@ac15000/'"
```

Looking under `/soc/qcom,cam-sensor*` finds nothing and looks like the overlay
failed to apply. It did not.

---

## Traps

**Whitespace in analysis scripts.** `tr -d ' ='` does not strip tabs. This bug
appeared three times: once in the earlier dt-diff work, twice while computing
which device-tree labels were missing — first making 38 labels look absent, then
11, when the true answer was zero. Use `sed -E 's/^[[:space:]]*//'`.

**Two dtbo paths exist.** `out/…/obj/KERNEL_OBJ/…/frogger-base-overlay.dtbo` is a
leftover from an older build layout and is never regenerated. The live one is
`out/…/obj/DTB_OBJ/base/frogger-base-overlay.dtbo`. Decompiling the stale one
shows changes as absent when they landed fine.

**`mka dtboimage` does not rebuild the kernel device tree** from a `.dtsi`
change on its own — check the timestamp of the blob you inspect.

**`PRODUCT_SHIPPING_API_LEVEL` is a compliance switch, not a property.** Raising
it to 36 to match stock enabled Android 16's 16KB page-size check (rejecting 4KB
prebuilts on a `CONFIG_ARM64_4K_PAGES` kernel) and made `host_init_verifier`
fatal on stock init scripts. Two build cycles lost. It stays at 35.

---

# Port details and historical findings

Migrated from `open-items.md`. Background for the device-tree work above —
how the sensor nodes were derived and what collided along the way.

## Camera — port details (retained for when it is re-enabled)

**Done and building** (devicetrees `142e4e1e`, device `6260e88`). The sensor
device tree is in, `mka dtboimage` succeeds, and the built `dtbo.img` carries
4 sensors with eeprom/actuator/flash children. The csiphy mapping matches stock
exactly:

| Node | csiphy | ours | stock |
|---|---|---|---|
| `qcom,cam-sensor0` | 0 | ✓ | ✓ |
| `qcom,cam-sensor1` | 1 | ✓ | ✓ |
| `qcom,cam-sensor2` | 3 | ✓ | ✓ |
| `qcom,cam-sensor3` | 2 | ✓ | ✓ |

Nothing here has been exercised on hardware — camera may still be broken for
reasons the device tree cannot show.

### One structural difference from stock, deliberate

Stock's `entry.66` contains the **sensors only** — no `qcom,cci*` or
`qcom,csiphy*` nodes — because its base dtb already carries the camera platform.
**Ours does not:** the built `volcano.dtb` here has no `cam_cci0/1`, no csiphy
and no `cam_sensor_*` pinctrl (only `camcc`), verified against its `__symbols__`.

So `frogger-base-overlay.dts` pulls in `qcom/camera/volcano-camera.dtsi` as well
as the sensors, and our overlay contains both halves. The merged tree ends up
equivalent, but if camera misbehaves this asymmetry is the first thing to
re-check — particularly whether anything in the platform block needs a base-dtb
node we are shadowing rather than extending.

### Asteroids' camera overlay was colliding with Frogger

`qcom/camera/volcano-camera-sensor-qrd.dts` is no longer a QTI reference
overlay — the fork repurposed it to include `arcanine-camera-sensor.dtsi`
(Asteroids' sensors) and it declares `qcom,board-id = <11 0>, <11 1>` with
`qcom,oem-id = <1>`. **That is exactly what `frogger-base-overlay` claims**, so
the merge script matched it to Frogger and applied Asteroids' camera on top of
ours. It logged

```
ERROR: ufdt_overlay_do_fixups():Couldn't find 'WL_LDO2_j' symbol in main dtb
ERROR: ufdt_overlay_apply():failed to perform fixups in overlay
```

and `brunch` still exited 0, so it is easy to miss.

That message is **not** harmless. Before the fix our overlay had **6** camera
flash nodes against stock's 3 — the extra three were arcanine's, merged in
despite the "failed" message. Gated behind `CONFIG_NOTHING_IS_FROGGER` in
`qcom/camera/config/pineapple.mk`, after which flash nodes match stock at 3 and
the fixup errors go to zero.

Two traps worth remembering:

* **The merge script globs `DTB_OBJ` for `*.dtbo`**, it does not read the
  makefile. Removing an overlay from the build leaves the stale `.dtbo` on disk
  and it keeps getting merged. The gate looked like it had failed until the
  stale artifacts were deleted; on a clean `out/` it would have worked first try.
* The `-pro` variant shares the board-ids but has `oem-id = <2>`, so it does not
  collide today. It is gated anyway — it is equally Asteroids-specific.

### Two build-system snags this hit

1. `<dt-bindings/msm-camera.h>` ships in **camera-kernel**, not the kernel, so
   dtc could not find it. Fixed with `KBUILD_DTC_INCLUDE` in
   `TARGET_KERNEL_ADDITIONAL_FLAGS`, matching what the OEM camera-devicetree
   Makefile does.
2. `volcano-camera.dtsi` needs `GIC_SPI`, the gcc/camcc clock ids, interconnect
   ids and rpmh regulator levels. Including only the camcc binding produced a
   bare-identifier syntax error at the first `GIC_SPI`.

**Watch out when verifying:** `out/.../obj/KERNEL_OBJ/.../noth/*.dtbo` is stale
and is *not* what ends up in the image. The live artifact is
`out/.../obj/DTB_OBJ/out/*.dtbo`, and `dtbo.img` is built from that. Checking
the KERNEL_OBJ copy showed zero camera nodes on a build that had them.

## Original finding: the sensor device tree was missing entirely

**The blobs are fine.** All sensor modules map 1:1 from stock into
`proprietary-files.txt` — `frogger_s5kgn9_main{,_v2}` (wide),
`frogger_imx355_uw` (ultrawide), `frogger_s5kkd1_front`, plus
`frogger_s5kjn5_tele{,_v2,_v3}` which belongs to the Pro and rides along in the
shared firmware. Their eeprom `.so`s are all present too.

**The device tree is not.** `noth/frogger-camera-supply.dtsi` ports the SGM38120
and the two WR1241 camera PMICs, but there are **no `qcom,cam-sensor` nodes
anywhere in `noth/`** — not for Frogger and not for Asteroids either. The
sensors, CSI PHY assignment, power-up sequences, eeprom/actuator/flash wiring
and MCLK pinctrl all live in a `camera-devicetree` module the fork does not
carry (OEM: `vendor/qcom/proprietary/camera-devicetree/frogger-camera-sensor.dtsi`
and `volcano-frogger-camera-sensor-qrd.dts`). `qcom/camera/volcano-camera*.dts`
exists in the devicetrees repo but no Makefile builds it.

Net effect: `camera-kernel` is built and loaded, but nothing will probe.

### The authoritative reference is stock's own dtbo

Stock `dtbo.img` holds 71 overlays. Carve them out and the Frogger one is
identifiable by the ids our overlay already claims:

```sh
ota_extractor --payload payload.bin --partitions dtbo --output_dir .
python3 system/libufdt/utils/src/mkdtboimg.py dump dtbo.img -b entry
for f in entry*; do dtc -I dtb -O dts $f 2>/dev/null | grep -m1 'qcom,board-id'; done
```

**`entry.65` and `entry.66`** both carry `qcom,board-id = <0x0b 0x00>` and
`qcom,oem-id = <0x01>`, and are structurally identical — 27 `qcom,cam-sensor*`
nodes, 12 `dsi_panel_pwr_supply*` references, same Frogger strings. They are the
same overlay emitted per SoC:

| Entry | `qcom,msm-id` |
|---|---|
| `entry.66` | `0x280`, `0x281`, `0x27c` — 640, 641, **636 (volcano)** |
| `entry.65` | `0x2c8` — 712 (volcanop) |

**`entry.66` is ours**, because `frogger-base-overlay.dts` declares
`qcom,msm-id = <636 0x10000>`. Use `entry.65` only as a cross-check.

Nothing left QTI's `model = "Qualcomm Technologies, Inc. Volcano QRD"` string in
place, so **do not search by model name** — match on board-id/oem-id, then
disambiguate on msm-id.

Note the OEM camera dts declares `qcom,board-id = <11 0>, <11 1>` where our
overlay declares only `<11 0>`. Check whether the second board-id matters before
assuming one is enough.

This overlay is also the ground truth for diffing our own
`frogger-base-overlay.dtbo` once the build produces one — worth doing regardless
of camera.

### Port plan — every dependency is already in the tree

The OEM source is the thing to port, not the decompiled dtb: 401 readable lines
at `camera-devicetree/frogger-camera-sensor.dtsi`, plus its 24-line wrapper
`volcano-frogger-camera-sensor-qrd.dts`. Four sensors:

| Node | CCI | csiphy | Role |
|---|---|---|---|
| `qcom,cam-sensor0` | `cam_cci0` | 0 | wide (S5KGN9) |
| `qcom,cam-sensor1` | `cam_cci0` | 1 | front (S5KKD1) — roll 270, yaw 0 |
| `qcom,cam-sensor2` | `cam_cci1` | 3 | ultrawide (IMX355) |
| `qcom,cam-sensor3` | `cam_cci1` | 2 | tele (S5KJN5) |

It references 51 external labels. **All of them resolve in this tree already:**

| Label group | Defined in |
|---|---|
| `cam_cci0`, `cam_cci1` | `qcom/camera/volcano-camera.dtsi` |
| `eeprom_{wide,uw,front,tele}`, `actuator_triple_*`, `led_flash_triple_rear_*` | `qcom/camera/volcano-camera-sensor-idp.dtsi` |
| `camcc` | `qcom/volcano.dtsi` |
| `cam_cc_camss_top_gdsc` | `qcom/pineapple-gdsc.dtsi`, which `volcano.dtsi` includes and then overrides |
| `pmxr2230_{switch0,flash0,flash3,torch0,torch3}` | `qcom/pmxr2230.dtsi` |
| `SGM_LDO1-7`, `WR_LDO*` | our own `noth/frogger-camera-supply.dtsi` |
| `cam_sensor_{mclk,active_rst,suspend_rst}*` pinctrl | volcano camera base |

So nothing has to be invented. Steps:

1. Add `noth/frogger-camera-sensor.dtsi`, adapted from the OEM file.
2. Add `noth/volcano-frogger-camera-sensor-qrd.dts` wrapping it, including
   `volcano-camera.dtsi` and `volcano-camera-sensor-idp.dtsi` for the base
   labels.
3. Add the `.dtbo` to `noth/Makefile` inside the existing
   `CONFIG_NOTHING_IS_FROGGER` branch.
4. `TARGET_MERGE_DTBS_WILDCARD := *volcano*` already matches the filename.

The merge script folds every overlay with matching ids into one entry — which is
why stock ships camera and display together in `entry.66` rather than as
separate overlays. That also means this does **not** hit the board-id collision
that forced the Asteroids/Frogger `ifeq` gate; those collided because they are
alternative *boards*, whereas this is an additional overlay for the same board.

### Other known differences

* post-processing — Morpho EIS and ArcSoft, where Asteroids uses Vidhance
* camera PMIC — SGM38120 rather than WL28681, already in the device tree
* `vendor/etc/camera/` ships `_Pro`/`tele` variants of the calibration blobs;
  inert on a non-Pro unit

The `libarcsoft_*` blobs are among the largest in `vendor/` (one is 118 MB).
