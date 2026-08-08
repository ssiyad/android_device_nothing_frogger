# Display

## Night Light and colour inversion — fixed

**Symptom:** Night Light had no visible effect. Accessibility colour inversion
was also dead, which is what identified the real fault: it is not a Night Light
bug but a broken colour pipeline shared by both.

**Cause: we shipped QDCM calibration for the wrong panel.**

```
panel        "nt37706a amoled vid mode dsi BOE panel with DSC"
needs        qdcm_calib_data_nt37706a_amoled_vid_mode_dsi_BOE_panel_with_DSC.json
we shipped   qdcm_calib_data_rm69220_*   (a different panel entirely)
```

With no calibration for the panel present, the display colour manager never
initialises. It shows in `dumpsys SurfaceFlinger` as uninitialised sentinels:

```
Current Color Mode: gamut 255 gamma 255 intent 65535     (0xFF / 0xFFFF)
ColorMode::NATIVE only,  wideColorGamut=false
```

**Why it failed in both composition paths.** The HWC unconditionally advertises
`SKIP_CLIENT_COLOR_TRANSFORM` for internal displays
(`composer/AidlComposerClient.cpp:318`), whose stated purpose is:

> Since DSPP would apply color transform on the final composed output, this is
> needed to prevent applying color transform twice.

SurfaceFlinger honours that (`CompositionEngine/src/Display.cpp:314`) and skips
applying the matrix in RenderEngine. So DSPP was expected to do it and could
not, while SurfaceFlinger deliberately would not. Forcing GPU composition
(`service call SurfaceFlinger 1008 i32 1`) changed nothing, which is what proved
the matrix was being lost above the HWC rather than inside it.

**Fix:** ship the `nt37706a` calibration for both BOE and Visionox variants.
Added to `proprietary-files.txt`; the `rm69220` files are kept since the device
tree defines those panel variants too.

## Also fixed: an invalid SurfaceFlinger colour setting

We shipped `persist.sys.sf.native_mode=260`, inherited from Asteroids.
SurfaceFlinger casts that straight to an enum with no validation
(`SurfaceFlinger.cpp:1169`). Valid values are 0-2; anything else falls to

```c
default: // vendor display color setting
    intent = static_cast<ui::RenderIntent>(refreshArgs.outputColorSetting);
```

so the render intent became 260. Vendor render intents start at 256 and stock's
HAL advertises 260, but ours advertises only `COLORIMETRIC`, so every frame
logged `map unknown (BT709 sRGB Full range)/(Unknown RenderIntent) to default
color mode`. Set to 0 (`kManaged`).

This was a real bug but **not** the cause of Night Light failing — fixing it
cleared the log flood and left the symptom unchanged. It is a persist property,
so a device that already holds 260 in `/data` needs one `setprop` to correct.

## Screen flicker — fixed

See devicetrees `726bba1c`. The 30Hz DFPS entry was not seamless: 120/90/60
share a pixel clock of 442,640,160 by stretching the vertical front porch, while
30Hz instead inflated the horizontal porch and halved the clock, forcing a DSI
PLL reprogram. 30Hz is removed from `qcom,dsi-supported-dfps-list`.

## Pattern worth noting

Three separate faults have now had the same shape: a HAL built from source,
missing a device-specific data file that stock ships.

```
audio    aw882xx_pid_2329_monitor.bin   absent from stock too -- dead end
audio    LVACFS profile mapping         wrong DeviceId, patched by us
display  QDCM calibration               wrong panel shipped
```

A systematic diff of `/vendor/etc` against the stock dump is worth doing before
camera work starts, since camera is the largest remaining consumer of vendor
data files.
