# Get the best achievable stills out of this camera

Background and measurements are in `reference/camera-image-quality.md`. The
short version: Nothing's processing lives inside `NTCamera.apk`, the CameraX
extensions route to it is broken in the firmware itself, and what remains for a
normal app is CamX with noise-reduction tuning that erases fine texture.

## Ship the zoom translator

`com.qualcomm.mcx.linearmapper.so` is shipped and sits in Aperture's rear path
(`MCXSuperFG`). It `dlopen`s `/vendor/lib64/libarcsoft_triple_zoomtranslator.so`,
gated on `vendor.camera.nothing.zoomtranslator.arcsoft`, and logs
`ZoomTranslatorProxy::Init failed` without it. Source is
`chi-cdk/oem/qcom/multicamera/chimcxlinearmapper/chimcxzoomtranslator.cpp`.

The blob list now names it. It needs a re-extraction on the builder to land, and
then a check of whether the `Init failed` line is gone and whether lens
transitions and framing across zoom improve.

## Reach 50 MP without depending on NTCamera

The hardware supports 50 MP JPEG and 50 MP RAW; the HAL computes the
configurations and then publishes a filtered subset to Android. See
`reference/camera-image-quality.md` for the measurement. Settings are ruled out,
so two routes remain, in the order they are worth trying.

**1. Complete the maximum-resolution characteristics in the framework.** Written,
as `patches/frameworks_av/0001-camera-Restore-the-hidden-maximum-resolution-modes.patch`.
It adds `addNothingUltraHighResolutionTags()` beside the `deriveHeicTags` /
`deriveJpegRTags` family, fills `d0014` with the configurations the vendor tag
has and the `android.` map does not, and advertises
`ULTRA_HIGH_RESOLUTION_SENSOR`. Gated on the maximum-resolution companions being
present, which holds only for the three quad-bayer sensors and for no other
device, so the logical camera and the ultrawide are untouched.

It is deliberately not merged into the ordinary
`android.scaler.availableStreamConfigurations`. Full-size modes belong behind the
maximum-resolution pixel mode; folding them into the default list would claim
they work in the default pixel mode, which is a different and probably untrue
thing to say, and would likely fail at `configure_streams`.

**What remains is a build.** The unproven step is whether the HAL accepts a
full-size configuration once the framework stops rejecting it. It is a
configuration the HAL computed and described, and NTCamera drives exactly these
sizes through the same provider, so the usecase exists. If `configure_streams`
still refuses, this route is dead and nothing is lost but the build.

Checks after flashing, cheapest first:

```sh
adb shell 'dumpsys media.camera' | grep -c "d0014"            # expect 3
adb shell 'dumpsys media.camera' | grep ULTRA_HIGH_RESOLUTION # expect 3 cameras
```

Then whether Aperture offers the larger size, and whether a capture at it
succeeds or the session dies.

This is the route that best matches "not vendor locked": no Nothing app, no
Nothing service, and the capability reaches every app through the standard API.

**2. A vendor-domain capture helper.** `libcamera2ndk_vendor.so` (AOSP's vendor
camera2 NDK, currently not shipped) or Nothing's `libntcamera2ndk_vendor_v2.so`
can talk to the provider directly and bypass `cameraserver` validation entirely —
this is how NTCamera gets 50 MP. A small service of our own in the vendor domain
could expose full-size capture on our terms.

It works, but it is a bespoke capture path that no ordinary app can use without
talking to it, so it trades one lock-in for another. Worth it only if the framework merge fails.

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
- **The Android 12 maximum-resolution API for 50 MP.** No
  `ULTRA_HIGH_RESOLUTION_SENSOR` capability, `availableStreamConfigurationsMaximumResolution`
  never populated, no `sensorPixelMode` request key.
- **CamX overrides for 50 MP.** The whole remosaic knob family was set at once,
  confirmed accepted in CamX's own override dump, and changed nothing about the
  advertised configurations on any camera. The filter is not in
  `camxoverridesettings.txt`.

## GCam

It opens physical camera IDs directly and bypasses MCX, SAT, every Nothing node
and the extensions path, so none of the above reaches it. It was equally poor on
stock firmware, which makes it a config-tuning exercise against these sensors
rather than a bring-up gap. Keep it a separate track; the device-tree lever worth
confirming is that RAW10/`RAW_SENSOR` ZSL streams are advertised on the physical
IDs.
