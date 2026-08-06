# Vendor properties vs stock

Our `vendor/build.prop` carries **217** properties. Stock's carries **499**.
**261 of stock's are missing from ours**, and 16 more differ in value.

The full list with stock's values is in
[data/vendor-props-missing.txt](data/vendor-props-missing.txt).

This is almost certainly Asteroids inheritance: `vendor.prop` came across with the
device tree and was never reconciled against Frogger's own stock image.

## Method

```sh
adb shell su -c 'cat /vendor/build.prop'          # ours, as shipped
~/sources/android/downloads/firmwares/frogger/extracted/vendor/build.prop   # stock
```

Diff by key. The stock dump is `B4.1-260309-1830`, the same build our blobs come
from, so the comparison is like-for-like.

## Missing, by subsystem

```
81  audio          the entire vendor.audio.feature.* family, plus Fluence
65  nothing        ro.vendor.nothing.* device feature flags
28  display        calibration; see below
20  glyph          LED controller config -- brightness tables, channels, segments
10  sys
 4  sf / surface_flinger
 4  newAAC
 3  qcom, product, bt
```

**The gap is largely a mirage.** Both groups investigated so far — audio (81) and
display (28) — turned out to be **inert**: their consumers are Nothing's
proprietary framework and HAL components, which LineageOS does not ship, or
legacy QCOM HALs for other SoCs that we do not build. Copying the properties
achieves nothing except the appearance of configuration.

Treat this file as a lead list, never a patch, and **always find the reader
first**.

## The ones that matter

### Audio — 81 missing, and **not worth applying**

This group looked like the biggest prize. It is not. Checked 2026-08-06 by
grepping what our source actually reads:

**Of the 81, exactly four are read by our audio stack**, and all four are already
at their default:

```
vendor.audio.compress_capture.aac.cut_off_freq = -1
vendor.audio.feature.dmabuf.cma.memory.enable  = false
vendor.audio.hdr.record.enable                 = false
vendor.audio.safx.pbe.enabled                  = false
```

`property_get_bool(x, false)` already yields those values when the property is
absent, so setting them changes nothing.

The rest belong to the **legacy `audio_extn` HAL**, which we do not build. The
`vendor.audio.feature.*` family is read only by
`hardware/qcom-caf/{msm8953,sdm845,sm8150,sm8250}/audio/hal/audio_extn/`, and
`persist.audio.fluence.*` only by `hardware/qcom/audio/hal/msm8974/platform.c`.
Frogger builds `hardware/qcom-caf/sm8650/audio` — the PAL/AGM stack — which reads
none of them. No shipped vendor blob references them either.

Nothing's `vendor.prop` inherits them from a QCOM template; they are vestigial in
stock too, since stock also runs a PAL-based HAL.

> **A retracted claim.** An earlier version of this document said
> `vendor.audio.feature.wsa.enable=false` meant stock "turns WSA off by property"
> and that this reopened the speaker-protection dead end in [audio.md](audio.md).
> It does not. Our AHAL never reads that property, so setting it would do nothing.
> The WSA mismatch and the missing `aw882xx_pid_2329_monitor.bin` both stand
> unchanged.

**Conclusion: skip the audio group.** Adding 81 properties that nothing reads
would be noise, and worse, would look like configuration when it is not.

### Display — 28 props, also inert

The 20 `ro.vendor.display.*` calibration entries (`low_brightness_threshold`,
`panel.type`, backlight min/max, 22-point backlight/lux/nits curves) were applied
on device via a Magisk `post-fs-data.d` script and **did not** fix the screen
flicker. Chasing why produced the reason, and it generalises.

Their only consumer in the entire stock image is
**`/system/framework/nt-services.jar`** — Nothing's framework services. Grepping
the unpacked stock partitions finds these strings in exactly two other places:
`vendor/build.prop`, where they are defined, and `vendor_property_contexts`,
which labels them. No native binary reads them.

**We do not ship `nt-services.jar`.** We ship `nothing-fwk.jar` and
`nt-telephony-interface.jar`; nothing we ship reads these properties.

> An earlier version of this document said `libdpps.so` reads them. It does read
> `ro.vendor.display.*` properties — `cabl`, `paneltype`, `foss`, `sensortype`,
> `ad.sdr_calib_data` — but **none of the 20**. That claim came from grepping the
> prefix rather than the keys.

The 8 `vendor.display.*` entries fare no better: none appear in
`hardware/qcom-caf/sm8650/display`, which is the display HAL we build from
source, nor in any shipped blob.

**One real difference remains**, and it should be left alone:

```
vendor.display.enable_rounded_corner   ours=1   stock=0
vendor.display.enable_ic_hw_roundedcorner  (stock only) = 1
```

`enable_rounded_corner` *is* read by our display HAL
(`include/display_properties.h:144`). Stock pairs `0` with hardware rounded
corners via `enable_ic_hw_roundedcorner`, which our HAL does not read — so
setting `0` to match stock would disable rounded corners with no hardware
fallback. Ours is correct as it stands.

**Conclusion: skip the display group too.** Remove the Magisk trial script.

### Glyph — 20 props, and the LEDs are unexercised

`ro.vendor.glyph.channels=6`, `segments=6`, per-group brightness tables,
music-visualiser limits. We ship a `glyph_app` sepolicy domain and have never
tested the LEDs. If Glyph misbehaves, start here rather than in policy.

### Value differences worth attention

```
ro.hwui.use_vulkan                   ours=true        stock=(unset)
debug.sf.enable_gl_backpressure      ours=0           stock=1
ro.surface_flinger.set_idle_timer_ms ours=3000        stock=550
ro.bionic.cpu_variant                ours=cortex-a76  stock=kryo300
dalvik.vm.isa.arm64.variant          ours=cortex-a76  stock=kryo300
vendor.audio.offload.buffer.size.kb  ours=256         stock=32
vendor.display.enable_rounded_corner ours=1           stock=0
```

The first three are all rendering/display timing and all differ from stock, which
is notable given the flicker investigation — stock does **not** enable Vulkan for
HWUI. The `cpu_variant` pair affects code generation for the wrong core family.

Deliberate and correct to keep: `ro.vendor.build.version.sdk` (ours 36, stock 34
— vendor is legitimately built against an older API under Treble),
`ro.vendor.build.type`, and the LineageOS default ringtones.

`ro.product.first_api_level` is ours=35, stock=36. Ours is deliberate — see
`device.mk`, where 36 enabled a 16KB-page check that rejected 4K-page blobs.

## Suggested order

**Check what reads a property before adopting it.** The audio group was the
largest and turned out to be entirely inert. The same test applies to every group
below:

```sh
grep -rl "<prop>" --include=*.c --include=*.cpp --include=*.h --include=*.rc \
    hardware/ vendor/nothing/ device/ frameworks/
strings -a <shipped blob> | grep <prop>
```

1. ~~**Audio feature flags**~~ — **skip**, 4 of 81 are read and all already hold
   the code's default
2. ~~**Display calibration**~~ — **skip**, sole consumer is `nt-services.jar`,
   which we do not ship. Verified on device: applying them changed nothing
3. **`ro.hwui.use_vulkan` / `enable_gl_backpressure` / `set_idle_timer_ms`** — these
   are AOSP properties with readers in the platform, so unlike the groups above
   they will actually take effect. Worth aligning with stock while the flicker is
   open
4. **Glyph** — expect the same outcome; the Glyph app is Nothing's and we do not
   ship its framework side. Check before spending time
5. **`ro.vendor.nothing.*`** — 65 flags, almost certainly read by `nt-services.jar`

Each group should be a separate commit, so a regression can be bisected to a
subsystem rather than to one 261-line change.
