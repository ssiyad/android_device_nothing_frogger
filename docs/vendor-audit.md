# Vendor file audit vs stock

Run 2026-08-08, after the QDCM calibration bug — the third fault caused by a
source-built HAL missing a device-specific data file that stock ships. Done
before camera work, since camera is the largest consumer of vendor data.

Raw list: [data/vendor-missing.txt](data/vendor-missing.txt).

**Compared against the live device, not the blob tree.** Diffing
`vendor/nothing/frogger/proprietary` would have reported 603 missing files in
`/vendor/etc` alone, most of which we ship from the device tree instead. Against
the device the figure is 470, and it means something.

```
/vendor/etc      stock 1163   device  734   missing 470
/vendor/lib64    stock 1382   device 1133   missing 364
/vendor/firmware stock   67   device   44   missing  23
/vendor/bin      stock  268   device  135   missing 141
```

## Display: complete

All 28 remaining `display/` files are calibration for **other panels** — Sharp,
r66451, vtdr6130, ft8726, nt36672e — or other DPU variants. None apply to
Frogger. The `nt37706a` gap we fixed was the only real one.

## Camera: the audit found a genuine lead, but not the crash

`/vendor/etc/camera` is 23 files and all cosmetic: colour-filter resources for
the stock camera app (amber, analog, chrome) and two face-detect models. Not HAL
data.

`/vendor/lib64` is where it matters. Missing and **not listed in
`proprietary-files.txt` at all**:

```
camera/components/com.morpho.node.gme.so     <- camera.md says the pipeline needs this
camera/components/com.morpho.node.eisv2.so
camera/components/com.morpho.node.eisv3.so
camera/node/com.nothing.node.filtereditor.so
libmorpho_video_stabilizer.so, libmorpho_ubwc.so
libarcsoft_triple_sat.so, libarcsoft_triple_zoomtranslator.so, and others
libcameradecision.so, libsensorcal.so
vendor.noth.hardware.camera-V1-ndk.so, vendor.noth.hardware.camera-service-impl.so
```

`com.morpho.node.gme.so` is a confirmed requirement: [camera.md](camera.md)
records `MultiCameraBayerSATNoBPSFrogger0_0_cam_2` needing `com.morpho.node.gme`.

**It does not explain the `swpnc` crash.** All of that node's direct
dependencies are present:

```
libcamxswispiqmodule.so  libcom.qti.chinodeutils.so  libcamxcommonutils.so
libcamximageformatutils.so  libchilog.so  libcamera_metadata.so
```

So `LoadLib()` is not failing on a missing `NEEDED` library. A guess that
`libmmcamera_pnc.so` was the missing piece — it is absent, and the node is
`com.qti.node.swpnc` — was wrong: it does not appear in the node's dependencies
at all.

Nothing is listed-but-absent anywhere in `/vendor/lib64`, so extraction is
self-consistent. These files were simply never listed.

## Everything else: cosmetic or deliberately not shipped

```
98  richtapresources/   RichTap haptic effect files (.he) for Nothing UI events
72  customringtone/     Nothing ringtones
51  init/               rc files for services we do not run
36  whisper/            OpenCC Chinese conversion data
24  res/images/charger/ stock charger animation
23  camera/             stock camera app filters
18  vintf/              manifests our build generates
11  permissions/        LineageOS ships its own
 6  nt_performance/     Nothing perf tuning, read by components we do not ship
 4  audio/sku_volcano_qssi/  a sku directory we do not use
```

`richtapresources` is the only group here that might be noticed: those are the
haptic effect definitions for Nothing's own UI. Haptics work, so this is
richness rather than function.

## Conclusion

The audit was worth running and is now closed for display. For camera it
produced a concrete next step — ship the Morpho nodes, `com.morpho.node.gme.so`
first — but it did **not** find the cause of the provider crash. That remains
open and is the first thing camera work should tackle, not the missing libs.
