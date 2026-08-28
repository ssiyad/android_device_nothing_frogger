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

Recheck only on a unit whose `in_temp_pm7550_volt_detect_input` reads like a
temperature.

Two things about `volt_detect` that look like leads and are not. Its driver,
`drivers/misc/hwid.c`, is gated on `CONFIG_HWID`, which is set in
`asteroids_perf.config` and not in Frogger's — `/proc/config.gz` on the device
confirms it is not built. So the node in `frogger-common-pmic.dtsi` binds
nothing and the ADC channel it claims is unclaimed. And `/proc/hwid` does work,
reporting `version = PVT_India platform_board_id = 18`, but it comes from one of
the other providers (`hardware_id.c` or `gpio_boardid.c`), not from this path —
`hwid.c` logs nothing at all.

None of that changes the conclusion. The 761 °C reading is the pin's wiring, not
the driver's.

## Done: the audio pop item

`BELL-5845`. gpio17 is `i2s0_data1`, and the shared base leaves it with no pull
in its active state, so the line floats between the codec releasing it and the
amplifier muting. The OEM's Frogger pinctrl pulls it down; the generic file it
was branched from does not, and this tree carried the generic value.

Overridden in `noth/frogger-common-pinctrl.dtsi`, matching what that file
already does for the se13 i2c pins.

Both properties are handled on purpose. `pinconf-generic` walks `dt_params[]` in
order — `bias-disable` at 162, `bias-pull-down` at 166 — so the pull-down is
applied second and wins whether or not the `/delete-property/` survives into the
overlay. The delete is there so the node does not read as a contradiction.

Verification is by ear, after a build and a flash: the pop is at the start and
end of playback.

**`/delete-property/` does not survive into a `/plugin/` overlay.** The built
`dtbo.img` shows `fragment@21` carrying `bias-pull-down` and nothing else — dtc
drops the delete silently when emitting overlay output, so the base's
`bias-disable` stays in `volcano.dtb` and both reach the pin controller. The
change works on the `dt_params[]` ordering alone.

That is the same limitation the `/delete-node/` lines in
`frogger-common-pmic.dtsi` are documented under, and it means the
`/delete-property/ qcom,i2c_pull` entries beside this one, on the se13 i2c pins,
are almost certainly inert too. They predate this change and are not known to be
causing anything; they are recorded here so the idiom is not trusted a third
time.

## Why the shared files are suspect generally

Anything under `qcom/` that touches a board-level peripheral carries whichever
board's hardware was merged into it. Speaker amplifier, display panel and camera
sensor nodes have all been found declaring the wrong part there. Board choices
belong in `noth/<board>-common.dtsi`.
