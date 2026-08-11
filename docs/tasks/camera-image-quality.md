# Get the best achievable stills out of this camera

Background and measurements are in `reference/camera-image-quality.md`. The
short version: Nothing's processing lives inside `NTCamera.apk`, the CameraX
extensions route to it is broken in the firmware itself, and what remains for a
normal app is CamX with noise-reduction tuning that erases fine texture.

## Ship the zoom translator

`com.qualcomm.mcx.linearmapper.so` is shipped, is loaded in the provider during
an Aperture session, and `dlopen`s
`/vendor/lib64/libarcsoft_triple_zoomtranslator.so`, which is not on the device.
The library is ArcSoft's triple-camera optical zoom control (`ARC_TCOZCTRL_*`):
it maps a requested zoom ratio onto the crop and field of view of whichever
sensor is streaming, keeps preview and snapshot framing in agreement through
separate handles, and reserves the FOV margin the alignment transform needs.

The stack does want it. With `chiLogInfoMask` raised, opening the camera logs

```
[ INFO][MCXCore] chimcxzoomtranslator.cpp:54 Create() Nothing ZoomTranslator, need = 1
```

**No failure is logged, and no symptom has been observed.** Nothing follows that
line -- no `dlopen` failure, no `cannot found dlsym`, no
`ZoomTranslatorProxy::Init failed` -- across lens transitions in both directions,
and the gate property `vendor.camera.nothing.zoomtranslator.arcsoft` is unset
here and in every stock `build.prop`. So the case for shipping it is that code we
ship asks for a library by name that is absent, not that anything measurably
misbehaves.

The blob list names it; it needs a re-extraction to land. Judge it afterwards on
framing continuity across a lens switch and on preview matching the captured
frame, since that is what the library governs.

## Reach 50 MP without depending on NTCamera

Parked, and the standard route is closed. The hardware supports 50 MP JPEG and
RAW, the HAL computes those configurations, and it then refuses to accept them
from any camera3 client -- `CheckValidStreamConfig` validates against the binned
ceiling, ignores `SENSOR_PIXEL_MODE`, and is unmoved by Nothing's own
`com.nothing.camera.remosaic.enable`. `reference/camera-image-quality.md` has the
evidence and `tools/MaxResTest` reproduces it in one command.

Completing the maximum resolution characteristics in `frameworks/av` worked
exactly as intended and is still the wrong thing to ship, because it advertises a
capability the HAL then rejects: an app that believes
`ULTRA_HIGH_RESOLUTION_SENSOR` fails at `configure_streams` instead of falling
back. It has been reverted for that reason, not because it was wrong about the
metadata.

Two routes remain, neither cheap, and the optics argue against urgency -- full
size is worth having on the wide in good light and close to worthless on the
tele, and it would not touch the noise-reduction smearing that actually limits
these photographs.

**Binary-patch `camera.qcom.so`** to lift the ceiling. It would work for every
app, but the blob is re-extracted from the OTA on every sync, so it needs a
patch step wired into extraction, and a camera HAL is an unforgiving thing to
patch without source.

**A vendor-domain capture helper** built on `libcamera2ndk_vendor.so` or
Nothing's `libntcamera2ndk_vendor_v2.so`, which is how NTCamera does it. It
works, but no ordinary app can use it without talking to our service, so it
trades one lock-in for another and cuts against the reason for doing any of this.

## Decide what to do about NTCamera

This is the only route to stock-quality output, and it is a real port, not a
drop-in. Installed as a plain user app it crashes twice:

- `NothingExperience.logEvent` — the analytics SDK.
- `UFSManager.connectNtCameraServiceLocked` — an NPE on a reflected method that
  resolves to null, reaching for Nothing's framework additions and
  `vendor.noth.hardware.camera`.

So it needs priv-app placement with a permission allowlist (`SYSTEM_CAMERA`,
`FOREGROUND_SERVICE_CAMERA`, `com.nothing.ketchum.permission.ENABLE`), the five
`vendor.noth.hardware.camera-service` files plus a sepolicy domain, and shims for
whatever framework surface it reflects into. `nothing-fwk/` already exists as a
place for the last of those.

It also registers itself as the default `STILL_IMAGE_CAMERA` handler, so it
cannot be installed speculatively on a daily driver without hijacking the camera
button.

Worth settling explicitly whether the goal is "Aperture as the UI" — in which
case this does not serve it, because the processing is inside NTCamera's own
process and reachable only by its own UI — or "stock-quality photographs on this
phone", which this does serve.

## Make RAW a practical default

RAW is the one lever that demonstrably recovers the detail CamX discards, and it
works today: `enable_raw_image_capture` writes a DNG beside every JPEG.

Open questions before recommending it as a shipped default rather than a setting
a user turns on:

- Storage. 25 MB per shot.
- Whether the shipped Gallery renders DNG acceptably.
- Whether a Lineage-side default is appropriate at all, given the JPEG is what
  most captures want.

## Do not chase these

Recorded so they are not re-tried:

- **CameraX extensions.** `libcamxextension_night.so` is in no partition of the
  stock OTA. Not fixable by extracting more blobs.
- **`noiseReduction.mode` = `OFF` or `MINIMAL`.** Advertised as available; kills
  the camera device.
- **`noiseReduction.mode` / `edge.mode` = `HIGH_QUALITY`.** Output is
  indistinguishable from default.
- **`photo_capture_mode` = `maximize_quality`.** The HAL path is byte-for-byte
  the same graph; no measured difference.
- **Aperture, or anything else built on CameraX, for 50 MP.** CameraX selects
  from the default pixel mode only and documents that the maximum resolution
  sensor pixel mode "does not allow applications to select those ultra high
  resolutions". Adjusting its `ResolutionSelector` is not a way round that.
- **CamX overrides for 50 MP.** The whole remosaic knob family was set at once,
  confirmed accepted in CamX's own override dump, and changed nothing about the
  advertised configurations on any camera. The filter is not in
  `camxoverridesettings.txt`.
- **Switching on the Nothing CHI nodes with their vendor tags.** All of
  `rawhdr`, `night`, `portrait`, `ldc` and `frt` are settable by a third-party
  app and accepted on both session parameters and request. A capture with all
  five set and a control with none produce the same feature graph, `RTMFNRJPEG`,
  and no `com.nothing.node.*` in either. CHI does not consult them when choosing
  a graph.
- **Re-adding the `frameworks/av` maximum-resolution patch on its own.** The
  metadata it wrote was correct and the HAL still refuses the configuration, so
  it only makes the device claim something it cannot do. It is worth restoring
  only alongside a way past `CheckValidStreamConfig`.

## GCam

It opens physical camera IDs directly and bypasses MCX, SAT, every Nothing node
and the extensions path, so none of the above reaches it. It was equally poor on
stock firmware, which makes it a config-tuning exercise against these sensors
rather than a bring-up gap. Keep it a separate track; the device-tree lever worth
confirming is that RAW10/`RAW_SENSOR` ZSL streams are advertised on the physical
IDs.
