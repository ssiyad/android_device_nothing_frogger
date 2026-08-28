# The camera lens cannot be changed from the lock screen

A GCam port, launched over the lock screen, will not switch lens. The same
install switches freely once the phone is unlocked.

## What answers the lock screen shortcut

`org.codeaurora.snapcam` — the port itself. Its
`com.android.camera.SecureCameraActivity` takes
`android.media.action.STILL_IMAGE_CAMERA_SECURE` and targets
`com.google.android.apps.camera.legacy.app.activity.SecureCameraActivity`, so
what runs over the keyguard is Google Camera's own secure activity. Nothing here
asks for a restriction, and Google Camera imposes one of its own when started
that way, which is the likely answer.

**Aperture cannot be the control as things stand.** It is installed but disabled
— `enabled=3` in `dumpsys package`, and `pm list packages -d` lists it — so it
never enters the resolver and the secure intent will not start it. `am start`
against its `SecureCameraActivity` fails to resolve for the same reason.
Enabling it is a prerequisite for that comparison rather than a detail, and it
is worth knowing before an afternoon goes into wondering why the component does
not exist.

## The test that needs no UI

The camera service logs every client connection, which answers the question
outright:

```
dumpsys media.camera
```

`Camera service events log` carries `CONNECT device N client for package ...`.
It is per-boot, and it currently holds the five `ADD device` lines and no client
connections at all, so one lock screen camera session leaves an unambiguous
trace.

Five camera devices exist and two are normal, visible to API1. Unlocked, the
port reaches **device 3**, an aux — which is what a lens change looks like from
the service side.

| A locked session in the log | Means |
|---|---|
| reaches device 3 too | the camera list is intact and the refusal is the port's own UI |
| only ever device 0 | the aux cameras are unreachable before an unlock, and the fault sits below the app |

Only the second is ours.

## Why Aperture is the right control, once enabled

It gates camera selection on the keyguard nowhere. `SecureCameraActivity` is an
empty subclass of `CameraActivity` carrying only a different task affinity, so a
non-secure camera cannot be reached from a secure lock screen. `canFlipCamera`
is `camerasForCycling.keys.size > 1 && !cameraState.isRecordingVideo`, the flip
button's enabled state is `cameraState == CameraState.IDLE`, and the keyguard
checks that do exist govern the gallery button and the captured-media URIs.
`config_enableAuxCameras` is true in `rro_overlays/FroggerApertureOverlay`.

So if Aperture also refuses over the keyguard, the platform is at fault; if it
does not, the restriction belongs to the port. Read the failing case by layer
with [camera-diagnostics.md](../reference/camera-diagnostics.md).
