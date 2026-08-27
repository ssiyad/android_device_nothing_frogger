# The screen stays dark mid-call

The display blanks during a call and does not come back when the phone leaves
the ear.

**Establish which half is at fault before changing anything.** Two unrelated
faults produce the same symptom, and the sample that separates them has to be
taken mid-call, with the screen dark and the phone away from the ear — which is
not a state anything can be read by hand from. `tools/prox-watch.sh` does the
reads device-side once a second and logs them, so the call can be placed
normally and the transition read afterwards.

| In the log, screen dark and the phone away from the ear | Means |
|---|---|
| `mProximity` near, `mScreenOffBecauseOfProximity=true` | the part is latched near, and the thresholds are the problem |
| `mProximity` far, `mScreenOffBecauseOfProximity=true` | the release arrived and nothing acted on it |
| `wl=0` | the dialer is not holding the proximity wake lock, so this is audio routing rather than either of the above |

The rest of the reading path is in [proximity.md](../reference/proximity.md).

## If the part is latched

The thresholds in place are `near_threshold 2000` and `far_threshold 1900`
against a crosstalk baseline of 1739 — a release band 161 counts wide, sitting
just above the baseline. They were picked to meet a return the screen protector
has already attenuated, and the price of meeting it is that the far side is now
within reach of anything that lifts the standing return: the protector settling,
warmth, a smear on the window. Once the standing return rests between 1900 and
2000, the part latches near at the first ear and never crosses back.

The raw count cannot be read from the ROM, so the thresholds are the only
comparator. Lower `far_threshold` toward the baseline — 1800 still leaves 61
counts of margin — reboot, and see whether the release arrives. Every step down
buys the release at the cost of tripping on ambient infrared, and no setting
recovers the ratio the protector took away. If no value releases without sitting
under the standing return, then removing the protector and writing the factory
pair back (2999, 2177) is the only configuration that works.

## If the release is being ignored

Then this is not a sensor fault and the thresholds are a distraction. `dumpsys
power` shows whether the dialer still holds `ACQ ProximitySensor (prox)` and
what `mProximityPositive` reads. The dialer takes that wake lock only while
audio routes to the earpiece, so a lock still held after the earpiece has been
left points at the dialer or at audio routing rather than at the display server.
