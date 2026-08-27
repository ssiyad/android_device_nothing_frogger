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

## Composition pipeline depth

The phase durations are stock's: 13.67 ms app plus 10.5 ms SF, applied as
durations because `debug.sf.use_phase_offsets_as_durations=1`. That describes a
pipeline 24.2 ms deep, which at 120 Hz is about 2.9 vsync periods, so
SurfaceFlinger has to keep roughly three frames in flight to pipeline at all.

`ro.surface_flinger.max_frame_buffer_acquired_buffers` sizes the framebuffer that
carries that depth — it appears in the SF dump as
`NUM_FRAMEBUFFER_SURFACE_BUFFERS`. AOSP defaults it to 2
(`max_frame_buffer_acquired_buffers(2)`); stock sets 3. Taking the default while
shipping stock's durations starves the pipeline, and the producer blocks in
`dequeueBuffer` → `waitForBufferRelease` rather than pipelining.

The framebuffer is only on the critical path when SurfaceFlinger falls back to
**client composition**. Background blur is what forces that here: it is the sole
full-screen blur the device draws, behind the notification shade and quick
settings. Both stock and this tree ship
`ro.surface_flinger.supports_background_blur=0`, so the buffer count and the blur
cost stay tuned together.

The measurement is why: over five shade open/close cycles, blur doubles the
median SystemUI frame time (15 ms to 30 ms) and takes jank from 14% to 41%,
which is the buffer count carrying a load stock never asks it to. Setting the
property to 1 restores the capability and with it Developer Options' "Disable
window blurs" (`Settings.Global disable_window_blurs`), which reaches the same
composition path per user and without a rebuild.

Two related stock properties are inert in this tree and are deliberately not
carried: `debug.sf.enable_advanced_sf_phase_offset` appears nowhere in
SurfaceFlinger, and `debug.sf.latch_unsignaled` has been superseded by
`debug.sf.auto_latch_unsignaled`.

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
