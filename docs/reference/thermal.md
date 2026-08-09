# Thermal

## What actually mitigates

Kernel trip points and cooling devices, plus `thermal-engine-v2`. Nothing above
the kernel throttles anything in this build: SurfaceFlinger's
`mThermalBrightnessThrottlingDataMapByThrottlingId` and
`mThermalRefreshRateThrottling` are both empty, and every cooling device sits at
`cur_state=0` until the kernel moves it.

`/vendor/etc/thermal-engine.conf` is a stub — 84 bytes reading "File empty by
default", on stock as well as here. `thermal-engine-v2` does not take its
configuration from that file, so editing it changes nothing.
`/vendor/etc/advance_thermal_mitigation/` is not shipped and no binary in the
stock image references that path.

## The HAL is stock's blob, not the source build

`vendor/bin/hw/android.hardware.thermal-service.qti` ships as a blob with its
own `.rc` and vintf fragment, rather than being built from
`hardware/qcom-caf/thermal`.

The source HAL's thresholds are compiled in and chosen by soc_id — 636 selects
`volcano_specific`, whose skin ladder opens at `[LIGHT] = 36500`:

```c
{ TemperatureType::SKIN, { "sys-therm-0" }, "skin",
  { [LIGHT] = 36500, [MODERATE] = 40000, [SEVERE] = 60000,
    [CRITICAL] = 65000, [EMERGENCY] = 70000, [SHUTDOWN] = 95000 } }
```

36.5 °C is marginally above body temperature, so ordinary use pushes
`PowerManager.getCurrentThermalStatus()` to LIGHT or MODERATE and apps show
throttling warnings while nothing is in fact throttled. There is no per-device
override and no property hook, and `thermalConfig.cpp` is shared by every
Qualcomm device in Lineage, so the ladder cannot be corrected from here.

Nothing's blob ("Nothing skin thermal config" appears in it) carries sensor
names but no thresholds at all. It reads `persist.vendor.thermal.thresholds`,
which no partition sets, so it logs `Thresholds is not defined` and never
computes a severity. Its `.rc` mirrors `sys.thermal.thresholds` onto that
persist property and restarts the service, so thresholds can be supplied at
runtime if that ever becomes worth doing.

Constraints on that swap:

- `vendor/etc/vintf/manifest_volcano.xml` declares `target-level="8"`, so
  `compatibility_matrix.8.xml` applies and requires `android.hardware.thermal`
  **version 1**, non-optional. The blob's fragment declares V1, so this matches
  rather than downgrades. Dropping the HAL entirely fails `check_vintf`.
- No sepolicy change: same path, so the same `hal_thermal_default_exec` label,
  and the same `vendor.thermal-hal` service name.
- `device.mk` must keep `PRODUCT_SOURCE_ROOT_DIRS += -hardware/qcom-caf/thermal/`.
  Dropping the source HAL from `PRODUCT_PACKAGES` does not get it out of the
  way, in two stages: `hardware/qcom-caf/common/BoardConfigQcom.mk` imports that
  namespace for every platform that is not legacy UM, so Soong first rejects one
  module name reaching a partition from two namespaces
  (`build/soong/fsgen/fsgen_mutators.go`), and renaming the blob's module past
  that only exposes the next collision — a `cc` module copies its unstripped
  binary to `symbols/vendor/bin/hw/<stem>` whether or not anything installs it,
  so both modules claim that path and ninja refuses the duplicate rule. Pruning
  the directory is what lets the blob keep the stock module name and filename.
  The leading `-` marks a disallowed prefix, matched as a string, so the
  trailing slash matters: without it the entry would also prune
  `hardware/qcom-caf/thermal-legacy-um`.
- `android.hardware.thermal-V1-ndk.so` and `libnl.so` stay in `/vendor` on their
  own — the Lineage power HAL, `liblmthermallistner.so`, `libsdmextension.so`
  and the wifi stack link them.

## Zone lookup is by name, first match wins

`get_tzn()` walks `/sys/class/thermal` with `readdir` and takes the first zone
whose `type` *starts with* the configured name — a prefix compare, not equality.
So a config asking for `sys-therm-1` would happily bind `sys-therm-10`, and two
zones sharing a name resolve to whichever `readdir` reaches first.

## Two `battery` zones, both stock

```
thermal_zone49 battery 31294
thermal_zone61 battery 30000
```

`thermal_zone49` is the devicetree zone: sensor
`PMIV0104_ADC5_GEN3_AMUX_THM1_BATT_THERM_30K_PU`, `user_space` governor, two
125 °C passive trips.

`thermal_zone61` is registered by the power supply core for the `battery`
supply, and reports `/sys/class/power_supply/battery/temp` × 100. `usb` and
`wireless` come from the same place. None of the three has trip points, which is
how a driver-registered zone is told apart from a devicetree one.

Both exist on stock — the base DTB carries the zone and the charger driver
registers the supply — and neither can be removed from here: an overlay cannot
delete a node from the base DTB, and the second name is the power supply's own.

It also does not matter. The HAL's battery entry sets `no_trip_set`, so no trips
are written to whichever zone it binds, and the only consequence is which of two
battery readings about a degree apart reaches the framework.

## The skin sensor

`volcano-pmic-overlay.dtsi` maps `sys-therm-0` to
`PMK8550_ADC5_GEN3_AMUX_THM1_XO_THERM_100K_PU`, the XO thermistor, which runs
hotter than the case. Whether that is the right sensor to call skin is
unconfirmed and moot while the shipped HAL derives no severity from it.
