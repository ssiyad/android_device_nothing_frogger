# Further Glyph indicators

A backlog. The panel, the pattern player and the notification listener already
exist, so each of these is a small addition rather than new plumbing. What the
hardware is good for and what has already been ruled out are in
[glyph.md](../reference/glyph.md).

| Indicator | Signal | Notes |
|---|---|---|
| Charge rate | `current_now` | Encode watts in the animation speed. Spots a weak charger or a bad cable from across the room, which no other surface reports at a glance. |
| Video fill light | all six at full | The driver's `video_mode` retunes the PWM to 4 kHz, which only matters against a rolling shutter. The hardware was designed for this. |
| Breathing pacer | none | Rise over four seconds, hold, fall over eight. The value is that there is nothing to look at. |
| App identity by rhythm | notification listener | Encode which app in the alert's rhythm rather than only its direction. Learnable, and it needs no lookup. |
| Indeterminate progress | `EXTRA_PROGRESS_INDETERMINATE` | The determinate case ships. A sweep would cover the rest. |
