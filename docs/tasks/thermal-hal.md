# Confirm the stock thermal HAL on the device

The swap builds. `mka check-vintf-all vendorimage` reports COMPATIBLE, and the
vendor image carries the 239008-byte blob at the stock path, byte-identical to
the extracted copy. See [thermal.md](../reference/thermal.md) for the shape of
the change.

What is unproven is runtime behaviour, which needs a ROM flash.

## Verify on the flashed build

```sh
adb shell ls -l /vendor/bin/hw/android.hardware.thermal-service.qti
adb logcat -d | grep -i "Thresholds is not defined"
adb shell dumpsys thermalservice | grep -A2 "HAL connection"
```

239008 bytes is stock's, 301744 the source build's. The threshold message
confirms the blob found no configuration and so computes no severity.

`Thermal Status: 0` should then hold with the device warm — worth checking under
a load that used to trip it, not at idle, since the source HAL only misbehaved
above 36.5 °C skin.
