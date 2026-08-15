# Verify the Glyph indicators

How the app works is in [glyph.md](../reference/glyph.md). What is left is
confirming the parts that have never been watched on the device.

## Unconfirmed

| Indicator | What to watch for |
|---|---|
| Silent ring | Whether a call reaching a silenced phone raises `MODE_RINGTONE` at all. If it does not, the telephony call state is the only thing that catches it, and that needs `READ_PHONE_STATE` to have been pre-granted. Both paths log. |
| Favourite's missed call | Whether the notification carries a `Person` or the legacy people array. If it carries neither, the blink is resting on a display-name match against the contacts, which is the weak leg. |
| Media sessions | Whether `getActiveSessions(null)` is answered. It needs `MEDIA_CONTENT_CONTROL`, which the platform signature should grant; a `SecurityException` is logged if not, and the meter then never runs. |
| Missed-call glow | Recognising the two dialer channels should light it for the first time. |
| Alert pattern | Whether a phone on silent, face-down, announces an arriving message — the whole point of it. |
| Capture flash | Never yet seen working. A white flash from the meter masked it every time, so a photo taken face-up with the meter detached is the first clean look. |
| Waiting glow | Has been seen staying lit after notifications were cleared, with nothing among them that should match. It logs `waiting on <package> <key>`, which names the cause the next time it happens. |
| Bluetooth | Untried. `selectOutputForMusicEffects()` picks the output from wherever `USAGE_MEDIA` routes, so the meter should follow an A2DP route, but it prefers a compressed-offload output and an effect forces such a stream back to PCM. Watch what it costs. |
| `pocket_mode` | Reports two fields and its meaning is unread. Logged at every change, so one trip in a pocket settles it. |

A `default_prop` read is denied to `glyph_app` and nothing observed has broken
because of it, but something the app asks for is being refused silently.

## Known gaps

- Video recorded with audio muted holds no microphone, so the red LED stays
  dark. Camera-open would catch it, but a viewfinder is not a capture.
- The clock app publishes no total duration, only a deadline. The largest
  remaining time seen is taken as full scale, so a timer already running when
  the process started begins from an empty bar and fills over what is left of
  it, rather than from where it had actually reached.
- Pause is detected by matching the clock app's own paused label. Several timers
  paused at once carries a different label and is read as running.
- The meter captures the whole output mix, so while it is attached every sound
  the phone makes drives it. It lets go after four seconds of quiet, which
  bounds how long that window stays open rather than closing it.
- The missed-call blink stops after a minute and leaves the glow. An indefinite
  blink would hold a wakelock for as long as the notification sat there.
