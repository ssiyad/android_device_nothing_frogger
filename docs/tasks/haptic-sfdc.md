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

**sysfs is the data path, and it is not optional.** `/dev/ics_sfdc` carries no
data at all: `.read` returns 0, `.write` discards, and there is no
`.unlocked_ioctl` despite `sfdc_drv.h` declaring two ioctl codes. Its only real
operation is `.poll`, raising `POLLIN` when `bemf_daq_done` is set. It is a
completion notification.

The acquisition loop is four steps and one is compiled:

| Step | Where | Built |
|---|---|---|
| write `daq_en` to start | `daq_en_store` | no |
| IRQ fills `daq_data`, calls `sfdc_wakeup_bemf_daq_poll` | `haptic_drv.c` ~995 | yes |
| poll `/dev/ics_sfdc` | `sfdc_drv.c` | yes |
| read `daq_data` back | `daq_data_show` | no |

`daq_en_store` is the only writer of `haptic_data->daq_en`, so even the IRQ's
acquisition branch can never run.

## Why flipping the guards does not fix it

Two attempts, two failed builds, and the second is the informative one.

`vibrator_init()` has `#ifdef TIMED_OUTPUT` / `#elif 0`, neither of which
compiles. Enabling the second fails, because `ics_haptic_attribute_group` is
inside an `#if 0` spanning lines 610–960. Enabling *that* fails with twenty
errors, because the `DEVICE_ATTR` lines there reference show and store functions
inside a **second** `#if 0`, lines 105–586. About 830 lines between them, and no
guarantee a third does not appear behind those.

**And it would still not be enough.** The blob asks for nineteen attribute names
under `leds/vibrator_nt`; this driver defines fifteen. The four missing are
`stream1_data`, `stream1_start`, and — the ones that decide this — **`sfdc_f0`
and `autotrack_f0`**, both SFDC's own.

So the interface `libics_haptic.so` expects is not the interface this driver
implements, guards or no guards. That is a driver older than the blob, not a
driver with its features switched off.

## What this actually is

A port, not a fix: either a newer `ics_haptic` driver that implements
`sfdc_f0` and `autotrack_f0`, or accepting that f0 tracking does not run here
and the actuator stays on its factory 170.9 Hz.

Nothing in the tree is left enabled. Both attempts are reverted, and the
guards are as they were.

## If a newer driver is ever ported

| Check | Why it can fail |
|---|---|
| haptics still work at all | `devm_led_classdev_register` failing returns an error out of `vibrator_init`, which fails probe and takes the driver with it. Test before anything about SFDC |
| `/sys/class/leds/vibrator_nt/` carries all nineteen names the blob wants | fifteen is not enough; `sfdc_f0` and `autotrack_f0` are the ones to confirm |
| `sfdc is not initialized` is gone from logcat | the point of the exercise |
| `sfdc f0 update: %.1f` appears | `Vibrator.cpp`'s callback, which only fires once tracking runs |

Permissions come after that and not before. The attributes are `0644`
root-owned and the HAL runs as `system`, so it can read `f0` and `daq_data` but
cannot write `daq_en` or `f0_en`; that needs a ueventd rule, and the nodes need
labelling for the HAL's domain. Both are deliberately unwritten — rules for a
path that does not exist are how inert configuration arrives.

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
