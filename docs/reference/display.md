# Display

## QDCM colour calibration

The colour manager loads calibration by panel name. Shipping calibration for a
panel the device does not have leaves it uninitialised:

```
Current Color Mode: gamut 255 gamma 255 intent 65535     (0xFF / 0xFFFF)
ColorMode::NATIVE only,  wideColorGamut=false
```

Both Night Light and accessibility colour inversion die with it, because the HWC
unconditionally advertises `SKIP_CLIENT_COLOR_TRANSFORM` for internal displays
(`composer/AidlComposerClient.cpp`):

> Since DSPP would apply color transform on the final composed output, this is
> needed to prevent applying color transform twice.

SurfaceFlinger honours that (`CompositionEngine/src/Display.cpp`) and skips
applying the matrix in RenderEngine, so DSPP is expected to do it and cannot.
Forcing GPU composition changes nothing, which is what distinguishes a lost
matrix above the HWC from a fault inside it.

`qdcm_calib_data_nt37706a_*` is required for both the BOE and Visionox variants.

## `persist.sys.sf.native_mode`

SurfaceFlinger casts this straight to a `RenderIntent` with no validation
(`SurfaceFlinger.cpp`). Valid values are 0-2; anything else falls through to:

```c
default: // vendor display color setting
    intent = static_cast<ui::RenderIntent>(refreshArgs.outputColorSetting);
```

Vendor render intents start at 256. A value the display HAL does not advertise
produces `map unknown (BT709 sRGB Full range)/(Unknown RenderIntent) to default
color mode` on every frame. `0` is `kManaged`.

It is a persist property, so a device holding an old value in `/data` needs one
`setprop` to pick up a change.

## DFPS and the pixel clock

Refresh-rate switching is seamless only when the modes share a pixel clock.
120/90/60 hold 442,640,160 by stretching the vertical front porch. A 30 Hz entry
instead inflates the horizontal porch and halves the clock, which forces a DSI
PLL reprogram and shows as flicker on every transition.

`qcom,dsi-supported-dfps-list` carries `<120 90 60>` for both nt37706a variants.
Adding a rate requires checking it against the porch table first.

## `ro.surface_flinger.set_idle_timer_ms`

A shorter idle timer drops to the idle refresh rate sooner and therefore switches
refresh rate more often. Stock pairs its value with
`ro.vendor.display.set_second_timer_ms`, a second-stage timer read only by
`nt-services.jar`, which this tree does not ship.

## Panel feature attributes

`/sys/panel_feature` is created by `sde_connector.c` only when
`nt_is_panel_detected()` is true, which merely reports whether the global
`nt_panel` was ever assigned. `dsi_panel_get()` assigns it on an exact `strcmp`
against a panel name, so a name mismatch removes the **entire attribute group**,
not one node.

The group is `brightnessid`, `fp_status`, `panel_id1`, `panel_id2`, `panel_id3`.
Optical UDFPS depends on `fp_status` to light the finger.
