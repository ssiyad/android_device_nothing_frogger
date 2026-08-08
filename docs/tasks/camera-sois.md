# Port the SOIS sensor driver

SOIS is Nothing's sensor-OIS path. It talks to `/dev/nt_cam_dev`, which this
tree's camera-kernel does not create.

`extract-files.py` currently forces both masks off in
`vendor/etc/camera/camxoverridesettings.txt`:

```
enableCameraSOISMask=0x0        # stock: 0x9
SOISOptimizationEnable=0x0      # stock: 0x9
```

Completing the port removes that workaround and restores OIS on the tele module.

## Scope

`cam_sensor_nothing.c` is the only file the camera-kernel lacks; 561 of 563
sources match the OEM tree. Every OEM addition is marked
`// xft add for nothing custom`.

| File | Change |
|---|---|
| `cam_sensor_nothing.c` | new, 633 lines |
| `cam_sensor_nothing.h` | new, 103 lines |
| `Kbuild` | one line, `cam_sensor_nothing.o` after `cam_sensor_soc.o` |
| `cam_sensor_dev.c` | `#include`, then `cam_nt_driver_init()` |
| `cam_sensor_soc.c` | `#include`, then `cam_nt_get_ois_power(s_ctrl)` |
| `cam_sensor_dev.h` | five `extern_*` fields for extern i2c probe |
| `cam_sensor_core.c` | `#include`, `cam_nt_driver_errcode()` and `cam_nt_sctrl_save()` at probe, power, init and i2c error sites |

Source path in the OEM 6.1 tree:
`vendor/qcom/opensource/camera-kernel/drivers/cam_sensor_module/cam_sensor/`.

## Risk

`cam_sensor_core.c` sits on the sensor probe path that all five sensors
currently use. Confirm probe still succeeds before touching anything else, and do
this in isolation rather than alongside other camera work.

## Not related to `swpnc`

`com.qti.node.swpnc` consumes OIS samples and its initialisation failure is
independent of the mask value — the mask gates *use* of OIS data, not the node's
`LoadLib()`. Disabling SOIS does not change that failure, so the driver port is
not a route to fixing it.
