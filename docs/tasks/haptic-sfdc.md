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

Two things are missing, not one, and the second is the expensive one.

**The device is never registered.** `vibrator_init()` in
`drivers/misc/haptic/haptic_drv.c` has two branches, `#ifdef TIMED_OUTPUT` and
`#elif 0`. `TIMED_OUTPUT` is defined nowhere in the tree and the second is dead
by construction, so nothing is registered and `sysfs_create_group` never runs.

**The attribute layer is not compiled either.** Lines 610 to 960 — every
`DEVICE_ATTR`, the `ics_haptic_attributes[]` array and
`ics_haptic_attribute_group` itself — sit inside an `#if 0`. The attributes are
written in the source and built into nothing.

That second point was established the expensive way: enabling only the
registration branch fails to compile, because there is no attribute group left
for `sysfs_create_group` to reference.

```
haptic_drv.c:1628:61: error: use of undeclared identifier 'ics_haptic_attribute_group'
```

So this is not a one-word fix. It means enabling a 350-line dead block, finding
what else it depends on, and only then registering the device.

## Before doing that, establish whether sysfs is even the intended path

`sfdc_drv.c` registers a misc device and `/dev/ics_sfdc` exists on the device
with a real major:minor and an SELinux label. `libics_haptic.so` carries both
that path and the sysfs ones. If SFDC is meant to be driven through the char
device's ioctls, the sysfs layer may be `#if 0` deliberately and the failure is
somewhere else entirely — in which case reviving 350 lines of it would be effort
spent on the wrong half of the driver.

Read `sfdc_drv.c` and the `sfdc_*` symbols in the blob before touching the
`#if 0`. The blob is the only thing that knows which interface it prefers.
## What to check, once there is something to check

The change that would have gone first was reverted for not compiling, so there
is nothing on a device to verify yet. When there is:

| Check | Why it can fail |
|---|---|
| haptics still work at all | `devm_led_classdev_register` failing returns an error out of `vibrator_init`, which fails probe and takes the driver with it. Test this before anything about SFDC |
| `/sys/class/leds/vibrator_nt/` exists and carries `f0`, `daq_en`, `daq_data` | a registered device with no attributes means the `#if 0` block is still not building what it needs to |
| `sfdc is not initialized` is gone from logcat | the point of the exercise |
| `sfdc f0 update: %.1f` appears | `Vibrator.cpp`'s callback, which only fires once tracking runs |

Then permissions, and only then. The attributes are `0644` root-owned and the
vibrator HAL runs as `system`, so it can read `f0` and `daq_data` but cannot
write `daq_en` or `f0_en`. That needs a ueventd rule, and the new sysfs nodes
will need labelling for the HAL's domain. Both are deliberately unwritten: rules
for a path that does not exist are how inert configuration arrives.

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
