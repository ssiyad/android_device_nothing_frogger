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
| Depth effect on the lock screen wallpaper | wallpaper picker and SystemUI | The effect is Google's rather than AOSP's: the subject segmentation that lifts the foreground ships with the Pixel wallpaper picker, so establish whether anything open reproduces it before costing the rest. |
| Core pinning for latency-sensitive processes | device tree, ours | Split out as [SurfaceFlinger runs on the little cluster](surfaceflinger-affinity.md), which is the concrete case and a measured divergence from stock rather than a wish. Nothing's own per-app pinning turned out to be two PUBG packages, so there is no general scheme here to copy. |
| The rest of AxionOS' performance and graphics changes | varies | Take them one at a time and find what each actually does. A ROM's collected tuning is a mix of real fixes, defaults already set here, and settings that are inert on this SoC — porting the set wholesale imports all three. |
| Pixel features | varies | Only the ones that work without the Pixel stack behind them. Anything that reaches for a Google service or a GMS-side model belongs with the depth effect above, not here. |

The device already has a place for the ones that turn out to be device-side:
`vendor.prop` and the power hint file are read on every boot and cost nothing to
revert. Anything that has to change Java or Kotlin upstream deserves the
question of whether it is worth a rebase every cycle.
