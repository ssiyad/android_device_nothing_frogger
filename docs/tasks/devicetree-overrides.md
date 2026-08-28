# Express deferred devicetree items as overrides

Both items lived in the fork's shared `qcom/` base, where editing them in place
would affect every board that includes it. Each needed expressing as a
board-level override in `noth/`.

## Done: `qupv3_se1_i2c` as a GCC critical device

The base lists only `qupv3_se7_i2c` and states the rule beside it — every QUP
that parents a PMIC must be a critical device to GCC, or the clock can be gated
while that device still needs it. Frogger hangs the `wr1241` camera regulator IC
off `qupv3_se1_i2c`, so se1 qualifies, and the OEM's own Frogger overlay lists
both. Overridden in `noth/frogger-common-pmic.dtsi`.

Latent rather than a fix: camera works today. The change only keeps alive a
clock that is currently eligible for gating.

## Do not port: the thermal NTC restructure

The OEM's Frogger overlay carries a change-set marked `SQ762-384, config thermal
ntc 2025/7/28` which comments out the `volt_detect` node, renames `sys-therm-13`
to `sub_board_ntc`, and points that zone at
`PMXR2230_ADC5_GEN3_AMUX2_GPIO4_100K_PU` — the channel `volt_detect` had been
using. It repurposes PM7550 GPIO4 from a hardware-ID voltage divider to a
thermistor.

**This unit does not have the thermistor.** The channel is exposed by the vadc
driver as `in_temp_pm7550_volt_detect_input`, and on this device it reads:

| Channel | Reading |
|---|---|
| `pm7550_volt_detect` | **761159** (761 °C) |
| `pm7550_sys_therm_12` | 36389 |
| `pm8550b_die_temp` | 36234 |
| `board_ntc` zone | 36325 |

761 °C is a resistor divider being run through a thermistor conversion, not a
temperature. GPIO4 here carries the hwid network the OEM's change removes.

So porting it would create a thermal zone reporting 761 °C on a device with
active thermal mitigation. That is worse than leaving it alone, and it explains
why the item was deferred rather than merely forgotten. The OEM change belongs
to a later board revision where the pin was rewired.

Recheck only if `/proc/hwid` stops reporting a board stage, or on a unit whose
`in_temp_pm7550_volt_detect_input` reads like a temperature.

## The audio pop item

`BELL-5845`, one pinctrl `bias-disable` to `bias-pull-down`, is untouched and
still needs locating and expressing the same way.

## Why the shared files are suspect generally

Anything under `qcom/` that touches a board-level peripheral carries whichever
board's hardware was merged into it. Speaker amplifier, display panel and camera
sensor nodes have all been found declaring the wrong part there. Board choices
belong in `noth/<board>-common.dtsi`.
