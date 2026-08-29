# Features worth taking from elsewhere

A backlog of things other ROMs ship. None of them is a device fault, and that is
what makes the list dangerous: almost every one lands in a repo this tree does
not own, and is then carried across every rebase for as long as it is wanted.
[repositories.md](../reference/repositories.md) sets the order to try — a
resource overlay under `overlay-lineage/` changes nothing upstream and is
cheapest; a `patches/` entry is the named exception and is meant to stay rare; a
sixth fork is the answer only when neither reaches.

Judge each one on what it costs to keep, not on what it costs to land.

| Want | Where it lands | What to settle first |
|---|---|---|
| Depth effect on the lock screen wallpaper | wallpaper picker **and** SystemUI | Costed below. Two halves, neither present, both upstream. |
| Core pinning for latency-sensitive processes | device tree, ours | Split out as [SurfaceFlinger runs on the little cluster](surfaceflinger-affinity.md), which is the concrete case and a measured divergence from stock rather than a wish. Nothing's own per-app pinning turned out to be two PUBG packages, so there is no general scheme here to copy. |
| The rest of AxionOS' performance and graphics changes | varies | Take them one at a time and find what each actually does. A ROM's collected tuning is a mix of real fixes, defaults already set here, and settings that are inert on this SoC — porting the set wholesale imports all three. |
| Pixel features | varies | Only the ones that work without the Pixel stack behind them. Anything that reaches for a Google service or a GMS-side model belongs with the depth effect above, not here. |

## The lock screen depth effect, costed

Asked for, and checked rather than guessed. It needs two independent pieces and
this tree has neither.

**The effect.** `WallpaperPicker2` ships the plumbing — `EffectsController`,
`EffectContract`, `WallpaperEffectsView2`, `ImageEffectsRepository` — and one
concrete subclass, `DefaultEffectsController`, which is an empty stub. Nothing
else in the tree implements it. Google's implementation is not carried in
`WallpaperPicker2` and is not something a port picks up: its own result codes
give it away — `RESULT_FOREGROUND_DOWNLOAD_SUCCEEDED`, `_FAILED` and
`RESULT_PROBE_FOREGROUND_DOWNLOADING` — so the segmentation asset is fetched
from Google at use time. `com.google.android.apps.wallpaper` is not installed
here and MindTheGapps does not ship it.

**The rendering.** Even given a segmented subject, nothing draws it in front of
the clock. SystemUI has no lock screen wallpaper foreground layer — no clock
layer, no foreground-wallpaper concept — and `WallpaperManager` exposes
`FLAG_LOCK` and `WallpaperDescription` but nothing for a subject plane. The
look people mean by "depth effect" is the subject overlapping the clock, and
that compositing does not exist to be configured.

So it is an `EffectsController` implementation *plus* a SystemUI change, carried
across rebases in two upstream repos. That is the shape this file exists to warn
about.

The cheap approximation needs no tree change at all: an app that bakes the
subject over a chosen clock position into a still image. It only looks right for
the clock style and position it was generated against, and it does not track the
real clock — but it costs nothing to try and it is reversible.

A middle path exists if the effect is ever wanted properly: ML Kit's subject
segmentation runs through Play Services, which this device has, so the model
download Google's picker does could be avoided. It removes one of the two
obstacles and leaves the SystemUI half untouched.

The device already has a place for the ones that turn out to be device-side:
`vendor.prop` and the power hint file are read on every boot and cost nothing to
revert. Anything that has to change Java or Kotlin upstream deserves the
question of whether it is worth a rebase every cycle.
