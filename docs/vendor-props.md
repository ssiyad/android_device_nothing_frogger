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

**Do not paste these in wholesale.** Some describe hardware we do not ship, some
refer to blobs we do not have, and some would enable code paths built from
generic CAF source that behaves differently from stock's blobs. The list is a
lead file, not a patch.

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

### Display — 28 props, applied but not the flicker fix

Includes the 20 `ro.vendor.display.*` calibration entries read by
`/vendor/lib64/libdpps.so`: `low_brightness_threshold`, `panel.type`,
backlight min/max, and the 22-point backlight/lux/nits curves. Applied on
2026-08-06 via a Magisk `post-fs-data.d` script and confirmed **not** to fix the
screen flicker — but they are stock calibration for hardware we ship, and belong
in `vendor.prop` regardless.

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

1. ~~**Audio feature flags**~~ — **skip**, only 4 of 81 are read and all are at
   their defaults
2. **Display calibration** — already trialled on device, just needs committing to
   `vendor.prop`. `libdpps.so` demonstrably reads these
3. **`ro.hwui.use_vulkan` / `enable_gl_backpressure` / `set_idle_timer_ms`** — align
   with stock while the flicker is still open
4. **Glyph** — verify the Glyph HAL/app reads them first, then set before the LEDs
   are exercised, so failures are not misread
5. **`ro.vendor.nothing.*`** — 65 flags, needs case-by-case reading

Each group should be a separate commit, so a regression can be bisected to a
subsystem rather than to one 261-line change.
