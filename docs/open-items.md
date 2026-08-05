# Open items

Live tracker for Frogger bring-up. Resolved items are **removed** from here —
their rationale lives in git history and in [decisions.md](decisions.md),
[hardware-facts.md](hardware-facts.md) and [changes.md](changes.md).

## TODO

**State:** boots, daily driven since 2026-08-04. Display, audio, telephony,
sensors, fingerprint, Wi-Fi, thermal and haptics all work. Camera enumerates
five sensors but cannot open a capture session — parked, see
[camera.md](camera.md).

Builds run on the build server, not the laptop — [build-server.md](build-server.md).
Published at <https://build.ssiyad.com>.

A cold build is ~257 min of ninja wall time, of which the kernel is ~23 min.
Platform Java/Kotlin/R8 dominates. For kernel or device-tree iteration use
targeted `mka` targets (`mka bootimage`, `mka dtboimage`, `mka vendordlkmimage`)
rather than a full `brunch`.

The two bugs that blocked first boot are in
[What blocked first boot](#what-blocked-first-boot) — both were invisible to the
checks used at the time, which is the reason that section is kept.

### Priority order

**Camera is sidelined** as of 2026-08-05 — see [camera.md](camera.md). The plan
for what follows is in [roadmap.md](roadmap.md); the short version:

1. **GApps survive a flash** — implemented, needs one test: flash a ROM without
   reflashing GApps
2. **Signing and keys** — first, because re-signing forces a factory reset and
   invalidates anything Magisk has patched
3. **Magisk / Zygisk / Play Integrity** — after signing, or it must be redone.
   `STRONG` integrity is not reachable; be clear about that before starting
4. **SELinux enforcing** — denial collection should start *now*, in parallel.
   Both counts quoted so far (5, then 0) understate it: permissive only logs
   paths that ran, and `dontaudit` hides more
5. **NFC** — `com.android.nfc` ships disabled, cause uninvestigated
6. Everything under [Cleanup](#cleanup-no-boot-needed)

### Verified working on hardware

Checked 2026-08-04 on the 15:08 build:

| | |
|---|---|
| sensors | 35 h/w sensors, all running — lsm6dsv accel/gyro, och191x magnetometer |
| telephony | SIM loaded, LTE, IMS active |
| wifi | `wlan0` + `wifi-aware0` present, 9 HAL entries |
| display | 1224x2720 @ 120Hz, cutout correct |
| fingerprint | enrol + auth confirmed |
| audio routing | earpiece and speaker devices present |
| haptics | confirmed via vibrator history |
| thermal | zero bind failures |

### Broken or missing

- [ ] **Camera — enumerates but cannot open a session.** Five sensors, 25 v4l2
      subdevs, flash and torch work. `com.qti.node.swpnc` fails in `LoadLib()`
      and takes the provider down. Parked — see [camera.md](camera.md)
- [ ] **NFC — `com.android.nfc` is in the disabled package list.** Cause not yet
      investigated. No contactless payments, no tag reading

### Cannot be fixed here

Play Integrity's `MEETS_STRONG_INTEGRITY` needs hardware key attestation and is
unreachable on an unlocked bootloader, whatever the build is signed with. Banking
apps and Google Wallet will refuse to run. See [roadmap.md](roadmap.md) before
spending time on it.

Signing keys now exist (`vendor/lineage-priv/keys/`), so `test-keys` is a
solvable problem — but AVB stays disabled (`--flags 3`) until re-locking is
decided, and `verifiedbootstate=orange` alone fails `DEVICE` integrity.

### Still unverified — needs hands on the device

- [ ] **Extracted vendor blobs are not in git and drift silently.**
      `vendor/nothing/frogger/` is not a git repo and not a `repo` project, yet
      `frogger-vendor.mk` copies files straight out of it into the image. So a
      `blob_fixup` change in `extract-files.py` does **not** change what gets
      built — only what a future re-extraction would produce, and the extracted
      tree must then be corrected by hand on the laptop *and* the build server.
      Hit for real by the LVACFS recording fix (see [audio.md](audio.md)).
      **The two copies had already silently diverged:** on 2026-08-06 the laptop
      tree held 1642 files and the builder 1673 — the laptop was missing 31,
      including camera blobs like `com.qti.node.swpnc.so`. Builds were correct
      because they run on the builder, so nothing ever surfaced it. Re-running
      `extract-files.py` against the stock `B4.1-260309-1830` dump at
      `~/sources/android/downloads/firmwares/frogger/extracted` brought the
      laptop to 1673 and the trees now match.
      Options: put the blob tree under git and add it to the local manifest, or
      re-run `extract-files.py` as part of the build. Until then, any blob edit
      is a two-machine manual step and easy to half-apply
- [ ] Real call audio: earpiece and mic in an actual call
- [x] ~~Speaker playback; if silent, flip `speaker_protection_enabled` to `0`~~ —
      it was silent, and this was the cause. Speaker protection opens a VI
      feedback capture stream alongside playback; the TX side never opened and
      took the Rx session with it, so every playback start failed:
      `viTxSetupThreadLoop: txPcm open not ready` then
      `Backend:24 <-> Frontend:121 Connect failed error:-22`. Set to `0` in
      `audio/resourcemanager_volcano_qrd.xml`. **Also the likely cause of video
      stutter** — playback was failing and retrying, not decoding slowly
- [ ] **No speaker protection at all** — see [audio.md](audio.md). Neither
      QCOM's VI path (it targets WSA883x controls and this device has no WSA
      amp on SoundWire) nor the AW882xx's own algorithm, which reports
      `monitor enable: 0` and `dsp_re 0`. `monitor-mode = "kernel_monitor"`
      was tried (devicetrees `503b2916`) and **verified not to help**: the
      driver only populates its monitor config from a sysfs write to
      `monitor_update`, which loads `aw882xx_pid_2329_monitor.bin` — a file
      absent from our blobs *and* from stock. Not fixable from the DT. The
      only remaining route is shipping stock's `libar-pal.so`, which has
      Awinic support, in place of our source-built PAL — a large risk to
      everything audio that currently works
- [ ] Bluetooth pairing
- [ ] GPS lock
- [ ] Single-tap gesture (UDFPS itself is confirmed)
- [ ] Verify display brightness curve resolves to the expected panel ID
- [ ] Decide `spunvm` — stock comments the mount out, we mount it
- [ ] Reconcile the 29 generic modules once the kernel actually builds them
- [x] ~~Verify touchscreen sysfs nodes bind (UDFPS)~~ — works, see
      [Fingerprint](#fingerprint--fod-illumination-fixed-and-verified)
- [x] ~~Diff our built overlay against stock `entry.66`~~ — done, see
      [Device tree vs stock](#device-tree-vs-stock--clean). Found and fixed the
      wrong speaker amp and a missing display panel

### Thermal — `/delete-node/` does not work in this overlay

`frogger-base-overlay.dts` is `/plugin/;`, a true dtbo overlay. **An overlay
cannot delete a node from the base DTB.** `frogger-common-pmic.dtsi` opens with
eight of them — `/delete-node/ sys-therm-{1,2,3,4,5,6,7,10}` — each followed by
a friendlier-named replacement zone. The deletes are silently no-ops against
`volcano.dtb`, so every base zone survives and the replacements are added
alongside. On the device all nine `sys-therm-*` are registered, plus
`board_ntc`, `flash_light_ntc`, `uhbpa_ntc` and `usb2_port_ntc` — duplicates
polling the same ADC channels. `ap_ntc` and `nrpa_ntc` did not register at all,
presumably losing the channel to the surviving base zone.

Only `sys-therm-5` actually errored, and only because of a second effect:
redefining `display_test_config1..4` inside `nrpa_ntc` stole those labels, so
the base zone's own cooling-maps resolved to trips in a *different* zone. Trips
must belong to the zone that maps them, so all 36 binds failed with `-ENXIO`,
36 times a boot. Verified on device by comparing phandles — `sys-therm-5`'s
cooling-map pointed at `0x54f` (`nrpa_ntc`'s trip) rather than its own `0x31c`.

Fixed by deleting the `nrpa_ntc` zone and its cooling-maps outright: it was a
byte-for-byte copy of the base zone under another name (diffed, 42 lines,
identical), and nothing in userspace references either name — checked our
vendor blobs and stock's `vendor/etc` and `odm/etc`, zero hits for both. The
`/delete-node/ sys-therm-5;` is deliberately **kept**, so the overlay emits no
fragment for that zone and the self-consistent base definition is what
registers.

- [x] ~~The remaining seven renames have the same duplicate-zone problem~~ —
      all seven dropped in devicetrees `49199ebe`. Every one was byte-identical
      to the base zone it shadowed and none could register, because the base
      zone already owned the ADC channel. What is left is exactly the five that
      do register: `battery`, `board_ntc`, `flash_light_ntc`, `uhbpa_ntc`,
      `usb2_port_ntc`. Note `sys-therm-7` and `sys-therm-10` are defined in
      `volcano-pmiv0104.dtsi`, not `volcano-pmic-overlay.dtsi` — a collision
      check against only the latter wrongly clears `sc_buck_ntc` and
      `usb_port_ntc`
- [x] ~~Not compile-verified~~ — **verified on hardware** on the 15:08 build:
      `binding zone` failures went 36 to 0, all eight shadow zones absent, and
      the five real zones (`battery`, `board_ntc`, `flash_light_ntc`,
      `uhbpa_ntc`, `usb2_port_ntc`) all registered
- [ ] Two zones are both named `battery` — `thermal_zone40` and
      `thermal_zone61`, reading 37.65C and 37.0C. No `qcom/*.dtsi` defines one,
      so ours comes from DT and the other is registered by a driver. Pre-existing
      and not from the shadow-zone work, but `thermal-engine.conf` matches on the
      name, so which one it binds to is not deterministic. Low priority

### Fingerprint — FOD illumination, fixed and verified

An optical under-display sensor cannot capture an unlit finger, so the panel has
to light it. The `/sys/panel_feature` attribute group is what exposes that, and
`sde_connector.c` creates the whole group only when `nt_is_panel_detected()` is
true. That function merely reports whether the global `nt_panel` was ever
assigned, and `dsi_panel_get()` assigned it on an exact `strcmp` against
Asteroids' rm69220. Frogger's nt37706a never matched, so **the entire directory
was absent** — not just one node.

Symptom: the UDFPS circle appeared and touching it did nothing.
`dumpsys fingerprint` showed the HAL alive, `HAL deaths since last reboot: 0`,
no lockout, and `acquire=0` — no touch ever reached the sensor.

Fixed in `sm7635-modules` `51ec430bb8` / `2111ef9f5d`. **Verified on hardware:**

```
/sys/panel_feature/   brightnessid  fp_status  panel_id1  panel_id2  panel_id3
Fps state: 2          (was 0)
count:1  acquire:5  accept:4  reject:0  lockout:0
```

#### `ui_status` is a red herring — do not chase it

`fingerprint/Session.cpp` writes `/sys/panel_feature/ui_status` in four places.
**That node does not exist and never did.** The kernel's attribute list is:

```c
static struct attribute *panel_feature_attributes[] = {
    &panel_id1_attribute.attr, &panel_id2_attribute.attr,
    &panel_id3_attribute.attr, &fp_status_attribute.attr,
    &brightnessid_attribute.attr, NULL,
};
```

No `ui_status`, on Frogger or Asteroids. All four HAL writes fail silently —
`WriteStringToFile` returns a `bool` that nothing checks. The working
illumination path is `fp_status`. Reading the HAL first made `ui_status` look
like the mechanism during diagnosis; it is not, and the fix worked because the
gate controlled the whole group.

- [ ] Dead code: drop the four `ui_status` writes from `Session.cpp`, or point
      them at `fp_status` if they were ever meant to do something
- [ ] `brightnessid` and `panel_id*` are now readable and were not before. They
      may bear on [Display brightness curve](#display-brightness-curve)

A 30Hz backlight quantisation keyed on the same rm69220 name was dropped rather
than ported; its levels came from that panel's gamma. If the nt37706a needs the
same treatment it wants its own measured values.

### Confirmed fixed on hardware, 15:08 build

- Thermal shadow zones — `binding zone` 36 to 0
- `PinnerService` — 1 error to 0
- Fingerprint FOD illumination — enrol and auth working
- Display cutout, device name, volume panel side

Boot survey on that build: crash buffer empty, SELinux denials 0. Error ranking
is now topped by `keystore2` at 162, which is attestation failing against our
test-key AVB-disabled build under NikGApps — expected, not actionable. The audio
tags below it (`AGM`, `PAL`, `ACDB`, `gsl`) were not visible in the MindTheGapps
survey and are GApps-package noise rather than regressions.

### GApps survive a flash — addon.d, enabled 2026-08-04

Every ROM sideload used to wipe GApps, because a payload OTA rewrites `system`,
`product` and `system_ext` whole and nothing put anything back.

The build already shipped the machinery — `backuptool_ab.sh`,
`backuptool_ab.functions`, `backuptool_postinstall.sh` and
`system/addon.d/50-lineage.sh` — but it was never invoked. There is one
postinstall hook per partition and `device.mk` pointed the `system` one at
AOSP's `otapreopt_script`, inherited from Asteroids. `backuptool_postinstall.sh`
is what runs the `addon.d` scripts, so with it bypassed nothing restored.

Now points at `backuptool_postinstall.sh`. The trade is that `otapreopt` no
longer runs after an OTA, so the first boot after an update is slower — official
LineageOS A/B devices make the same trade.

- [ ] **Unverified** — confirm GApps survive a sideload without reflashing them.
      Restore also depends on the GApps package installing its own
      `/system/addon.d/` script; MindTheGapps does, NikGApps' list is far larger
      and how completely it survives has not been checked

### Build fingerprint and signing — two known inconsistencies

`PRODUCT_SHIPPING_API_LEVEL` stays at **35**. It was briefly raised to 36 to
match stock's `ro.product.first_api_level` and that was a mistake, reverted the
same day. The variable is not a property — it is the compliance switch that
selects which launch requirements the build must meet. At 36 it turned on the
16KB page-size check, which rejects `adpl` and `ATFWD-daemon` on a
`CONFIG_ARM64_4K_PAGES` kernel, and then made `host_init_verifier` fatal on
stock init scripts declaring no user, such as `vendor.nicmd`. Two build cycles
lost. Stock reporting 36 is not a reason to claim it: stock is not built through
AOSP's checks, and this device's vendor partition is API 34.

The remaining two are not one-line fixes:

- [ ] **`ro.build.tags=test-keys` while every fingerprint claims
      `release-keys`.** The only coherent fix is signing with our own release
      keys — generate them, set `PRODUCT_DEFAULT_DEV_CERTIFICATE`, and the tags
      follow. **This re-signs every APK, so system app signatures change and a
      factory reset is required.** Worth doing before any wider release, not
      casually on a daily driver
- [x] ~~The fingerprint advertises stock build `2606301839` while our blobs came
      from `2603091830`~~ — resolved by pointing the fingerprint, all four SKU
      files and `VENDOR_SECURITY_PATCH` at `2603091830`. This reverses part of
      [decisions.md](decisions.md) §2, which assumed blobs would be pulled from
      the phone; they never were. See the note recorded there

Per-partition values were checked against stock and are otherwise correct,
including `FroggerIND` in the vendor and odm fingerprints, which comes from
`sku/build_IND.prop` and matches an India unit.

### Camera — see [camera.md](camera.md)

Five sensors enumerate, flash and torch work, pipelines build; the app still
cannot open a capture session. Four defects found and fixed (bad bisect, missing
regulator drivers, generic media profiles, incomplete blobs); two gaps remain —
the SOIS kernel driver and the Morpho node AIDL conflict.

The full history, the diagnostic method, the exact remaining work and the traps
are in [camera.md](camera.md). Do not start from this file.

- [ ] Port `cam_sensor_nothing.{c,h}` and re-enable SOIS
- [ ] Ship the Morpho nodes once the allocator version conflict can be scoped

### Decided against

- **Moving the volume panel up the screen.** Its vertical position is
  `app:layout_constraintVertical_bias="0.5"` hardcoded in AOSP's
  `SystemUI/res/layout/volume_dialog.xml`. It is an attribute literal, not a
  dimension reference, so no resource overlay reaches it —
  `volume_dialog_slider_vertical_margin` is used for both the top and bottom
  margin, so overriding it grows both ends and the panel stays centered. The
  only device-local route is an RRO replacing the whole layout, which would
  park a ~110-line copy of an AOSP file in this tree that goes stale silently,
  with no build error when upstream changes it. Not worth it for a cosmetic
  tweak. Patching `frameworks/base` is not an option either: it is LineageOS
  upstream, not one of our forks, so `repo sync` would revert it

### Planned workstreams

Detail and sequencing in [roadmap.md](roadmap.md).

- [ ] **Confirm GApps survive a ROM flash.** `POSTINSTALL_PATH_system` runs
      `backuptool_postinstall.sh`, and MindTheGapps installs
      `/system/addon.d/30-gapps.sh` (`ADDOND_VERSION=3`, standard `list_files()`,
      no `/sdcard` paths). Test: flash a ROM and do not reflash GApps
- [x] ~~Generate signing keys~~ — done 2026-08-05, eight RSA-2048 keys in
      `vendor/lineage-priv/keys/` on laptop and build server. The path is what
      makes them `release-keys` rather than `dev-keys`; see
      [roadmap.md](roadmap.md)
- [ ] **First signed build.** Every APK gets re-signed, so this **requires a
      factory reset**. Batch it with the Magisk work, since re-signing also
      invalidates a patched boot image. Decide AVB at the same time — we ship
      `--flags 3`, which disables verification outright
- [ ] **Magisk, Zygisk, Play Integrity.** After signing. Magisk over KernelSU
      only because Zygisk is what integrity modules target. `STRONG` integrity
      is unreachable — hardware attestation. `DEVICE` needs spoofing and is
      eroding. Banking apps and Wallet will keep failing
- [ ] **SELinux enforcing.** Start collecting denials now while daily driving.
      Rebuild policy with `dontaudit` stripped before trusting any count. Most
      denials are labelling bugs — `genfs_contexts` for virtual filesystems,
      not `audit2allow` output pasted verbatim. Dropping
      `SELINUX_IGNORE_NEVERALLOWS` is separate from flipping enforcing and
      usually harder

### Cleanup, no boot needed

- [ ] `init.frogger.rc:33` sets
      `ro.media.xml_variant.profiles "_volcano_v1_${ro.boot.pbid}"`. Frogger has
      no `ro.boot.pbid`, so this expands to `_volcano_v1_` and the framework
      looks for `media_profiles_volcano_v1_.xml` — a name that cannot exist.
      Stock Frogger's init never sets this property at all, and the only profile
      blob we extract is plain `vendor/etc/media_profiles.xml` (none of stock's
      `_volcano_v1{,_Base,_Pro}` variants). The line is Asteroids inheritance and
      should almost certainly just be deleted, but it changes media/recording
      behaviour and camera is disabled, so it is untestable right now. Same dead-
      `pbid` class as the USB product string fixed in `init.frogger.hw.rc`
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

**Speaker protection — settled, see [audio.md](audio.md).** Stock's `1` was kept
initially; it made the speaker completely silent, and is now `0`. Protection
cannot be re-enabled without stock's `libar-pal.so`.

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

**It is no longer set** — it was removed once the device booted reliably, since
it hands unauthenticated adb to any USB host. If a build bootloops and this is
needed again, put it back in `lineage_frogger.mk` above the `vendor/lineage`
inherit; nowhere else works. It bought us the audio bootloop diagnosis.

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

## Flashing — sideload the OTA zip

**This is the standard procedure.** The build is a full A/B OTA — `payload.bin`,
`payload_properties.txt`, `care_map.pb`, an `otacert`, `ota-type=AB` — so
recovery installs every partition the payload covers, kernel, `dtbo` and
`vendor_dlkm` included. Kernel and device-tree changes ship this way; there is
no need to touch individual images.

```sh
adb reboot sideload            # or: recovery > Apply update > ADB sideload
adb sideload lineage-23.2-<date>-UNOFFICIAL-frogger.zip
```

Recovery writes to the inactive slot and switches to it on success, so the
slot discipline that mattered during bring-up is handled for us.

### Flashing GApps: reboot recovery in between

**Do not sideload the ROM and GApps back-to-back in one recovery session.** That
is the usual advice and it is wrong on A/B:

```sh
adb sideload lineage-23.2-<date>-UNOFFICIAL-frogger.zip
adb reboot recovery                      # <-- the step that matters
adb sideload MindTheGapps-<date>.zip
```

`update_engine` writes the ROM to the *inactive* slot and marks it active for
next boot, but recovery keeps running from the slot it booted. GApps installers
resolve their target from `ro.boot.slot_suffix` and recovery's own fstab —
MindTheGapps does exactly this:

```sh
CURRENTSLOT=`getprop ro.boot.slot_suffix`
SYSTEM_BLOCK=$(find_block "system")
mount -o rw "$SYSTEM_BLOCK" /mnt/system
```

So without the reboot it mounts the slot you are about to stop using, reports
success, and you boot the other one with no GApps. Observed 2026-08-05: Play
Store vanished after a flash, `/product/priv-app/` held only LineageOS apps, and
the only Google package left was an orphaned `com.google.android.gms` in
`/data/app` — `/data` being shared between slots is why that one survived.

Rebooting to system first and then back into recovery works too, and is what
recovered it.

Collect logs afterwards with [`tools/grab-logs.sh`](../tools/grab-logs.sh).

### Flashing individual images — fallback only

[`tools/flash-frogger.sh`](../tools/flash-frogger.sh) still exists and still
works, but it is **not** the normal path. Reach for it only when sideload is not
available: restoring stock, or recovering a device whose recovery is broken.

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

Everything below this point applies to that fallback path only. None of it is
a concern when sideloading.

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

* ~~`WITH_ADB_INSECURE := true` in `lineage_frogger.mk`~~ — **removed**, the
  device boots reliably and no longer needs adb without an authorisation
  dialog. It also left `ro.debuggable=1` by skipping
  `PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG`. Kept here for the notes below, which
  still apply if it ever goes back in.
  **Do not set `ro.adb.secure=0` directly** — `vendor/lineage/config/common.mk`
  already assigns it and Soong fails the build on duplicate sysprop assignments.
  It must also go in the **product** makefile, before the `vendor/lineage`
  inherit that reads it; in `BoardConfig.mk` it is silently ignored, because
  board config is evaluated after product config
* ~~`BOARD_API_LEVEL_PROP_OVERRIDE := 34`~~ — **not a hack, keep it.** The build
  warns that it is for testing only, but 34 is exactly what stock reports for
  `ro.board.api_level` and `ro.board.first_api_level`: Frogger's vendor
  partition was built at API 34 while the system is Android 16. Removing it
  would let the build derive 36 and misdescribe the vendor image. This is also
  why the vendor and odm fingerprints legitimately read `:14/`
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
rebase on upstream periodically.

**These trees build Frogger and nothing else.** Asteroids support was originally
kept on the grounds that it costs nothing and that removing it would conflict
with upstream forever. That is no longer the policy: where Asteroids-specific
code is in the way, delete it rather than widen a condition to admit both. The
merge conflicts are the accepted price. Nothing here needs to stay buildable for
the Phone (3a), so `CONFIG_NOTHING_IS_FROGGER` in `volcanoautoconf.h` needs no
guard either.

`LineageOS/android_hardware_nothing` is **not** the right repo despite the
matching name — it is the Phone (1)/(2) generation, with no `config.mk` and none
of the required HALs.

The Asteroids tree this was forked from is
`NullDebris/android_device_nothing_asteroids`; that org publishes a manifest at
`NullDebris/manifest` listing every dependency. Worth watching for changes.
