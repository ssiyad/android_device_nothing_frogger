# Camera image quality

Why photographs from this device look the way they do, and which levers exist.
Read this before treating soft or smeared output as a porting fault.

## The image quality lives in the camera app, not the HAL

`system_ext/priv-app/NTCamera/NTCamera.apk` is 166 MB and carries **79 native
libraries** that are the entire Nothing imaging pipeline:

```
libsuperNight  libturbonight  libturboNightFront  libhdrcapture  libhdrcaptureV2
libhdr5capture  librawhdrcapture  libRawDeepDenoise  libRawDeepDenoiseV2
libsuperResolution  libsuperResolutionRaw  libdarkVision  libsuperPortrait
libsinglecamBokehV3  libdualCamBokeh  libfacialRestoration  libportraitRepair
libaimoon  libmorpho_ImageRefiner  libmorpho_RPS  libjni_arcsoft_camera_pp_engine
```

None of that is in `vendor/`. It runs inside the app's own process. **Any app
that is not NTCamera gets CamX output and nothing else** — which is the whole
explanation for the quality gap, and why GCam was no better on stock firmware.

The vendor half of Nothing's stack ships complete -- all twenty-four
`com.nothing.node.*` CHI nodes, every `libntcam*` library, and
`libntofflinepostproc.so` -- and is unreachable, for the reasons below.

## The CHI nodes cannot be switched on from an application

Twenty-four `com.nothing.node.*` CHI nodes ship in this ROM, and the HAL
registers a vendor tag for most of them that looks like the switch:
`com.nothing.camera.rawhdr.enable`, `.night.mode`, `.portrait.enable`,
`.ldc.enable`, `.frt.enable`. All are settable by an ordinary third-party
application -- none throws, on session parameters or on the request -- so the
mechanism is open.

It changes nothing. `tools/MaxResTest` captures with all five set and again with
none, on the same camera at the same size, and the two sessions are
indistinguishable:

| | feature graph | `com.nothing.node.*` instantiated |
|---|---|---|
| all five tags set | `RTMFNRJPEG` | 0 |
| control, no tags | `RTMFNRJPEG` | 0 |

Read with `chiLogInfoMask` raised, so node instantiation would have been visible.
CHI selects its usecase graph without consulting these tags, and no graph it
selects for an ordinary session contains a Nothing node. The tags are accepted
and inert, exactly as `com.nothing.camera.remosaic.enable` was.

That closes the last route that would have exposed the OEM processing to other
camera apps without shipping OEM application code.

## Nothing's own service is unreachable from any normal app

`vendor.noth.hardware.camera-service` is absent — the binary, its
`-service-impl.so` and `-V1-ndk.so`, its `.rc` and its VINTF manifest. Its
consumers all ship. `libntcamextened.so` is the reader of `decision.json`,
`nothing_pipeline.bin` and `nothing_node.bin`.

Shipping those five files would not help Aperture. **CamX never calls
`INtCamService`** — neither `camera.qcom.so`, `camera.qcom.milos.so` nor
`com.qti.chi.override.so` names it. Only the `libntcam*` family, the two
`com.nothing.node.{p2y,filtereditor}` CHI nodes and NTCamera itself do. It is an
offline post-processing service the app submits frames to, not something in the
capture path.

## CameraX extensions are broken in the firmware itself

The device ships the plumbing for CameraX/Camera2 extensions, and it is Nothing's
real implementation rather than the AOSP sample the filename suggests —
`NightAdvancedExtenderImpl`, `HdrAdvancedExtenderImpl`,
`BokehAdvancedExtenderImpl`, `com.nothing.algolib.offlineproc.OfflinePostProc`.

It cannot work, on this ROM or on stock:

```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libcamxextension_night.so" not found
  at com.nothing.algolib.supernight.SuperNightUtil.<clinit>
  at androidx.camera.extensions.impl.advanced.NightAdvancedExtenderImpl.<clinit>
  at com.android.cameraextensions.CameraExtensionsProxyService.initializeAdvancedExtensionImpl
```

`libcamxextension_night.so` and `libcamxextension_algo.so` exist in **no partition
of the stock OTA**. The only trace of either is a label in
`vendor/etc/selinux/vendor_file_contexts`. Nothing shipped the policy entry and
the Java caller but never the library.

Everything else in that path is healthy, so do not re-diagnose it: the extender
jar is linked (`dumpsys package org.lineageos.aperture` lists it under
`usesLibraryFiles`), the version handshake passes (vendor 1.5.0,
`isAdvancedExtenderImplemented` true), and `InitializerImpl.init` only calls the
success callback. Aperture hides its `effectButton` because
`supportedExtensionModes.size > 1` is false — not because of its ZSL clause,
which needs `enableZsl`, defaulting to false.

Installing NTCamera does not revive this. The failure is in a static initializer
that runs before the extender ever binds to the app's
`ExtensionsInterfaceProxyImplService`.

## 50 MP: the HAL computes it, then publishes a filtered copy

The full sensor modes are advertised only under a vendor tag. Comparing the two
lists on the wide camera settles what kind of restriction this is:

```
android.scaler.availableStreamConfigurations   178 entries, max 4080x3072
nothing.scaler.availableStreamConfigurations   225 entries, max 8160x6144
entries in android that are absent from nothing: 0
```

**The Android list is a strict subset.** The HAL builds the complete
configuration list, publishes a reduced copy to Android, and keeps the full one
in `nothing.scaler.availableStreamConfigurations` (`0x80c50000`). The 47 extra
entries are the full-size modes — `8160x6144`, `8160x4592`, `6560x4928`,
`6144x6144` and so on — offered in formats 32 (`RAW_SENSOR`), 33 (`BLOB`/JPEG),
34, 35, 36, 37 and 54. Both a 50 MP JPEG and a 50 MP RAW are genuinely supported
by the hardware.

The split is exactly the quad-bayer 2x2 relationship, and it appears on precisely
the three QCFA sensors:

| Camera | Sensor | `android.` max | `nothing.` max |
|---|---|---|---|
| 0 | logical | 4080x3072 | 8160x6144 |
| 1 | S5KKD1 front | 3280x2464 | 6560x4928 |
| 2 | IMX355 ultrawide | 3280x2464 | 3280x2464 |
| 3 | S5KJN5 tele | 4096x3072 | 8192x6144 |
| 4 | S5KGN9 wide | 4080x3072 | 8160x6144 |

The non-QCFA ultrawide has no remosaic mode, and its two lists are identical —
which is what confirms the extra entries are the remosaic full-size set rather
than an arbitrary allowlist.

### The maximum-resolution family is populated except for one tag

The Android 12 ultra-high-resolution path is not merely absent — it is built and
then left one tag short:

| Tag | State |
|---|---|
| `availableStreamConfigurationsMaximumResolution` (`d0014`) | **never populated** |
| `availableMinFrameDurationsMaximumResolution` (`d0015`) | populated, 3 cameras |
| `availableStallDurationsMaximumResolution` (`d0016`) | populated, 3 cameras |
| `availableInputOutputFormatsMapMaximumResolution` (`d0017`) | populated, 3 cameras |

The populated ones carry real full-size values — the front camera lists
`6560x4928` at a 66225165 ns minimum frame duration, about 15 fps. `d0014` is
also named in `availableCharacteristicsKeys`, so the HAL declares the key and then
never fills it.

Every companion needed to describe full-resolution capture is therefore present
and self-consistent. The single missing piece is the list that would make it
usable, and `android.request.availableCapabilities` correspondingly omits
`ULTRA_HIGH_RESOLUTION_SENSOR`. There is no `sensorPixelMode` request key, so as
shipped the API does not apply and `cameraserver` rejects `8160x6144` before the
HAL sees it.

That shape matters: it means the fix is to supply one omitted tag whose
neighbours already agree with it, not to invent a capability.

Supplying it is what the reverted `frameworks/av` patch did, and it worked: with
it the three quad-bayer sensors advertised full resolution through the standard
API, and the HAL then refused the configuration anyway. The sizes, for
reference:

| Camera | Sensor | `d0014` entries | Largest |
|---|---|---|---|
| 1 | S5KKD1 front | 55 | 6560x4928, 32.3 MP |
| 3 | S5KJN5 tele | 39 | 8192x6144, 50.3 MP |
| 4 | S5KGN9 wide | 47 | 8160x6144, 50.1 MP |

Formats 32 (`RAW_SENSOR`) and 33 (`BLOB`/JPEG) are both offered at the largest
size, so full-resolution DNG and JPEG are equally available. Cameras 0 and 2 are
untouched: the logical camera lacks the sensor-info companions, and the ultrawide
is not quad-bayer.

### CameraX cannot reach these, so neither can Aperture

This is a documented refusal rather than a gap to work around.
`ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE` selects from the
default-pixel-mode map only, and its own javadoc says of the maximum resolution
sensor pixel mode: "This mode does not allow applications to select those ultra
high resolutions."

So the modes are reachable only by a Camera2 client that sets
`SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` and configures against
`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`. Aperture is a CameraX app;
changing its `ResolutionSelector` will not help, and CameraX's surface
combination logic would reject the size even through Camera2 interop.

Note also that full-size configurations run below 20 fps -- the HAL's own
`d0015` says 66225165 ns, about 15 fps -- and `StreamConfigurationMap` files
anything that slow under `getHighResolutionOutputSizes()` rather than
`getOutputSizes()`. A client looking only at the latter finds nothing and
concludes the map is empty.

### The HAL refuses the configuration anyway

`tools/MaxResTest` asks for it directly, and CamX declines in its own words:

```
camxhaldevice.cpp:1781 CheckValidStreamConfig() format:33 max Res(4080 x 3072) requested Res(8160 x 6144)
camxhaldevice.cpp:2044 CheckValidStreamConfig() Invalid streamStype: 0, format: 33 (8160 x 6144)
```

Three things follow, and together they close the standard route:

- **CamX validates against the same filtered limit it publishes.** The filter is
  not only a publication filter; it is the HAL's real ceiling on the camera3
  path.
- **`SENSOR_PIXEL_MODE` is ignored.** CamX does not implement the Android
  maximum resolution pixel mode at all, which is consistent with its never
  populating `d0014` itself.
- **Nothing's own switch does not move it.** `com.nothing.camera.remosaic.enable`
  (`0x81090001`) is settable and was accepted into the session parameters;
  `CheckValidStreamConfig` rejected with the identical message. That tag is read
  by `libchifeature2.so` and `com.qti.chi.override.so`, and CamX core validates
  before CHI is consulted.

The capability is present deeper in the stack -- the CHI override carries a whole
XCFA usecase, with `QCFA sensor full size output: %dx%d` and
`[XCFA] XCFA usecase selected` -- but nothing reachable from an application gets
past `CheckValidStreamConfig` to it. Stock's own app avoids the problem by not
using this path at all: it goes through `libntcamera2ndk_vendor_v2.so` to the
provider directly.

### How much 50 MP would be worth, if it worked

Enough to want on the wide in good light, and very little anywhere else. The
optics decide this, and the numbers come from the HAL's own `physicalSize`
divided by `pixelArraySizeMaximumResolution`, against the Airy disk at 550 nm
(`2.44 x lambda x f-number`):

| | wide, GN9 | tele, JN5 |
|---|---|---|
| Aperture | f/1.88 | f/2.85 |
| Pixel pitch, full-res | 1.00 um | 0.64 um |
| Pixel pitch, binned | 2.00 um | 1.28 um |
| Airy disk | 2.52 um | 3.82 um |
| Pixels across Airy, binned | **1.26** | 2.99 |
| Pixels across Airy, full-res | **2.52** | 5.98 |

About two pixels across the Airy disk is where a sensor captures what its lens
delivers. The wide's binned output sits at 1.26, so it is under-sampling and real
detail is being discarded; full resolution at 2.52 is properly sampled. The
tele's binned output is already at 2.99, so full resolution there is close to
empty magnification -- four times the file for very little more information.

Three things cut against it even on the wide:

- **Quad-bayer interpolation.** At full size the filter is 2x2 same-colour
  clusters, so remosaic reconstructs a Bayer pattern rather than measuring one.
  The gain is real but well short of 4x.
- **Low light inverts the comparison.** Binning gathers four photodiodes into
  one pixel, roughly doubling SNR, and full size is capped near 15 fps and
  unlikely to keep the multi-frame path that runs today.
- **Resolution is not the current limit.** At ISO 900 the detail is destroyed by
  noise reduction, not missing for want of pixels. Full size would produce four
  times as many smeared pixels.

RAW at the binned size recovers more real detail than full size would, needs no
patches, and works now.

NTCamera reaches it through `libntcamera2ndk_vendor_v2.so`, a vendor camera2 NDK
that talks to the provider directly and bypasses that validation. That is a
vendor-domain client path, which is why the stock app has 50 MP and no ordinary
app can.

## Enumerating the CamX override knobs

`camxoverridesettings.txt` is read only from `/vendor/etc/camera`, and the names
it accepts are not documented anywhere in the tree. They can be recovered from
the settings-dump format strings, which carry every knob with its hash:

```sh
cd vendor/nothing/frogger/proprietary/vendor
for f in lib64/*.so lib64/hw/*.so; do strings -a -n 4 "$f"; done |
  grep -oE "%\*s[A-Za-z][A-Za-z0-9_]+ \(0x[0-9A-Fa-f]+\)" |
  sed 's/%\*s//; s/ (0x.*//' | sort -u
```

That yields **1107 knobs**, from `com.qti.settings.milos.so` (904) and
`camera.qcom.milos.so` (203). Neither `camera.qcom.so` nor
`com.qti.chi.override.so` contributes any.

The remosaic and QCFA family is the part that bears on full-size capture:

```
enableSensorRemosaic   enableSWMLRemosaic   remosaicType   noRemosaicCaptureMode
overrideForceFullSizeLiveshot   forceNumberOfPixelsPerColorForXCFASWRemosaic
enableInSensorZoom   enableCSIDBinning   CSIDBinningMode
```

**The advertised list is not settings-controlled.** With all of

```
enableSensorRemosaic=TRUE      enableSWMLRemosaic=TRUE
noRemosaicCaptureMode=FALSE    overrideForceFullSizeLiveshot=TRUE
enableCSIDBinning=FALSE
```

set at once, `android.scaler.availableStreamConfigurations` is unchanged on every
camera — identical entry counts (205/167/170/230/205) and identical maxima. The
settings were genuinely applied, not ignored; CamX echoes each accepted override
with its hash at provider start:

```
camxoverridesettingsfile.cpp:328 DumpData() enableSensorRemosaic (0xce48e159) = TRUE
```

That dump is the way to prove any override took effect, and it is worth checking
first whenever a knob appears to do nothing. A stock boot loads 60 overrides.

Whatever filters the list is not reachable from `camxoverridesettings.txt`.

## What the HAL actually does for a normal app

A still capture through Aperture is not the bare single-frame path it might look
like. Confirmed in a session log:

| Stage | Evidence |
|---|---|
| ZSL | `ZslEnable 1`, `ZSLYuv2Jpeg1` |
| Multi-frame denoise | `MFNRV2`, wired `Prefilter → Blend → PostFilter → JPEG` |
| HDR | `HDRCaptureRequest` |
| Ultra HDR output | JPEG carries `XMP-hdrgm` and a `GainMap` container item |

Aperture defaults `usePhotoJpegUltraHdr` to true, so gain-map HDR JPEGs are
already the normal output.

## The defect is tuning, and the Camera2 knobs do not reach it

At ISO ~900 the JPEG loses fine texture entirely — wood grain and fabric weave
become flat blobs. The sensor resolves that detail: a DNG from the *same frame*,
developed with default settings, keeps the weave and the grain. The smearing is
CamX's noise reduction, baked into the per-sensor tuning.

The Camera2 controls that ought to govern it are inert or fatal:

| Requested | Result |
|---|---|
| `noiseReduction.mode` / `edge.mode` = `HIGH_QUALITY` | Output is indistinguishable from default |
| `noiseReduction.mode` = `MINIMAL` or `OFF` | **Kills the camera device** |

`android.noiseReduction.availableNoiseReductionModes` advertises `[0 1 2 3 4]`,
so `OFF` and `MINIMAL` look supported. Requesting either ends the session with
`GRAPH_ERROR(cameraError=CameraError(ERROR_CAMERA_DEVICE))` and an
`ImageCaptureException: Camera is closed`; the service then re-adds all five
devices. Do not ship either as a default, and treat that advertised list as
untrustworthy.

**RAW is the working escape hatch.** `RAW_SENSOR` is advertised up to 4080x3072,
`enable_raw_image_capture` produces a 25 MB 16-bit CFA DNG beside the JPEG, and
the DNG carries detail the JPEG has thrown away. It is single-frame, so it trades
MFNR's noise advantage for that detail.

## Changing an override without a reboot

`/vendor` is ext4 under dm-verity and cannot be remounted writable, so an
override is tested by bind-mounting a copy over the real file in init's mount
namespace, then restarting the provider so it re-reads:

```sh
su -c 'cp /vendor/etc/camera/camxoverridesettings.txt /data/local/tmp/cxo.txt'
su -c 'echo enableSensorRemosaic=TRUE >> /data/local/tmp/cxo.txt'
su -c 'chcon u:object_r:vendor_configs_file:s0 /data/local/tmp/cxo.txt'
su -c 'nsenter -t 1 -m -- mount --bind /data/local/tmp/cxo.txt /vendor/etc/camera/camxoverridesettings.txt'
su -c 'setprop ctl.restart vendor.camera-provider'
```

The `chcon` matters — the provider runs in a vendor domain and a file left
labelled `shell_data_file` is unreadable to it. Undo with
`nsenter -t 1 -m -- umount /vendor/etc/camera/camxoverridesettings.txt`; nothing
survives a reboot.

Restarting `vendor.camera-provider` is the mild kind of service restart. It is a
lazy vendor HAL, `cameraserver` re-enumerates cleanly afterwards, and the same
remove/re-add cycle already happens whenever a session dies.

## Comparing camera apps

The comparison is only worth as much as its controls. What each of these guards
against has already gone wrong once.

**Fix the phone in place.** An early comparison drifted in framing between shots
and could not be read. Prop it, and drive everything over adb without touching
it.

**Record exposure with every frame** (`exiftool -ISO -ExposureTime`) and quote it
alongside any judgement. Indoor light drifts enough over minutes to swamp what is
being measured, and the apps meter differently -- GCam will happily choose a
slower shutter *and* a higher ISO than Aperture for the same scene, so the frames
are not equal-exposure even when the scene is identical.

**Normalise orientation exactly once.** Aperture writes no orientation tag; AGC
writes `Rotate 180`. Run `magick ... -auto-orient` on both and nothing else --
adding a `-rotate 180` on top of `-auto-orient` puts it back where it started.

**Judge at 100%, on texture.** Fine fabric weave, wire grilles and small print
show the difference; a downscaled frame hides it entirely.

### Finding what an app wrote

Each app saves somewhere different, and "no new file in `DCIM/Camera`" usually
means the wrong directory rather than a failed capture. Ask MediaStore:

```sh
adb shell 'content query --uri content://media/external/images/media \
    --projection _data:date_added --sort "date_added DESC"'
```

`LIMIT` is rejected by the provider, so take the head of the output instead.
Aperture writes `DCIM/Camera`; AGC writes `DCIM/AGC`, and writes a DNG beside
every JPEG.

### Driving GCam

`com.agc.gcam` (BigKaka's AGC) has a launcher activity and can be started from
adb, unlike `com.google.android.apps.googlecamera.fishfood`, which is not GCam at
all but LineageOS's `ApertureLensLauncher` stub claiming that package name.

```sh
adb shell 'am start -n com.agc.gcam/.app.CameraActivity'
adb shell 'uiautomator dump /sdcard/g.xml'    # com.agc.gcam:id/shutter_button
```

HDR+ takes ten to thirty seconds to write its result, so poll for the file rather
than sleeping. The provider logs the burst as it happens, which is the quickest
confirmation that a tap registered at all:

```
chxextensionmodule.cpp:8611 OverrideProcessRequest() HALOP:RAW SNAPSHOT START ... num_output_buffers 3
```

Its configuration lives in `/sdcard/Android/data/com.agc.gcam/files/`, which
holds `configs`, `luts` and `tuning`.

### What the comparison shows

In low light GCam is clearly ahead, and on a worse exposure. On one indoor scene
at night, Aperture at ISO 1590 1/33 against AGC at ISO 2384 1/25: wire grille
separated rather than smeared, small print legible, fabric weave retained instead
of waxy, and less shadow noise despite the higher ISO. Less noise at higher ISO
is the signature of burst stacking.

That is the expected shape of the result rather than a surprise -- HDR+ works
from RAW bursts and never touches the noise reduction that costs the CamX JPEG
its detail. It also means effort spent on GCam has a better return than effort
spent on the CamX path, which cannot be tuned from outside.

## Testing captures over adb

The device must be awake before `am start`, or the intent is accepted and the HAL
never starts — this silently produces "no new file" and looks like a settings
failure. Verify a capture by polling for a new file rather than by sleeping:

```sh
BEFORE=$(adb shell 'ls -t /sdcard/DCIM/Camera/*.jpg | head -1')
adb shell 'input keyevent KEYCODE_WAKEUP'
adb shell 'am start -n org.lineageos.aperture/.CameraLauncher'
sleep 8; adb shell 'input tap 612 2367'      # confirm bounds from a UI dump
```

Aperture's settings live in
`/data/data/org.lineageos.aperture/shared_prefs/org.lineageos.aperture_preferences.xml`
and can be written with root while the app is force-stopped. The app rewrites the
file on exit and drops keys it did not set, so back it up first.

Comparisons are only meaningful when the two frames have similar exposure — check
`exiftool -ISO -ExposureTime`. Indoor light drifts enough over a few minutes to
swamp the difference being measured.
