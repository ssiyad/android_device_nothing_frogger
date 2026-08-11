# Verify the Glyph indicators

`glyph/` builds a persistent, headless system app that drives the strip. Most of
it is confirmed on the device; what is left is below.

## Confirmed

The countdown and its blocks, the progress bar, the charge column on setting the
phone down, the beat meter and its face-down gate, the recording blink, the
release back to stand-by, and the `glyph_app` domain running with no denials.

## Still open

| Indicator | State |
|---|---|
| Missed-call alert | Does not light. Deferred. |
| Capture flash | Never yet seen working. A white flash from the meter masked it every time, so a photo taken face-up with the meter detached is the first clean look. |
| Waiting glow | Has been seen staying lit after notifications were cleared, with nothing among them that should match. It now logs `waiting on <package> <key>`, which names the cause the next time it happens. |
| Bluetooth | Untried. `selectOutputForMusicEffects()` picks the output from wherever `USAGE_MEDIA` routes, so the meter should follow an A2DP route, but it prefers a compressed-offload output and an effect forces such a stream back to PCM. Watch what it costs. |
| `pocket_mode` | Reports two fields and its meaning is unread. Logged at every change, so one trip in a pocket settles it. |

A `default_prop` read is denied to `glyph_app` and nothing observed has broken
because of it, but something the app asks for is being refused silently.

## Known gaps

- Video recorded with audio muted raises no camcorder record client, so the red
  LED stays dark.
- The clock app publishes no total duration, only a deadline. The largest
  remaining time seen is taken as full scale, so a timer already running when
  the process started begins from an empty bar and fills over what is left of
  it, rather than from where it had actually reached.
- Pause is detected by matching the clock app's own paused label. Several timers
  paused at once carries a different label and is read as running.
- The meter captures the whole output mix, so while it is attached every sound
  the phone makes drives it. It lets go after four seconds of quiet, which
  bounds how long that window stays open rather than closing it.

## Rejected

**A spectrum across the six segments, and then loudness as the height.** The
spectrum read as undifferentiated: six brightnesses cannot be ranked by eye, and
music's energy sits so low that the bass segments pinned while the rest stayed
dark. Loudness replaced it and was no better, for a reason no retuning reaches —
music is mastered to a near-constant level, so a mean over a short window does
not move while a track plays, and the bar parks at whatever that master's
loudness happens to be. Bass energy with a fast attack and a slow release is
what moves with the music, because the beat is the part that varies.

**Channel importance on its own as a measure of importance.** It reads as the
obvious app-independent floor and is worthless: 172 of the 519 channels on this
phone sit at high or above, because high is simply what a channel that alerts
costs, so the test matched everything that made a sound. `hasUserSetImportance`
is the field that separates a decision from a default, and it holds for seven.

Conversations cannot carry the test alone either. WhatsApp posts every ordinary
chat to a shared `individual_chat_defaults` channel and creates no conversation
channels at all, so a rule built on them is inert on the phone it has to work
on. Per-chat channels appear only once custom notifications are turned on for
that chat, and the importance still has to be set from Android's settings
rather than in the app, since an app choosing its own channel's importance does
not set the user-locked bit.

**Blinking the whole strip for short sounds.** Touch feedback, the lock and
unlock sounds and the charging chime all carry
`USAGE_ASSISTANCE_SONIFICATION`, and notification tones carry
`USAGE_NOTIFICATION`; nothing in the attributes separates a deliberate tap from
unlocking the phone. Flashing on either is unwanted, and flashing during use is
wasted anyway, since the strip faces away from whoever is holding it.

**A declared notification listener approved by `config_defaultListenerAccessPackages`.**
That configuration seeds the approved list only when a profile is created, so a
declared listener is inert on any device that was not wiped — it binds on a
clean flash and never again. Registering as a system service instead needs no
approval, survives an existing profile, and keeps the component out of the
user's notification-access list, where it would otherwise invite being switched
off.

**`ParanoidGlyphPhone4a`.** It does not exist upstream, and building a target
for it would have meant a Glyph app with its own settings for something the
kernel already exposes as three sysfs files.

**Driving it from SystemUI.** It already watches notifications and app-ops, so
it needs no new package, but it means carrying a device patch against
`frameworks/base` indefinitely.

**A native daemon.** Recording and music are both reachable from native audio
APIs, but notifications are not, and the timer is only published in one.
