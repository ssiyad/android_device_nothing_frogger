# The proximity part reports near and nothing else

From outside: the screen blanks during a call and does not come back when the
phone leaves the ear.

## What the capture shows

`tools/prox-watch.sh` across a call, and the sensor's own event buffer:

- The display server registers the wakeup handle, reads `mProximity=Positive`
  on the first sample, sets `mScreenOffBecauseOfProximity`, and never sees a
  change before the call tears the registration down.
- `dumpsys sensorservice`, last 30 events for handle `0x00000052`: all thirty
  read `0.00`. The buffer spans nine hours. There is no `5.0` anywhere in it.
- Every one of those events carries `ts=19.03…`, a boot-time stamp about
  nineteen seconds in, and the wall clock of each matches a registration in the
  same dump. Proximity is on-change, so registering delivers the current value —
  thirty events that are all registration deliveries means the part has
  published nothing of its own since SEE started.

So it is not latching during a call. It has read near since boot and has never
once read far.

**That last reading is the durable one**, and it is easy to misread: a
registration delivery looks exactly like a sensor event. Compare the `ts`
against the wall clock. A part that is doing anything has event timestamps that
advance; this one has thirty stamped at the same nineteen seconds.

## Why

The pair on persist is the one [proximity.md](../reference/proximity.md)
records:

```
ct_threshold 1739   near_threshold 2000   far_threshold 1900
```

Near sits 261 counts above the bare-panel crosstalk baseline and far 161. For
the part to be pinned, the standing return is now at or above 2000 and never
falls back to 1900 — the shift proximity.md warned the screen protector would
cause, except it has gone past a marginal release and closed the band outright.
Nine hours including overnight rules out ambient infrared: this is a DC shift,
not sunlight.

The part itself is alive. The ALS shares the optical window and is streaming
current lux with advancing timestamps, so this is neither a dead sensor nor a
dead SEE.

## What to do

The raw count is still unreadable, so the thresholds remain the only
comparator — but the search is one-sided now, since any candidate pair has to
clear the standing return. Both values move together and far stays below near;
inverting them is not a configuration the part has behaviour for.

Restore the factory pair first — `near_threshold 2999`, `far_threshold 2177`.
It is the only pair known to have been measured against this unit, and the
current state is the worst outcome available, so nothing is risked. The registry
is read at ADSP boot, so it takes a reboot.

| After the reboot | Means |
|---|---|
| at rest it reports far | the standing return is under 2177, and a pair between there and 2000 will work |
| still pinned near | the shift is 438 counts or more |

If the factory pair is still pinned, no threshold recovers it — the protector
has taken the band, and the protector is the thing to change.
