# Add the Goodix touch panel driver

Frogger ships `goodix_ts.ko` alongside `focaltech_fts.ko`, which indicates dual
sourcing of the touch panel.

`goodix_ts.ko` is not in the module load lists, because referencing a module the
kernel does not build breaks module loading.

## Change

Build `goodix_ts` and add it to `modules.load.vendor_dlkm`, gated so that a
kernel without it still loads.

## Note

`device.mk` points the sensors HAL at `fts_gesture_single_tap_{pressed,enabled}`
and `fts_fod_{pressed,enabled}` under the Focaltech SPI path. Those nodes are
LineageOS additions in `sm7635-modules/noth/touchscreen/focaltech_core.c` and
exist in no OEM tree, so a Goodix panel needs its own equivalents before the
gesture and FOD paths work.
