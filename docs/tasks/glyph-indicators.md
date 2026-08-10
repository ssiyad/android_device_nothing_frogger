# Verify the Glyph indicators

`glyph/` builds a persistent, headless system app that drives the strip from
three signals. None of it has run on the device yet.

## What to check

| Indicator | Expected |
|---|---|
| Recording | The red LED blinks while any camera app records video with audio. |
| Timer | The white segments drain from the top as a clock-app timer counts down, and freeze while it is paused. |
| Music | The white segments track the output mix while media plays, bass at the bottom. |

Also confirm the strip returns to stand-by when nothing is active, that no
denials appear against `glyph_app`, and that notification access is granted
without a visit to Settings.

## Known gaps

- Video recorded with audio muted raises no camcorder record client, so the red
  LED stays dark.
- The clock app publishes no total duration, only a deadline. The largest
  remaining time seen is taken as full scale, so a timer that was already
  running when the process started drains from a full bar.
- Pause is detected by matching the clock app's own paused label. Several timers
  paused at once carries a different label and is read as running.

## Rejected

**`ParanoidGlyphPhone4a`.** It does not exist upstream, and building a target
for it would have meant a Glyph app with its own settings for something the
kernel already exposes as three sysfs files.

**Driving it from SystemUI.** It already watches notifications and app-ops, so
it needs no new package, but it means carrying a device patch against
`frameworks/base` indefinitely.

**A native daemon.** Recording and music are both reachable from native audio
APIs, but notifications are not, and the timer is only published in one.
