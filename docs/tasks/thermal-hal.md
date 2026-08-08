# Replace the source-built thermal HAL

`device.mk` builds `android.hardware.thermal-service.qti` from
`hardware/qcom-caf/thermal`. Its `volcano_specific` config hardcodes a skin
threshold ladder selected by soc_id 636:

```c
{ TemperatureType::SKIN, { "sys-therm-0" }, "skin",
  { [LIGHT] = 36500, [MODERATE] = 40000, [SEVERE] = 60000,
    [CRITICAL] = 65000, [EMERGENCY] = 70000, [SHUTDOWN] = 95000 } }
```

36.5 °C is marginally above body temperature, so the framework reports LIGHT or
MODERATE under ordinary use and apps reading
`PowerManager.getCurrentThermalStatus()` show throttling warnings.

Stock ships its own patched HAL binary containing **no** temperature constants at
all. It reads `persist.vendor.thermal.thresholds`, which no partition sets, so
its threshold list stays empty and no severity is computed.

## Change

Add to `proprietary-files.txt`:

```
vendor/bin/hw/android.hardware.thermal-service.qti
vendor/etc/init/android.hardware.thermal-service.qti.rc
vendor/etc/vintf/manifest/android.hardware.thermal-service.qti.xml
```

Drop `android.hardware.thermal-service.qti` from `PRODUCT_PACKAGES` in
`device.mk`.

No sepolicy change: the service name (`vendor.thermal-hal`) and the exec label
(`hal_thermal_default_exec`) already match, and every `DT_NEEDED` library is
present.

## Version constraint

`vendor/etc/vintf/manifest_volcano.xml` declares `target-level="8"`, so
`compatibility_matrix.8.xml` applies and requires `android.hardware.thermal`
**version 1**, non-optional. Stock's blob and its vintf fragment declare V1, so
this matches rather than downgrades.

Removing the HAL entirely is not an option — `check_vintf` fails on a mandatory
missing HAL.

## Constraints

- Devicetree trip points do not control the reported severity. The HAL holds its
  thresholds internally and *writes* `trip_point_1_temp` itself to arm
  notifications, so raising a trip point in DT changes nothing.
- Framework thermal status drives nothing in this build:
  `mThermalBrightnessThrottlingDataMapByThrottlingId` and
  `mThermalRefreshRateThrottling` are both empty and cooling devices sit at
  `cur_state=0`. Actual mitigation is kernel trip points plus
  `thermal-engine-v2`, neither of which this change touches.
- `/vendor/etc/advance_thermal_mitigation/` is not shipped and no binary in the
  stock image references that path.

## Open question

`volcano-pmic-overlay.dtsi` maps `sys-therm-0` to
`PMK8550_ADC5_GEN3_AMUX_THM1_XO_THERM_100K_PU`, the XO thermistor, which runs
hotter than the case. Whether that is the right sensor to report as skin is
unconfirmed. Shipping stock's blob makes the question moot, since no severity is
derived from it either way.
