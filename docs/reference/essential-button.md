# Essential Button

`essentialbutton/` builds the app behind the extra hardware button. It is signed
with the platform key, shares `android.uid.system`, and has two halves that run
in different processes.

## The button

`ai_key` in the OEM devicetree, `noth/frogger-common-pmic.dtsi`:

| Fact | Value |
|---|---|
| GPIO | tlmm 71, `GPIO_ACTIVE_LOW` |
| Keycode | `KEY_AI`, 250 |
| Input device | `gpio-keys` — the same node as volume up |
| Debounce | 15 ms |
| Wake | `gpio-key,wakeup`, so a press resumes the SoC |

`KEY_AI` is a Nothing addition to `input-event-codes.h`; 249 and 250 are
unassigned upstream. The same node exists in `asteroids-common-pmic.dtsi`, so
anything here applies to that device too.

Stock maps no keycode for 250 at all — its `gpio-keys.kl` stops at `CAMERA`, and
`com.android.server.wm.NtEssentialButtonImpl` in `nt-services.jar` reads the raw
scancode. That route needs a framework fork, so it is not available here.

## Why the keycode is `MACRO_1`

`configs/gpio-keys.kl` maps 250 to `MACRO_1`. It was `ASSIST`, inherited from the
asteroids tree, and that cannot work:

- `interceptKeyBeforeQueueing` runs `handleKeyGesture()` — the
  `SingleKeyGestureDetector` — before `dispatchKeyToKeyHandlers()`. Consuming the
  event does not un-fire a rule that has already run.
- `updateKeyAssignments()` sets `mAssistPressAction = Action.SEARCH` and
  `mAssistLongPressAction = Action.VOICE_SEARCH` unconditionally, and only reads
  `LineageSettings` over the top of them when `KEY_MASK_ASSIST` is set. Clearing
  that bit therefore *locks* the assistant on rather than switching it off.

`MACRO_1` is what Android reserves for a programmable button. No
`SingleKeyGestureDetector` rule claims it, nothing under `server/input/` mentions
it, and `KeyGestureController.interceptKeyBeforeQueueing` only delegates to
`KeyCombinationManager` and cannot consume, so `PhoneWindowManager` is always
reached. `PhoneWindowManager` clears `ACTION_PASS_TO_USER` for `MACRO_1`–`MACRO_4`
outright, so no application ever sees the key even if nothing consumes it first.

The line carries no `WAKE` flag. With one, `isWakeKeyWhenScreenOff` sets
`mPendingWakeKey` for a wake that never happens, because consuming the event
returns before `wakeUpFromWakeKey()`. The devicetree already resumes the SoC, and
which actions light the screen is a decision the app makes.

`config_deviceHardwareKeys` and `config_deviceHardwareWakeKeys` are `64` — volume
rocker only. Claiming the assist bit for a key that no longer emits `ASSIST`
would only put a dead *Assist key* screen in LineageParts.

`/vendor/usr/keylayout/` beats `/system` in the `InputDevice.cpp` search order,
and `gpio-keys` is a virtual device with vendor and product both 0, so the
canonical name resolves without the vendor/product forms being tried.

## The two halves

`config_deviceKeyHandlerLibs` and `config_deviceKeyHandlerClasses` in
`overlay-lineage/` name an apk path and a class. `PhoneWindowManager` loads the
class out of that apk with a `PathClassLoader` and runs it **inside
system_server**. Two things follow.

**No sepolicy.** The handler is in the `system_server` domain and the picker is
an ordinary `android.uid.system` app that
`system/sepolicy/private/seapp_contexts` already covers as `system_app`. Giving
this a domain of its own the way `glyph_app` has one would reintroduce the
public-type failure that keeps `DeviceExtras` switched off.

**The apk path is a literal, checked by nothing.** It is written in
`essentialbutton/Android.bp` by omission and in `overlay-lineage/.../config.xml`
by hand. The app is not `privileged`, so it installs to `/system_ext/app/`; adding
`privileged: true` would move it to `priv-app/`, the class would stop being
found, and no build step would say so. Overriding the arrays also *replaces*
them rather than adding, so the LineageParts entry has to be repeated — dropping
it takes the touchscreen gestures with it.

The failure is silent in both directions: `MACRO_1` reaches no application, so a
handler that never loaded leaves the key completely dead rather than falling back
to anything. `KeyHandler`'s constructor logs unconditionally, and that line is
the only evidence the override took effect.

## Gestures

`handleKeyEvent` is called on the input dispatcher's thread, so it recognises the
keycode, takes a timed wakelock and hands two primitives to a thread of its own.
Blocking there stalls every key and touch on the device.

Because `ACTION_PASS_TO_USER` is cleared, the event is never dispatched, the
dispatcher synthesises no repeats and nothing sets `FLAG_LONG_PRESS`: exactly one
down and one up arrive per press, and every timer is the app's own.

| Gesture | When it fires |
|---|---|
| Long press | 500 ms after the down, while still held, with haptic feedback. The release that ends it is swallowed. |
| Double press | On the second release. |
| Single press | 300 ms after the release, always — including when no double press is configured. |

The single press waits even when nothing else could be coming, because skipping
the wait would make the key feel different depending on a setting belonging to a
different gesture.

The multi-press window is measured from the release, where
`SingleKeyGestureDetector` measures down to down. That is the same clock the
single press already waits on, so the two cannot disagree about which gesture
happened.

A long press outranks a pending double: press-then-press-and-hold gives the long
action.

## Actions

Whether an action wakes the screen belongs to the action. A torch or a skipped
track earns its place by working with the phone in a pocket; a screenshot nobody
can see is worthless.

| Action | Screen | How |
|---|---|---|
| Flashlight | stays off | `CameraManager.setTorchMode` on the rear camera |
| Play/pause, next, previous | stays off | `MediaSessionLegacyHelper` |
| Do Not Disturb | stays off | `NotificationManager.setZenMode`, `fromUser` set |
| Ringer mode | stays off | `setRingerModeInternal`, cycling ring → vibrate → silent |
| Rotation lock | stays off | `RotationPolicy.setRotationLock` |
| Screenshot | wakes | `ScreenshotHelper`, after the display reports `STATE_ON` |
| Camera | SystemUI wakes | broadcast `lineageos.intent.action.SCREEN_CAMERA_GESTURE` |
| Open application | wakes | `startActivityAsUser` with `setDismissKeyguardIfInsecure()` |

Four of these are easy to get subtly wrong:

- **`wakeUp()` is asynchronous.** A capture taken before the display is up is a
  black rectangle, so the screenshot waits on a `DisplayListener` with a timeout
  rather than on a guessed delay.
- **`ScreenshotHelper` registers a receiver on every call and unregisters none**,
  so one instance is shared rather than built per screenshot.
- **The `Internal` audio and zen setters are the right ones.** The public
  `setRingerMode` applies the do-not-disturb reconciliation meant for
  third-party callers.
- **`setDismissKeyguardIfInsecure()` is the right keyguard behaviour** — an
  insecure keyguard gets out of the way, a secure one stays up and the app
  appears once unlocked. `dismissKeyguard` would prompt for credentials, which
  is the wrong answer to a button press.

The camera goes through SystemUI rather than an activity launch because SystemUI
owns which camera a locked phone may open, and it wakes the screen itself.

## Configuration

Three `Settings.System` keys, holding a token rather than an ordinal:

```
essential_button_single_press    default screenshot
essential_button_double_press    default none
essential_button_long_press      default none
```

`none`, `screenshot`, `camera`, `flashlight`, `play_pause`, `next`, `previous`,
`dnd`, `ringer`, `rotation`, or `app:` followed by a flattened `ComponentName`.

Tokens rather than the ordinals Lineage's own key actions use: an ordinal list
has to keep its order forever and carries a validator that has to be widened
every time it grows, while a token survives `settings get` and means the same
thing next year. `open_app` appears in the picker's entry values but is never
stored — choosing it writes the component instead.

`SettingsProvider` is `directBootAware` and `/data/system/users/<id>` is
device-encrypted, so these read on the lock screen before the first unlock.
Writing keys the provider has never heard of is unrestricted for `SYSTEM_UID`,
and `installSystemProviders()` runs before `WindowManagerService.main()`, so the
provider is up by the time the handler is constructed.

## Where the settings live

**In LineageParts, not in this app.** The three gestures are an *Essential
button* section in the buttons screen, between *Power button* and *Home button*,
carried by `patches/packages_apps_LineageParts/`. This app has no settings
screen and no launcher filter; what is left of its UI is the app picker.

The buttons screen offers a hook for a device-specific key — a `RemotePreference`
in its Extras category guarded by `lineage:requiresAction` — but that hook is one
row pinned at the bottom of the screen, and a section in a chosen position is not
something it can give. Editing `button_settings.xml` is the only way to place
one, which is why this is a patch rather than a manifest action.

Nothing is duplicated: the rows moved rather than being copied, so the tokens,
the defaults and the action list exist once, in LineageParts. They must still
agree with `Constants` here, which is what the key handler reads.

The patch is shaped to survive rebasing. Everything that thinks lives in a new
file, `input/EssentialButtonPreferences.java`, which cannot conflict;
`ButtonSettings` gains only two lines, a `setup()` in `onCreate` and a `refresh()` in `onResume`.
The rows reuse `hardware_keys_short_press_title`, `hardware_keys_double_tap_title`
and `hardware_keys_long_press_title`, so only the actions no other key offers are
named. `apply.sh` fails the build if any hunk stops applying, so upstream moving
underneath this is loud rather than silent.

`EssentialButtonPreferences` removes the whole section unless
`org.lineageos.essentialbutton.PICK_APP` resolves against a system package,
which is how the patch stays inert on any device without the button.

**The picker writes its own result.** LineageParts hands it the gesture's
`Settings.System` key and starts it without waiting for a result; it stores
`app:<component>` itself, and `onResume` is what shows the change. That keeps
result plumbing out of a shared repo, and backing out of the picker stores
nothing, so the gesture keeps what it had. The picker is exported, so the key it
is handed is checked against the three gestures before anything is written.

Nothing indexes the rows for Settings search. An injected tile was indexed by its
own title and summary; preferences inside LineageParts are indexed by its own
search provider, which this patch does not touch, so reaching them means
searching for *Buttons*.

## Verification

```sh
adb shell dumpsys input | grep -A6 -i 'gpio-keys'   # expect /vendor/usr/keylayout/
adb logcat -b all -d | grep 'EssentialButton: KeyHandler loaded'
adb shell settings get system essential_button_single_press
```

A missing `KeyHandler loaded` means the resource override did not apply, or the
apk path or class name is wrong. `overlay-lineage` is compiled to a runtime
overlay here, because `PRODUCT_ENFORCE_RRO_TARGETS := *`, so a mistyped array
name fails at runtime with nothing said at build time.
