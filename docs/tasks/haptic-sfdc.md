# Is SFDC f0 tracking actually running?

The vibrator HAL is ours and it uses SFDC — `hardware/nothing/vibrator/Vibrator.cpp`
calls `sfdc_initialize`, `sfdc_calibrate`, `sfdc_get_continuous_f0` and
`sfdc_get_transient_fc` out of `libics_haptic.so`, which this tree ships. SFDC
tracks the actuator's resonant frequency, which drifts with temperature, so
without it the drive waveform is tuned for a frequency the LRA no longer has.

Haptics work — the log shows `play go enter` and `set stream data successfully`,
so the drive path is fine. What is unclear is whether the tracking is.

## What does not add up

| Reading | Expected if SFDC were running |
|---|---|
| No `sfdc` line anywhere in logcat | `Vibrator.cpp` logs `sfdc f0 update: %.1f` on the callback, and `sfdc init failed, running without f0 tracking` on failure. Neither appears |
| `persist.vendor.sfdc.result` is empty | `libics_haptic.so` carries that name and writes it |
| `persist.sys.sfdc` and `vendor.ics.sfdc.drift` are empty | both are property names in the blob |
| No sfdc property is labelled in our sepolicy | stock labels `persist.vendor.sfdc` and `persist.vendor.sfdc.result` `vendor_sfdc_prop` |

There are no `avc: denied` lines for sfdc, the vibrator HAL or `hal_vibrator`
either, so this is not a denial being hit and logged. It may be a denial being
suppressed by `dontaudit` — see
[selinux-collection.md](../reference/selinux-collection.md) for stripping those
before believing a quiet log.

## The property question this came from

Stock sets `persist.vendor.sfdc=true` and `persist.vendor.sfdc.ntc=true`, and
this tree sets neither. `persist.vendor.sfdc.ntc` is read by `libics_haptic.so`;
NTC is thermistor-based temperature compensation, which is exactly what f0
tracking would want.

`persist.vendor.sfdc` is *not* read by the blob — the enable name it carries is
`persist.sys.sfdc`, a different prefix. Stock setting one name while the library
reads another is unexplained and is worth resolving before either is adopted.

**Do not set them speculatively.** Of 260 stock vendor properties this build does
not set, `persist.vendor.sfdc.ntc` is the only one whose consumer is both shipped
and running, which makes it the one worth understanding rather than the one worth
copying.
