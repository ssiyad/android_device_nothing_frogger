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
- `android.hardware.thermal-V1-ndk.so` and `libnl.so` stay in `/vendor` on their
  own — the Lineage power HAL, `liblmthermallistner.so`, `libsdmextension.so`
  and the wifi stack link them.

## The skin sensor

`volcano-pmic-overlay.dtsi` maps `sys-therm-0` to
`PMK8550_ADC5_GEN3_AMUX_THM1_XO_THERM_100K_PU`, the XO thermistor, which runs
hotter than the case. Whether that is the right sensor to call skin is
unconfirmed and moot while the shipped HAL derives no severity from it.
