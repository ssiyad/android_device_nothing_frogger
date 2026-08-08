# Camera diagnostics

The stack fails in layers and each layer names its own failure.

```sh
adb logcat -c && adb logcat -b crash -c
# exercise the camera
adb logcat -b crash -d | grep -E "Executable|signal|#0[0-6] pc"
adb logcat -d | grep -E " E (CamX|ChiX)" | grep -viE "PreLoadLiberary|PopulateFuseId"
```

`PreLoadLiberary` and `PopulateFuseId` are logged at ERROR and are noise.

## Which layer

| Framework error | Meaning |
|---|---|
| `Number of camera devices: 0` | sensors did not probe — kernel, devicetree or regulator |
| `Function not implemented (-38)` | HAL rejected the config — usually a missing node or blob |
| `Broken pipe (-32)` | HAL died — get the tombstone, it names the library |

## State checks, cheapest first

```sh
adb shell 'lsmod | grep -E "sgm38120|wr1241"'
adb shell 'cat /sys/class/regulator/*/name | grep -E "SGM|WR_"'
adb shell 'ls /sys/bus/platform/devices/*cam-sensor0/waiting_for_supplier'   # should not exist
adb shell 'ls /dev/v4l-subdev* | wc -l'                                      # expect 25
adb shell 'dumpsys media.camera | grep "Number of camera"'                   # expect 5
adb shell 'ls /sys/bus/platform/drivers/qcom,camera/'
```

`waiting_for_supplier` present means a regulator never registered and
`fw_devlink` is blocking probe indefinitely.

Sensor nodes live under the CCI nodes, not `/soc`:

```sh
adb shell "ls '/sys/firmware/devicetree/base/soc/qcom,cci0@ac15000/'"
```

Looking under `/soc/qcom,cam-sensor*` finds nothing and resembles an overlay that
failed to apply.

`camxoverridesettings.txt` raises CamX logging via `chiLogInfoMask`, commented
out in stock. It is cheaper than blob diffing when a `dlopen` target needs
naming.

## CHI node dependencies

CHI nodes `dlopen` their libraries, so absence from `DT_NEEDED` proves nothing
about whether a node requires a library. Diff whole directories against stock
rather than reasoning from the ELF headers of one blob.

## dmesg

Kernel logs rotate in about four minutes on this device. Capture promptly.
