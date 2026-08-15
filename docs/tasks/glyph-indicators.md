# Verify the Glyph indicators

How the app works is in [glyph.md](../reference/glyph.md). What is left is
watching the indicators themselves, which needs the phone handled rather than
queried.

## Standing up

Confirmed on the device: the process survives boot with no crash, `Gate` holds a
connection to `screen_upward` and cycles the accelerometer to settle each flip,
`NotificationIndicator` is bound as a SYSTEM listener at `USER_ALL`, the call
state is registered (`events=[6]`, `notifyNow=false`), and `Panel` reaches the
hardware — the aw20036 driver logs the frame, the red register and the drop back
to stand-by.

None of that says an indicator looks right, only that nothing is in its way.

## Unconfirmed

| Indicator | What to watch for |
|---|---|
| The gate itself | The one decision nothing has tested. Face-down with the screen still on should stay dark, and `gate open` should appear only once the screen goes off. |
| Silent ring | A silenced call never reaches `MODE_RINGTONE`, so this rests entirely on the call state, which is now registered. A call to a silenced phone is the whole test. |
| Favourite's missed call | The caller is a display name matched against the contacts, which is the weak leg. Call from a starred contact and from an unstarred one; `Home` is starred here and `Home 2` is not, which makes them the pair to try. |
| Media sessions | Whether the meter runs at all. Nothing is denied, but no music has played through it yet. |
| Missed-call glow | Recognising `phone_missed_call` should light it for the first time. |
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
