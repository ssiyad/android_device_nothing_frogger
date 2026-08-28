# Proximity

## The part and where it runs

`hx32062secr`, a TYHX combined ambient-light and proximity part, sharing one
optical window with the ALS. It is driven entirely from the ADSP by SEE
(`sns_hx32062secr`); there is no kernel driver and no sysfs. The two
`/sys/devices/virtual/optical_sensors/proximity` permission lines in
`init/ueventd.qcom.rc` name a node that does not exist on this device.

Two Android sensors are published from it, both on-change:

| Handle | Sensor | Used by |
|---|---|---|
| `0x51` | proximity, non-wakeup | nothing in this build |
| `0x52` | proximity, wakeup | the framework and every app |

`SensorUtils.findSensor()` falls through to `getDefaultSensor(TYPE_PROXIMITY,
true)`, so the display server takes the wakeup handle. Range is 5.0 cm,
reported as a two-state value: `0.0` near, `5.0` far. `mProximityThreshold` is
`min(maxRange, 5.0)` and the near test is `distance < threshold`, so a part
that reported its near value *as* its maximum range would never trip. This one
reports `0.0`, so it does.

## Calibration is per-unit and outlives a flash

The thresholds are raw ADC counts, written by the factory into the SEE registry
on the persist partition:

```
/mnt/vendor/persist/sensors/registry/registry/
    qrd_hx32062secr_0.json.hx32062secr_platform.prox.fac_cal
```

| Field | Meaning |
|---|---|
| `ct_threshold` | crosstalk baseline measured with nothing in front of the window |
| `near_threshold` | counts at or above which the part reports near |
| `far_threshold` | counts at or below which it returns to far |

The gap between the two thresholds is the hysteresis band, and both are
absolute counts rather than offsets from `ct_threshold` — moving the baseline
without moving the thresholds changes the sensitivity.

The factory measured this unit on a bare panel and wrote `ct_threshold=1739`,
`near_threshold=2999`, `far_threshold=2177`, demanding a rise of 1260 counts
before declaring near. That pair is what is in place. The screen protector
fitted since prints a border across the sensor strip; the ambient light sensor
still reads normally through it, but the proximity emitter is clipped enough
that an ear returns under 438 counts above the bare-panel baseline.

Both values move together and far stays below near: inverted, there is no
behaviour to expect.

**The standing return is no longer 1739.** A `near 2000 / far 1900` pair, chosen
to meet the attenuated ear, left the part reporting near continuously — it could
neither release nor ever have been far. That brackets the crosstalk the factory
measured at 1739: at or above 2000, since the part read near, and at or below
2177, since the factory `far_threshold` releases it at rest. Somewhere between
those two, so the protector has cost 261 to 438 counts of baseline.

That is what makes a pair close to the recorded baseline unusable, and it is the
second-order failure rather than the obvious one. A threshold a few hundred
counts above 1739 looks safe against the number in this file and is not, because
the number in this file is the value on a bare panel. **Ambient infrared then
compounds it**: direct sun on the panel adds to a return that already starts
higher, so it crosses sooner and blanks the screen, or convinces the lock screen
the phone is pocketed. None of this can be tuned away — the protector lowers the
ratio between the return and the baseline, and no threshold, gain or LED drive
setting recovers a ratio.

Because this lives on persist it survives every flash and factory reset, and
nothing in the tree records it. A device that has never had these values written
still carries the factory pair.

**Nothing in the ROM can rewrite these.** `libsensorcal.so` is a stock vendor
file this tree does not ship, and no binary in the stock vendor partition links
it, so its consumer is one of Nothing's own apps. The values are therefore
whatever the factory measured on a bare panel, and they survive every ROM
flash, factory reset and firmware change. Anything added to the optical stack
afterwards — a screen protector above all — shifts the return without shifting
the thresholds, and cannot be corrected from here.

`/vendor/etc/sensors/` is byte-identical to the stock `2603091830` vendor
partition across all 76 files, and the ADSP image is stock, so the SEE side is
not a porting surface. A proximity fault on this device is calibration or
optics, not configuration.

## Reading the in-call path

The chain is dialer → `PowerManager` → display server → sensor:

| Where | What to read |
|---|---|
| `dumpsys power` | `Wake Lock Log` for `ACQ ProximitySensor (prox)` from `com.android.dialer` |
| `dumpsys power` | `mProximityPositive` |
| `dumpsys display` | `DisplayPowerProximityStateController`: `mProximitySensor`, `mProximityThreshold`, `mProximity`, `mScreenOffBecauseOfProximity` |
| `dumpsys display` | `mPowerRequest=...useProximitySensor=` |
| `dumpsys sensorservice` | the last 30 events for the wakeup handle, and the registration list |

The dialer only takes the wake lock when it routes audio to the earpiece, so
its absence points at audio routing rather than at the sensor.

`mProximityPositive` feeds `PowerManagerService.isBeingKeptAwakeLocked()`, which
is what stops the inactivity timeout while the phone is at an ear. **A
proximity failure therefore shows up as the device sleeping on the ordinary
screen timeout mid-call**, not as a display that refuses to blank — and with
`wake_gesture_enabled=1` the pickup gesture then relights it against the cheek,
which is what the fault looks like from outside.

Registrations churn with display state by design: the display server drops the
sensor whenever its policy reaches `OFF`, so gaps in the registration list that
line up with sleep are not evidence of anything.

The event list needs the same care in the other direction, and misleads more
readily. Proximity is on-change, so registering delivers the current value as an
event — and while the part is not changing state, *every* entry in the last-30
list is a registration delivery rather than something the part did. The tell is
the timestamp: a delivery carries the `ts` of the original sample, so a part
that has not moved since boot shows thirty events all stamped a few seconds in
while their wall clocks span hours. Match each event's wall clock against the
registration list before reading any of it as activity.

Reading the raw count is not possible from the ROM. SEE publishes only the
two-state value, `values[1]` and `values[2]` are always zero, and the diag path
would need `Diag_sensor.cfg`, another stock file this tree does not ship. The
thresholds themselves are the only available comparator: set a candidate pair,
reboot, and observe which side the part lands on. The registry is read at ADSP
boot, so nothing short of a reboot reloads it.
