# SurfaceFlinger runs on the little cluster

The compositor and the display composer HAL are both confined to CPUs 0–3, the
four A520s. Stock puts SurfaceFlinger on 4–7.

## What the device reports

`surfaceflinger` and every thread that matters — `RenderEngine`, `app`, `appSf`,
`TimerDispatch`, `HwcAsyncWorker`, `RegionSampling` — read
`Cpus_allowed_list: 0-3`. Only the binder threads and `BckgrndExec LP` get 0–7.
`vendor.qti.hardware.display.composer-service` is 0–3 as well.

The cause is the cpuset, not a missing affinity call:

```
/proc/<sf>/cgroup  ->  cpuset:/system-background   cpu:/foreground
/dev/cpuset/system-background/cpus  ->  0-3
```

`surfaceflinger.rc` asks for `task_profiles HighPerformance`, and that profile —
identically in our `task_profiles.json` and stock's — sets only the **cpu**
controller to `foreground`. It names no cpuset, so the cpuset is whatever the
process inherited, and that is `system-background`.

## What stock does instead

`/vendor/etc/nt_performance/platform_config.xml` carries

```xml
<CONFIG name="sf_affinity" value="240"/>   <!-- 0b11110000, CPUs 4-7 -->
```

alongside cpuset defaults that match what this device already runs (`bg 0-3`,
`sbg 0-3`). That file is staged into `/data/nt_performance` by
`system_ext/etc/init/nt_performance_config.rc` for a Nothing framework consumer
on `system_ext`, and this tree ships none of it. So the value exists in the
firmware and nothing here reads it: the compositor stays where the cpuset left
it.

Worth noting what the rest of that directory turned out to be, since it looks
more promising than it is. `nt_named_thread_affinity.xml` pins exactly two
packages, both PUBG builds, and only to `0-6` — the OEM does almost no per-app
core pinning on this device, so there is little there to port.

## The trap in fixing it

**A cpuset masks `sched_setaffinity`.** The affinity request is intersected with
the cpuset's CPUs, so pinning SurfaceFlinger to 4–7 while it sits in a 0–3
cpuset does not partially work — it fails, and it fails quietly enough to look
applied. Whatever does this has to move the process out of `system-background`
first, or widen that cpuset, which moves every other process in it too.

## Why it is worth the care

Client composition runs in `RenderEngine`, in this process. [display.md](../reference/display.md)
records that the shade and quick settings are the only full-screen blur the
device draws and that blur is what forces client composition — measured at a
median SystemUI frame time of 30 ms against 15 ms without. That work has been
landing on A520s.

Measure before and after with the shade open/close method already in
display.md, rather than assuming the move is free: 4–7 is three A720s and the
prime core, and moving a always-running process onto them has a power cost that
the frame-time win has to justify.
