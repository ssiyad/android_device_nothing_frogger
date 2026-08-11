# Verify the Glyph indicators

`glyph/` builds a persistent, headless system app that drives the strip from
three signals. None of it has run on the device yet.

## What to check

| Indicator | Expected |
|---|---|
| Timer | The white segments fill from the bottom as a clock-app timer runs out, and freeze while it is paused. |
| Progress | A download or other determinate progress notification fills the segments, and yields to a timer. |
| Music | The column rises and falls with how loud the output mix is, and reads at a glance. |
| Face-down | Setting the phone down on its face shows the charge level for three seconds, over anything else, and hands the strip back after. |
| Waiting | A missed call, a priority conversation, or a channel the user themselves set to alert leaves red glowing dim until it is dealt with. |
| Ringing | An incoming call drives the meter from the ringtone itself, over everything except the face-down status. |
| Capture | A shutter click or a video starting or stopping flashes red once, over the recording blink. |

Recording is confirmed: the red LED blinks while the camera app records with
audio. So are the timer bar, the release back to stand-by, and the `glyph_app`
domain running clean with no denials.

A spectrum across the six segments was tried first and read as undifferentiated:
six brightnesses cannot be ranked by eye, and music's energy sits so low that
the bass segments pinned while the rest stayed dark. Loudness as the height of
the column replaces it, scaled against a decaying peak so quiet material still
uses the whole strip, with the topmost lit segment carrying the fraction.

Bluetooth is worth a pass of its own. `selectOutputForMusicEffects()` picks the
output from wherever `USAGE_MEDIA` currently routes, so the visualiser should
follow an A2DP route, but it prefers a compressed-offload output and the effect
forces such a stream back to PCM. Check that it still reacts, and watch what it
costs.

## What the logs should settle

`screen_upward` and `pocket_mode` are Nothing sensors with no source in the
tree, and which value each reports for which state is undocumented. Both are
logged at every change under the `Glyph` tag. Flip the phone and pocket it once
with `logcat -s Glyph` running, and both polarities are settled.

The accelerometer sample exists to avoid needing the first of those. Once the
pocket polarity is known, the gate can be tightened without guessing.

## Known gaps

- Video recorded with audio muted raises no camcorder record client, so the red
  LED stays dark.
- The clock app publishes no total duration, only a deadline. The largest
  remaining time seen is taken as full scale, so a timer that was already
  running when the process started drains from a full bar.
- Pause is detected by matching the clock app's own paused label. Several timers
  paused at once carries a different label and is read as running.

## Rejected

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
