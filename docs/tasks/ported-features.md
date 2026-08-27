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
| Core pinning for latency-sensitive processes | `configs/power/powerhint.json`, ours | The one item on this list that needs nothing upstream. Frogger already ships `power-service.lineage-libperfmgr` and its own hint file, so this is a device change, and it can be measured rather than argued about. |
| The rest of AxionOS' performance and graphics changes | varies | Take them one at a time and find what each actually does. A ROM's collected tuning is a mix of real fixes, defaults already set here, and settings that are inert on this SoC — porting the set wholesale imports all three. |
| Pixel features | varies | Only the ones that work without the Pixel stack behind them. Anything that reaches for a Google service or a GMS-side model belongs with the depth effect above, not here. |

The device already has a place for the ones that turn out to be device-side:
`vendor.prop` and the power hint file are read on every boot and cost nothing to
revert. Anything that has to change Java or Kotlin upstream deserves the
question of whether it is worth a rebase every cycle.
