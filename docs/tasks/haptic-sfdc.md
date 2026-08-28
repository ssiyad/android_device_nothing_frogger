# SFDC f0 tracking is not running

The actuator is driven at its factory resonance whatever the temperature. SFDC
exists to correct exactly that, and it has never initialised on this build.

The library says so itself, repeatedly, on every query:

```
E/libics_haptic: ics_sfdc: sfdc is not initialized, please initialize sfdc first
E/libics_haptic: ics_sfdc: sfdc is not initialized! return manufacture f0 = 170.9
```

Haptics work — the drive path logs `play go enter` and `set stream data
successfully`. It is only the frequency tracking that is dead, which is why
nothing about it is obvious from using the phone.

## Why

`libics_haptic.so` reaches for
`/sys/.../i2c-8/8-005f/leds/vibrator_nt/{f0,f0_en,daq_en,daq_data}`. That
directory does not exist, and neither does any other `vibrator*` node in sysfs.

The kernel defines every one of those attributes. It just never attaches them.
`vibrator_init()` in `drivers/misc/haptic/haptic_drv.c` had two branches,
`#ifdef TIMED_OUTPUT` and `#elif 0`; `TIMED_OUTPUT` is defined nowhere in the
tree and the second was dead by construction, so no device was registered and
`sysfs_create_group` never ran.

Everything else was already correct. The devicetree sets `device-name =
"vibrator_nt"` on the `ics_haptic@5f` node, `vib_name` is read from it, and
`vib_dev_t` is already `struct led_classdev` when `TIMED_OUTPUT` is undefined —
so the disabled code was written against the type it would get.

Both are `#else` now, in the kernel repo. **That is unproven until a build and a
flash.**

## What to check after the next kernel build

| Check | Why it can fail |
|---|---|
| haptics still work at all | `devm_led_classdev_register` failing returns an error out of `vibrator_init`, which fails probe and takes the whole driver with it. Test this first |
| `/sys/class/leds/vibrator_nt/` exists and carries `f0`, `daq_en`, `daq_data` | the group is attached to `vib_dev.dev->kobj`, so a registered device with no attributes means `sysfs_create_group` failed |
| `sfdc is not initialized` is gone from logcat | the point of the exercise |
| `sfdc f0 update: %.1f` appears | `Vibrator.cpp`'s callback, which only fires once tracking runs |

Then permissions, and only then. The attributes are `0644` root-owned, and the
vibrator HAL runs as `system`, so it can read `f0` and `daq_data` but cannot
write `daq_en` or `f0_en`. That needs a ueventd rule, and the new sysfs nodes
will need labelling for the HAL's domain.

**Both are deliberately not written yet.** Adding rules for a path that does not
exist is how inert configuration gets into a tree, and this device tree has just
had a round of it removed. Let the nodes appear, read the denials, then write
exactly what is needed.

## The property that started this

Stock sets `persist.vendor.sfdc=true` and `persist.vendor.sfdc.ntc=true` and
this tree sets neither; `sfdc.ntc` is read by `libics_haptic.so` and is the only
one of 260 stock vendor properties whose consumer this tree both ships and runs.

It is still not worth setting speculatively. With SFDC failing to initialise, a
property that tunes its behaviour cannot be evaluated. Revisit once tracking
runs.

Note that `persist.vendor.sfdc` is **not** the enable switch — the name in the
blob is `persist.sys.sfdc`, a different prefix. Stock setting one name while the
library reads another is unexplained.
