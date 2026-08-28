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

## Where it stands

The factory pair is written back and survives the reboot, and the part now
reports `5.00` at rest — three events since boot, all far. So the standing
return is at or below 2177, and with the earlier pin it is at or above 2000.
[proximity.md](../reference/proximity.md) carries the bracket.

**Far at rest is only half the test.** `near_threshold` is 2999, which the
factory measured against a bare panel, and an ear returns under 438 counts above
baseline. Even taking the standing return at the top of its bracket, an ear
lands near 2615 and does not reach 2999 — so the likely outcome is the opposite
failure: the screen never blanks at all, and a cheek works the touchscreen.

That is the next reading, and it needs a real call held to an ear. If the screen
blanks and comes back, this is finished. If it never blanks, `near_threshold`
has to come down to somewhere between the standing return and the ear, with
`far_threshold` above the standing return and below it — a pair around
`near 2400 / far 2250` is the first candidate, and it is a guess at the ear
return rather than a measurement.

The raw count is still unreadable, so each candidate costs a reboot: the
registry is read at ADSP boot and nothing short of one reloads it.
