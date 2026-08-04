# Open items

Live tracker for Frogger bring-up. Resolved items are **removed** from here —
their rationale lives in git history and in [decisions.md](decisions.md),
[hardware-facts.md](hardware-facts.md) and [changes.md](changes.md).

## TODO

In dependency order. Nothing below "flash and boot" can be checked until the
device actually comes up.

**Status:** **`brunch frogger` succeeds.** First complete ROM built
2026-08-03 on the build server: `lineage-23.2-20260803-UNOFFICIAL-frogger.zip`,
1.56 GB, exit 0. Every partition image is produced — `system` 862 MB,
`vendor` 1612 MB, `product` 547 MB, `system_ext` 362 MB, `boot` 96 MB,
`vendor_boot` 96 MB, `vendor_dlkm` 73 MB, `dtbo` 24 MB.

**IT BOOTS.** First successful boot 2026-08-04 with
`lineage-23.2-20260804-UNOFFICIAL-frogger.zip`:

```
sys.boot_completed = 1        bootanim = stopped
display  1224x2720 @ 120/90/60/45/30/24Hz, HDR [2,3,4], 480dpi
         uniqueId local:4630947107087237506
sound    volcano-qrd-wsa883x-snd-card registered
kernel   0 errors, no failed module loads, no deferred probes
```

Two bugs blocked it, both in this tree, both invisible to the checks used at
the time — see [What blocked first boot](#what-blocked-first-boot).

Builds run on the build server, not the laptop — see
[build-server.md](build-server.md). Finished zips are published at
<https://build.ssiyad.com>.

Build cost, measured from `out/.ninja_log` on a 12-core/62 GB server, cold `out/`:

| | |
|---|---|
| total ninja wall time | 257 min |
| kernel (`Image.gz`, one edge, includes all ext modules) | 22.6 min (~9%) |
| kernel `.config` | 29 s |
| heaviest single edges | SystemUI 557 s, Launcher3 339 s, platformprotos 321 s |

So the platform — Java/Kotlin/R8 — dominates, and the kernel is under a tenth of
a cold build. For kernel or device-tree iteration use targeted `mka` targets
(`mka bootimage`, `mka dtboimage`, `mka vendordlkmimage`) rather than a full
`brunch`; `dtbo.img` packaging alone is sub-second.

### Get it booting — next up

- [ ] **Re-flash using the inactive-slot procedure** (see
      [Flashing](#flashing--use-the-inactive-slot)). This is the unblocking step:
      it leaves a bootable stock slot as automatic fallback, so a failed boot no
      longer needs a hard power-off — which means **pstore survives and we finally
      get a panic log**
- [ ] **Find why our dtbo prevents boot.** Bisected to the device tree; removing
      the camera block did *not* fix it, so the cause is still unidentified
- [ ] Triage the captured log and let it drive everything below

### Needs a booted device

- [ ] Verify touchscreen sysfs nodes bind (single-tap, UDFPS)
- [ ] Verify audio; if speakers are silent, flip `speaker_protection_enabled` to `0`
- [ ] Verify display brightness curve resolves to the expected panel ID
- [ ] Decide `spunvm` — stock comments the mount out, we mount it
- [ ] Reconcile the 29 generic modules once the kernel actually builds them
- [ ] **Camera** — currently **disabled** in the overlay (devicetrees `95d70251`)
      because our dtbo prevents boot. The sensor nodes are still in the tree and
      were never reached; re-enabling needs the platform-block problem solved
      properly. See [Camera](#camera--disabled-prevents-boot)
- [x] ~~Diff our built overlay against stock `entry.66`~~ — done, see
      [Device tree vs stock](#device-tree-vs-stock-clean). Found and fixed the
      wrong speaker amp and a missing display panel

### Cleanup, no boot needed

- [ ] Two deferred device-tree items (thermal NTC, pinctrl audio-pop fix)
- [ ] SELinux back to enforcing; drop `SELINUX_IGNORE_NEVERALLOWS` and
      `BOARD_API_LEVEL_PROP_OVERRIDE` before any release build
- [ ] Decide whether to version `vendor/nothing/frogger` (GitLab) or regenerate
- [ ] Re-enable Glyph if wanted (needs a `ParanoidGlyphPhone4a` upstream target)
- [ ] Re-enable `DeviceExtras` (optional) — fork `hardware/nothing`, move
      `device_extras` to private policy and route vendor access via the Health
      HAL. Decided against forking `system/sepolicy` for this

---

## Frogger-conditional code in the external modules — complete

The OEM guards Frogger behaviour with `#if IS_ENABLED(CONFIG_NOTHING_IS_FROGGER)`
in `vendor/qcom/opensource/`, mapping to `kernel/nothing/sm7635-modules`.
All 13 OEM sites are now ported and the counts match the OEM tree file for file.

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

**Audio is done** (commit `e9d209bd08`) — all 13 sites, verified against the OEM
tree file for file:

| File | Sites |
|---|---|
| `asoc/codecs/wcd9378/wcd9378.c` | 5 |
| `asoc/codecs/wcd9378/wcd9378-mbhc.c` | 2 |
| `asoc/codecs/aw882xx/aw882xx.c` | 2 |
| `asoc/codecs/wcd-mbhc-v2.c`, `include/asoc/wcd-mbhc-v2.h`, `asoc/msm_dailink.h`, `wcd9378/internal.h` | 1 each |

Two things it does, and two deliberate deviations from stock:

* **`msm_dailink.h`** points `pri_mi2s_rx/tx` at the two AW882xx amps on I2C bus
  13 (`0x34`/`0x35`) instead of Asteroids' TFA98xx. This is the functionally
  important one — without it nothing drives the speakers.
* **The rest is the WCD9378 `ANA_BIAS` watchdog.** On micbias enable and on
  headset plug-in, queued work reads `WCD9378_ANA_BIAS` with the regcache
  bypassed; a read of 0 means the codec lost its bias, and a `KOBJ_CHANGE`
  uevent (`SSR_TRIGGER=1`) goes to userspace, debounced to one per 3 s.

Deviations:

1. Stock gates the TFA98xx arm of `msm_dailink.h` on
   `CONFIG_NOTHING_IS_ASTEROIDS`. **That symbol does not exist in this tree** —
   only `NOTHING_IS_FROGGER` is declared (`drivers/soc/qcom/Kconfig:1300`).
   Copying stock verbatim would leave an Asteroids build with *neither*
   `pri_mi2s_rx` nor `pri_mi2s_tx` defined. TFA98xx is the `#else` default here.
2. `wcd-mbhc-v2.c` is shared by every wcd codec and only wcd9378 populates
   `mbhc_micbias_reg_detect`; stock calls it unconditionally, we NULL-guard it.

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

## What blocked first boot

Two bugs, found 2026-08-04.

### 1. Missing `qcom,display-topology` — kernel panic at 2.88s

```
[drm:dsi_panel_get_mode] *ERROR* invalid topology list for the panel, rc = -22
Unable to handle kernel NULL pointer dereference at 0000000000000380
Kernel panic - not syncing: Oops: Fatal exception
```

The NT37706A port added the two `dsi-panel-nt37706a-*.dtsi` files and the
QRD-level backlight overrides, but **not** the `&dsi_nt37706a_*` blocks in
`volcano-sde-display-common.dtsi` — which is where `qcom,display-topology`,
the PHY timings, the dfps lists and the ESD config live.
`dsi_panel_parse_topology()` returns `-EINVAL` without it and the caller
dereferences NULL.

Verified at the time by confirming the panel *nodes* appeared in the built
dtbo. That proved the nodes existed and nothing about the properties the driver
actually reads.

### 2. `CONFIG_NOTHING_IS_FROGGER` invisible to audio-kernel — watchdog bootloop

```
system_server -> StartAudioService -> hangs -> Watchdog kills it -> loop
/proc/asound/cards      -> no soundcards
devices_deferred        -> soc:spf_core_platform:sound
machine_dlkm.ko         -> tfa98xx.13-0034 / tfa98xx.13-0035
```

`asoc/Kbuild` selects its config header from `BOARD_PLATFORM` and includes
`config/volcanoautoconf.h` — it never sees the kernel's `autoconf.h`. So every
`IS_ENABLED(CONFIG_NOTHING_IS_FROGGER)` guard compiled its `#else` branch and
**none of the 13 ported audio hunks were in the binary**. The machine driver
looked for Asteroids' TFA98xx, which cannot register here, so the card deferred
forever.

Note `KCFLAGS` via `TARGET_KERNEL_ADDITIONAL_FLAGS` does **not** fix this — the
audio-kernel is invoked with its own `AUDIO_ROOT`/`MODNAME`/`BOARD_PLATFORM`
arguments and never receives those flags. Other modules (e.g. dataipa) do.

Verified at the time by counting `#if` guards against the OEM source. That
proved source parity and nothing about what was compiled. On device everything
looked right — aw882xx probed at 0x34 and 0x35, both components and both DAIs
registered with the expected names — because the codec side was never the
problem.

**Still to check:** the display-drivers and touchscreen hunks in
`sm7635-modules` use the same guard and are very likely still dead code. The
audio fix only touched the audio-kernel's own config header.

## Getting logs from a device that will not boot

The two techniques that actually worked, after pstore, `nt_kmsg`, `nt_log` and
`rawdump` all came back empty:

**Sahara ramdump.** In Nothing's crashdump screen the device exposes
`05c6:900e` (Qualcomm memory-dump mode) on USB. `edl.py memorydump` from
[bkerler/edl](https://github.com/bkerler/edl) pulls `md_KCONSOLE.BIN`, a 2 MB
kernel console buffer. **Do not power the device off** — that mode only exists
while it sits on the crashdump screen.

**Insecure adb.** `WITH_ADB_INSECURE := true` gives adb during a bootloop, which
is the only way to read a live userspace failure. pstore is the wrong tool for
that case entirely: if the kernel is up and userspace is restarting, nothing is
being preserved across a reboot because there is no reboot.

## Historical: first flash — does not boot

Flashed 2026-08-03/04. The ROM installed cleanly via `adb sideload` and then the
device dropped into Nothing's crashdump (minidump) screen on first boot, showing:

```
store_ramdump_to_userdata: get rd_format_if.init failed
Nothing Rdump2Userdata store failed.
```

That second message is the dump *storage* failing — userdata had just been
formatted — not the crash itself. It says nothing about the cause.

### What was established

Single-variable bisect against a known-good state, on hardware:

| Config | Result |
|---|---|
| stock boot + stock vendor_boot + stock dtbo + **our** recovery | **recovery boots, adb works** |
| … + our dtbo (with camera) | no boot, falls back to fastboot |
| … + our dtbo (camera removed) | no boot |
| our boot + our dtbo (camera removed) + stock vendor_boot | no boot |
| our boot + our vendor_boot + our dtbo + our recovery | does not enumerate at all |

Two conclusions:

* **Our kernel is fine.** `recovery.img` carries the same 6.6 kernel and boots
  and runs adb on the device.
* **Our dtbo is what prevents boot** — and **removing the camera block did not
  fix it**, so the fault predates the camera port and is still unidentified. The
  pre-camera ROM was never flashed, so it was never known-good either.

Caveat on that table: `BOARD_INCLUDE_DTB_IN_BOOTIMG := true`, so the base DTB
ships inside `boot.img`. Rows pairing our dtbo with stock's `boot.img` are
applying our overlay to stock's 6.1 base device tree, which cannot work by
construction. Those rows are not clean evidence — the last row is the honest one.

### Why there is no panic log

Nothing survived, and it is worth recording why so the next attempt is set up
differently:

* **pstore** (`/sys/fs/pstore`, 4 MB at `0x81f20000`) is mounted and empty. It
  lives in RAM and survives a warm reset, but escaping the crashdump screen
  needs Power held ~20–30 s, which is a PMIC-level reset that cuts RAM.
* **`nt_kmsg`** (64 MB) contains only stock's `6.1.157` kernel, no panics. It is
  fed by a Nothing kmsg dumper our build does not carry.
* **`nt_log`** holds `boot_log/NTboot_54..63` archives — all stock 6.1, zero
  panics. Written by Android userspace, which our boot never reached.
* **`rawdump`** (256 MB) is entirely zeros — no ramdump was written, matching the
  `Rdump2Userdata store failed` message.

**The fix is procedural, not technical:** flash to the inactive slot so a failed
boot falls back automatically instead of needing a power-hold.

## Flashing — use the inactive slot

Scripted as [`tools/flash-frogger.sh`](../tools/flash-frogger.sh); collect logs
afterwards with [`tools/grab-logs.sh`](../tools/grab-logs.sh).

```sh
cd ~/sources/android/downloads/roms
WIPE_DATA=1 ~/sources/android/lineage/device/nothing/frogger/tools/flash-frogger.sh \
    ex lineage-images
```

Two image directories because they complement each other: `ex/` is the ROM
payload extraction and has every partition image; `lineage-images/` is the
images zip and is the only one with `super_empty.img`. The script searches the
first, then the second.

Use **platform-tools r33.0.0**, which is what the reference flasher pins:
`~/sources/android/platform-tools`.

Reference: [spike0en/nothing_flasher](https://github.com/spike0en/nothing_flasher)
(`frogger` branch, `bash/flash_all.sh`). Its structure matters more than the
individual commands:

1. Everything runs **in fastbootd**, not the bootloader — including `boot`,
   `dtbo`, `vbmeta`.
2. Everything flashes to the **inactive slot** via `--slot=`, with
   `--set-active` only at the very end. The working slot stays intact throughout.
3. Logical partitions are **deleted and recreated** (`delete-logical-partition`,
   `create-logical-partition <name> 1`) before flashing — for both slots — rather
   than just flashed.
4. Firmware partitions are flashed too (23 of them).
5. It pins **platform-tools r33.0.0**, not latest. Given the USB trouble below,
   that may not be incidental.

### The USB link needs re-enumeration before every flash

This cost hours. Symptoms: `FAILED (remote: ' Invalid argument size')` or
`FAILED (remote: 'unknown command')` — **the same command producing different
errors on different runs**, which is the signature of a corrupted transport, not
a device fault. `getvar all` works while targeted `getvar` returns
`Variable Not found`, and fastboot then warns `partition size: 0`, which is a
red herring rather than a real problem.

A flash succeeds only on a freshly enumerated connection:

```sh
fastboot reboot bootloader; and fastboot flash <part> <img>   # fish
```

One flash per reboot. Retry loops around a bare `fastboot flash` do not help;
the re-enumeration is what matters.

Also note: this bootloader advertises `has-slot` for only `boot`, `system` and
`modem`, so fastboot does not auto-suffix anything else. Unsuffixed names target
the current slot, which is fine — but be aware which slot that is, because the
bootloader switches slots on its own after repeated boot failures.

## Device tree vs stock — clean

Our built overlay was diffed node-for-node against stock's shipping overlay for
`board-id <11 0>` / `oem-id <1>` (`dtbo.img` `entry.66`). Everything stock has
that we lack is now accounted for:

| Missing from ours | Verdict |
|---|---|
| `cable_detect` | **Correct.** Frogger renamed the driver — `nt,cable_detect` → `gpio,cable_state` (`CONFIG_OEM_CABLE`). We ship `cable_gpio`/`cable_state`; stock carries the vestigial Asteroids node |
| `ois_vdd_ctrl` | **Correct.** Tele-camera OIS; `ois_vdd_ctrl.ko` deliberately dropped |
| `nfc`, `nfc_*`, `nq@28`, `st54spi_gpio`, `st_st21nfc@08` | **Correct.** NFC removed entirely |

Two real gaps were found and fixed (devicetrees `a885b2dc`):

**The wrong speaker amplifier.** `qcom/audio/volcano-audio-overlay.dtsi`
declared NXP `tfa98xx@34/@35` on `qupv3_se13_i2c` — Asteroids' amps, at exactly
the addresses Frogger fits Awinic AW882xx. Because that file is shared, whichever
board merged it got the wrong codec. Combined with the ported `msm_dailink.h`
pointing `pri_mi2s` at `aw882xx_smartpa.13-0034/0035`, Frogger would have had no
codec to bind to and **no sound card at all**. The amps are a board choice, so
each board now declares its own in `noth/<board>-common.dtsi`.

**The display panel was absent.** Stock carries
`qcom,mdss_dsi_nt37706a_120hz_fhd_plus_dsc_vid_{boe,vxn}` plus
`dsi_panel_pwr_supply_amoled_frogger` and its `dvdd_en` rail; we had none of
them. Ported from the OEM `display-devicetree` module. Note the default panel is
`rm69220` in **both** ours and stock — the bootloader selects the real panel by
name at runtime, so the default is not the thing that matters.

This is the same failure mode as the arcanine camera overlay, three times over:
**the fork's shared `qcom/` files carry Asteroids hardware.** Anything under
`qcom/` that touches a board-level peripheral is suspect until checked.

### How to re-run the diff

`bin/dt-diff.sh` on the build server. One trap: node names must be compared with
**all leading whitespace stripped** — the two overlays nest the same node at
different depths, so comparing with tabs intact reports identical nodes as
missing. An early version of the script had this bug and produced a false
"camera sensors are missing" result on a build that had them.

## Camera — disabled, prevents boot

**Currently disabled** in `frogger-base-overlay.dts` (devicetrees `95d70251`).
Our dtbo does not boot, and while removing the camera block did **not** fix that,
it stays off until the device tree is understood.

The structural problem, recorded so it is not re-attempted blindly: the base
`volcano.dtb` carries no camera block at all, so the sensor nodes cannot resolve
`&cam_cci0/1` on their own. The port therefore pulled the whole platform half
(`qcom/camera/volcano-camera.dtsi` — cci, csiphy, icp, bps, smmu, the
`cam_sensor_*` pinctrl) into the overlay. **Stock's overlay contains sensors
only**, because its base tree already provides the platform half. Duplicating
that block in an overlay is evidently not equivalent to having it in the base.

`noth/frogger-camera-sensor.dtsi` is kept — the sensor nodes were never reached
and may be perfectly correct.

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

## Deferred device-tree items

Both live in the fork's shared `qcom/` base rather than `noth/`, so editing them
in place would affect Asteroids; each wants expressing as an override:

* thermal NTC restructure (`sys-therm-13` → `sub_board_ntc`, and adding
  `qupv3_se1_i2c` to thermal `qcom,critical-devices`)
* one pinctrl `bias-disable` → `bias-pull-down` audio-pop fix (BELL-5845)

## DeviceExtras — disabled (decided)

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

### Decision: leave it disabled

DeviceExtras is a settings app, nothing depends on it, and neither fix is worth
blocking a first boot on. **If and when it is restored, do it by forking
`hardware/nothing`** rather than `system/sepolicy`:

* Move `device_extras` from `DeviceExtras/public/` to `DeviceExtras/private/`,
  so it is no longer part of the platform-vendor API surface and needs no compat
  mapping at all.
* Route the vendor-file access through a HAL instead of granting it to a
  system_ext app. `hal_lineage_health_default` already holds exactly the
  permission needed:
  `rw_dir_file(hal_lineage_health_default, vendor_proc_power_supply)`.
* Then drop our `sepolicy/vendor/device_extras.te` entirely, since vendor policy
  can no longer reference a private type.

`hardware/nothing` is already a dependency we track, so forking it costs one
more repo and no AOSP rebase burden.

**Rejected:** forking `system/sepolicy` to add `device_extras` to `new_objects`
in `202404.ignore.cil` / `202504.ignore.cil`. AOSP's own error message suggests
this, and it is two lines, but it means carrying a patch against a large AOSP
repo forever to paper over a public type that should not be public.

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

* `WITH_ADB_INSECURE := true` in `lineage_frogger.mk` — adb with no authorisation
  dialog, which cannot be tapped on a device that will not boot. It also leaves
  `ro.debuggable=1`, since it skips `PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG`.
  **Do not set `ro.adb.secure=0` directly** — `vendor/lineage/config/common.mk`
  already assigns it and Soong fails the build on duplicate sysprop assignments.
  It must also go in the **product** makefile, before the `vendor/lineage`
  inherit that reads it; in `BoardConfig.mk` it is silently ignored, because
  board config is evaluated after product config
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
