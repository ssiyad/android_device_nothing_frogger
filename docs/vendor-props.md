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

### Audio — reopens the speaker-protection question

```
vendor.audio.feature.spkr_prot.enable   = true
vendor.audio.feature.wsa.enable         = false     ← stock disables WSA
persist.vendor.audio.spv3.enable        = true
vendor.audio.feature.dsm_feedback.enable = false
vendor.audio.feature.external_speaker.enable = false
```

[audio.md](audio.md) records speaker protection as a dead end, partly because
enabling it drove `WSA_AIF_VI` mixer controls for a WSA883x this device does not
have. **Stock turns that feature off by property.** We never set it, so our AHAL
had no way to know.

This does not mean protection will work if we set these — the missing
`aw882xx_pid_2329_monitor.bin` is a separate and harder problem — but the
conclusion that the WSA mismatch is unavoidable was reached without knowing stock
had a switch for it. Worth retesting `speaker_protection_enabled=1` with
`vendor.audio.feature.wsa.enable=false` set.

Also absent: the whole `persist.audio.fluence.*` and
`persist.vendor.audio.fluence.*` family, which governs mic noise reduction for
calls and recording. Relevant to the LVACFS work, where we restored stock
behaviour by *disabling* processing.

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

1. **Audio feature flags** — largest group, and directly bears on an open item
2. **Display calibration** — already trialled, just needs committing to `vendor.prop`
3. **`ro.hwui.use_vulkan` / `enable_gl_backpressure` / `set_idle_timer_ms`** — align
   with stock while the flicker is still open
4. **Glyph** — before the LEDs are exercised, so failures are not misread
5. **`ro.vendor.nothing.*`** — 65 flags, needs case-by-case reading

Each group should be a separate commit, so a regression can be bisected to a
subsystem rather than to one 261-line change.
