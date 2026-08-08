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

## Proving a rail is actually driven

A device node existing proves the driver loaded, not that anything uses it. For
anything that ends in a regulator — SOIS, a sensor supply — the rail itself is
the evidence. Snapshot every camera rail, open the camera, snapshot again, diff:

```sh
snap() { adb shell "su -c 'for d in /sys/class/regulator/*/; do
  n=\$(cat \$d/name 2>/dev/null)
  case \$n in SGM*|WR_*) echo \"\$n \$(cat \$d/state)\";; esac
done'" | tr -d '\r' | sort; }

snap > /tmp/closed.txt
# open the camera, wait ~10s
snap > /tmp/open.txt
diff /tmp/closed.txt /tmp/open.txt
```

Diffing *all* of them is what makes this trustworthy — the rails that obviously
must come on are the control. If none change, the session never started; if only
some change, read which sensor is streaming before concluding anything.

Three traps, each of which reads as a broken driver:

- **`num_users` is 0 even for rails in use.** It is not a consumer count you can
  read this way. `SGM_LDO4` (`cam_vio`) reports 0 with a session live. Use
  `state`.
- **`/sys/kernel/debug/regulator/` does not exist here**, so `regulator_summary`
  is not available to list consumers. Establish the sole consumer from the
  devicetree instead.
- **The rails belong to one sensor.** A wide-sensor rail stays off during a tele
  session, which looks identical to a broken driver. Confirm which camera opened
  (`CameraId-N opened successfully`) before reading the diff.

## Opening the camera from adb is unreliable

`am start -a android.media.action.STILL_IMAGE_CAMERA` resolves to
`ResolverActivity` — a chooser — because no app is the default handler. The
intent silently lands on a gallery instead, and a rail diff taken around it shows
nothing changing for the obvious reason.

```sh
adb shell 'cmd package resolve-activity --brief -c android.intent.category.LAUNCHER org.lineageos.aperture'
adb shell 'am start -n org.lineageos.aperture/.CameraLauncher'
adb shell 'dumpsys window | grep mCurrentFocus'   # always confirm what came up
```

The GCam fishfood build that is installed has no launcher activity and cannot be
started this way at all. Aperture can. Always verify the foreground activity
before trusting any measurement taken around it.

## CHI node dependencies

CHI nodes `dlopen` their libraries, so absence from `DT_NEEDED` proves nothing
about whether a node requires a library. Diff whole directories against stock
rather than reasoning from the ELF headers of one blob.

The converse check is still worth running, and is cheap — it now comes back
clean:

```sh
cd vendor/nothing/frogger/proprietary/vendor
for f in $(find lib64/camera lib64/libcamx* lib64/libchi* -name "*.so"); do
    readelf -d "$f" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'
done | sort -u > /tmp/needed.txt
adb shell 'ls /vendor/lib64/ /vendor/lib64/camera/components/ /system/lib64/' |
    sort -u > /tmp/ondevice.txt
comm -23 /tmp/needed.txt /tmp/ondevice.txt
```

## `dlopen` failures hide outside the CamX tags

`libQnnHtpV73Stub.so` was missing while every `CamX`/`ChiX` grep looked healthy,
because the QNN runtime logs the failure under its own `QnnDsp` tag as a
**warning**, and only the downstream `Failed to load skel` is an error. The DSP
half of the pair (`vendor/lib/rfsa/adsp/libQnnHtpV73Skel.so`) shipped from the
start, which is what made the gap easy to miss.

Grep a session for `dlopen`, `not found` and `No such file` without filtering by
tag before concluding the blob set is complete.

## dmesg

Kernel logs rotate in about four minutes on this device. Capture promptly.
