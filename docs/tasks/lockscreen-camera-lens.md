# The camera lens cannot be changed from the lock screen

A GCam port, launched over the lock screen, will not switch lens. The same
install switches freely once the phone is unlocked.

**The likely answer is the port's own secure mode, not this tree.** The lock
screen shortcut starts whichever app answers
`android.media.action.STILL_IMAGE_CAMERA_SECURE`, and Google Camera restricts
what it will do when started that way; a port inherits the restriction along
with everything else. Nothing here asks for it.

So the question worth answering first is whether the platform is capable of it
at all, and Aperture is the control that answers it, because it demonstrably
imposes no such restriction of its own. `SecureCameraActivity` is an empty
subclass of `CameraActivity` carrying only a different task affinity, so that a
non-secure camera cannot be reached from a secure lock screen. Neither it nor
`CameraViewModel` consults `KeyguardManager` anywhere near camera selection:
`canFlipCamera` is `camerasForCycling.keys.size > 1 &&
!cameraState.isRecordingVideo`, the flip button's enabled state is
`cameraState == CameraState.IDLE`, and the keyguard checks that do exist govern
the gallery button and the captured-media URIs. `config_enableAuxCameras` is
true in `rro_overlays/FroggerApertureOverlay`.

Make Aperture the default camera app, so the lock screen shortcut reaches it,
and try the same thing:

| Aperture on the lock screen | Means |
|---|---|
| switches lens | the platform is fine and the restriction is the port's — nothing to do here |
| will not switch either | the camera list itself is shorter before an unlock, and the fault sits below the app |

Only the second outcome is ours. Read it by layer with
[camera-diagnostics.md](../reference/camera-diagnostics.md), comparing what the
provider publishes to a process on the secure lock screen against what it
publishes after an unlock — a shorter list there is the whole finding.
