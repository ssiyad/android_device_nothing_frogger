# SurfaceFlinger runs on the little cluster

The compositor and its RenderEngine thread sit in the `system-background`
cpuset, which is CPUs 0–3 here, so neither reaches a big core. The mechanism,
the measurements and the reasoning are in
[display.md](../reference/display.md).

The fix is in the tree and **not on the device**: a `display` cpuset of 4–7 in
`init.frogger.rc`, and a `/vendor/etc/task_profiles.json` redefining
`SFMainPolicy` and `SFRenderEnginePolicy` onto it. Until a build and a flash,
the running image still composites on A520s.

## What still has to be checked

The runtime rehearsal covered the part that could be rehearsed: a hand-made
`display` cpuset of 4–7 binds those two threads to 4–7 with no `taskset`, and
that thread set measured 4.4% and 4.0% janky against 17.4% and 26.6% as
shipped. What a running device cannot answer is whether the plumbing lands.

| Check | Why it can fail |
|---|---|
| `/dev/cpuset/display` exists, `cpus` is 4-7, `mems` is 0 | `on early-boot` has to run after init.rc has mounted and populated `/dev/cpuset`; a cpuset with no `mems` accepts no tasks |
| `grep cpuset /proc/$(pidof surfaceflinger)/cgroup` says `/display` | the override only applies if the file parsed; libprocessgroup logs a load failure and silently keeps the AOSP profile |
| `RenderEngine` in that process reads `Cpus_allowed_list: 4-7` | RenderEngine applies its profile on its own thread, separately from the main one |
| the cpuset survives a `surfaceflinger` restart | this is the reason it is a profile and not a `taskset`; worth proving once rather than assuming |

If the cpuset is empty while the directory exists, the profile did not apply and
the task_profiles file is the thing to look at. If the directory is missing, the
init ordering is wrong.

Re-measure with the shade benchmark rather than trusting the rehearsal —
six quick-settings open/close cycles against `dumpsys gfxinfo
com.android.systemui`, with the screen kept awake, since a screen that dozes
mid-run reports zero frames rendered and a 4950 ms percentile rather than an
error.
