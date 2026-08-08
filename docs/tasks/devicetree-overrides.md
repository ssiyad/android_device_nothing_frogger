# Express two deferred devicetree items as overrides

Both live in the fork's shared `qcom/` base rather than `noth/`, so editing them
in place affects every board that includes them. Each needs expressing as a
board-level override in `noth/`.

| Item | Change |
|---|---|
| Thermal NTC restructure | `sys-therm-13` → `sub_board_ntc`, and add `qupv3_se1_i2c` to thermal `qcom,critical-devices` |
| Audio pop (BELL-5845) | one pinctrl `bias-disable` → `bias-pull-down` |

## Why the shared files are suspect generally

Anything under `qcom/` that touches a board-level peripheral carries whichever
board's hardware was merged into it. Speaker amplifier, display panel and camera
sensor nodes have all been found declaring the wrong part there. Board choices
belong in `noth/<board>-common.dtsi`.
