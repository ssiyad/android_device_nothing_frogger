# Further Glyph indicators

A backlog. The panel and the notification listener already exist, so each of
these is a small addition rather than new plumbing.

## What the hardware is good for

The strip faces away from the user, so it only speaks when the phone is
face-down on a surface or held out towards someone. A face-down phone is a
deliberate state — it means the screen has been put away — which is exactly when
a silent channel is worth having. Anything that assumes the strip is visible
during normal use is decoration.

Six segments carry about two and a half bits of position. **Position reads at a
glance and brightness does not**, because the eye cannot rank brightness against
a shifting ambient level. Encode with column height and blink rhythm; spend
brightness on texture.

Red is the only colour contrast in the system and already means attention.
Diluting it costs more than any use of it gains.

## Candidates

| Indicator | Signal | Notes |
|---|---|---|
| Charge rate | `current_now` | Encode watts in the animation speed. Spots a weak charger or a bad cable from across the room, which no other surface reports at a glance. |
| Priority senders | `Ranking.getChannel().isImportantConversation()`, `Ranking.matchesInterruptionFilter()` | Both are configured through stock UI, so this needs no settings surface of its own. |
| Self-timer countdown | camera app | The strip faces the subject, so they can see the count rather than guessing. |
| Video fill light | all six at full | The driver's `video_mode` retunes the PWM to 4 kHz, which only matters against a rolling shutter. The hardware was designed for this. |
| Breathing pacer | none | Rise over four seconds, hold, fall over eight. The value is that there is nothing to look at. |
| App identity by rhythm | notification listener | Encode which app in a blink pattern rather than a position. A silent ringtone: learnable, and it needs no lookup. |
| Indeterminate progress | `EXTRA_PROGRESS_INDETERMINATE` | The determinate case ships. A sweep would cover the rest. |

## Ruled out

**Tap to peek.** `org.lineageos.sensor.single_tap` is a Focaltech touch-panel
gesture — `fts_gesture_single_tap_pressed` on the SPI node — not an
accelerometer heuristic, so it only sees taps on the screen. Face-down, the
screen is against the table and the sensor cannot be reached. The proximity
sensor faces the same wrong way, and SystemUI gates the tap on it anyway.

Every gesture sensor on this device points at the face that is hidden whenever
the strip is visible. Detecting the flip is what replaces it, and the vendor
`screen_upward` sensor reports that directly.
