# Glyph

`glyph/` builds a persistent, headless system app that drives the LED strip on
the back of the phone. It is signed with the platform key, shares
`android.uid.system`, and runs in the `glyph_app` SELinux domain.

## The strip

The aw20036, at `/sys/class/leds/aw20036_led/`.

| Node | Use |
|---|---|
| `frame_brightness` | Six space-separated values, the white segments, index 0 at the top |
| `single_brightness` | `<register> <value>`; the red indicator is register 26 |
| `operating_mode` | 1 active, 2 stand-by |
| `always_on` | Power to the chip |

The driver latches the red value and re-applies it on every six-value frame
write, so red and white never contend.

Suspend powers the chip down and clears every brightness register, and resume
brings it back in stand-by. What mode it was left in therefore cannot be
remembered across a write, and the mode is set on every write instead. The
driver ignores a mode it is already in, which makes that close to free.

The strip holds its own pattern across suspend but a release does not, so
anything that has to stop by itself needs a wakelock for as long as it runs.

## Ownership

Every owner's last frame is kept rather than only the winning one, so a brief
high-priority interruption falls back to what was underneath instead of leaving
the strip dark. An owner holding a blank frame does not win, or silence on the
meter would blank a running countdown.

White, lowest to highest:

| Owner | Shows |
|---|---|
| `OWNER_MUSIC` | The beat meter |
| `OWNER_NOTIFICATION` | Progress and the countdown |
| `OWNER_STATUS` | The charge column |
| `OWNER_ALERT` | A notification arriving |
| `OWNER_RINGING` | A call coming in |

Red, lowest to highest:

| Owner | Shows |
|---|---|
| `RED_WAITING` | Steady glow: something is waiting |
| `RED_MISSED` | Blink: a call missed from a favourite |
| `RED_RECORDING` | Blink: the microphone is open |
| `RED_CAPTURE` | Flash: a shutter |

Red carries its two meanings by rhythm — a glow for something waiting, a blink
for something happening. An owner keeps red until it releases, because a blink
writes zero for its dark half and that half has to stay dark rather than falling
through to a glow underneath.

## The gate

**Face-down and screen off, both.** Everything that is merely worth seeing waits
on it; everything that is worth knowing does not.

| Behind the gate | Not behind it |
|---|---|
| The beat meter | The ring pattern |
| Progress and the countdown | The microphone blink |
| The red waiting glow | The shutter flash |
| The alert pattern | The favourite's missed-call blink |
| | The charge column |

Face-down comes from the vendor `screen_upward` sensor, which is on-change and
wakes the machine, so it costs nothing while the phone sits still and works with
the screen off. It only reports that the up-down state changed; a single
accelerometer sample says which way, which avoids depending on a polarity
nothing in the tree documents. Eight samples are taken before deciding, because
a phone is still being put down when the flip is reported and the first sample
catches it mid-air. The sample has to be flat rather than merely past the
horizontal, which is what keeps a pocket from lighting the strip.

Posture alone was not dependable enough to gate on — the strip ran while the
phone was in use. The screen is the second opinion and costs nothing.

The charge column answers to posture alone rather than to the gate, because
putting the phone down is a moment and the screen is still on for the whole of
it.

## What drives what

| Indicator | Signal |
|---|---|
| Beat meter | `Visualizer` on the output mix, bass energy 40–260 Hz |
| Music present | An active `MediaSession` playing, with media usage |
| Ring | Telephony `CALL_STATE_RINGING`, or audio mode `MODE_RINGTONE` |
| Countdown | A `Chronometer` inflated out of the clock app's RemoteViews |
| Progress | `EXTRA_PROGRESS` against `EXTRA_PROGRESS_MAX` |
| Alerts, waiting, missed calls | The notification listener |
| Microphone | `AudioRecordingConfiguration.getClientAudioSource()` |
| Shutter | `USAGE_ASSISTANCE_SONIFICATION` with `FLAG_AUDIBILITY_ENFORCED` |
| Charge | `BATTERY_PROPERTY_CAPACITY` |

The listener registers itself as a system service rather than being bound as a
declared one, so it never appears in the user's notification-access list and
never depends on it.

## Reading the notifications

**Importance is not a measure of importance.** 172 of the 519 channels on this
phone sit at high or above, because high is simply what a channel that alerts
costs. `hasUserSetImportance` narrows that to seven and is still wrong: one of
the seven is `com.android.messaging`'s `messaging_channel`, at high, which lit
the strip for every SMS.

What is used instead are the two settings that mean this and nothing else —
`isImportantConversation()` and `canBypassDnd()`. Four channels match here.

Conversations cannot carry the test alone. WhatsApp posts every ordinary chat to
a shared `individual_chat_defaults` channel and creates no conversation channels
at all. Per-chat channels appear only once custom notifications are turned on
for that chat, and the importance still has to be set from Android's settings
rather than in the app, since an app choosing its own channel's importance does
not set the user-locked bit.

**Neither dialer sets `CATEGORY_MISSED_CALL`.** Missed calls are recognised by
channel as well: `phone_missed_call` from `com.android.dialer`, and
`TelecomMissedCalls` from `com.android.server.telecom`.

**The dialer names the caller in the text, not the title.** Its missed-call
notification is titled "Missed call" and carries no `Person` and no people
array. The text holds the contact's display name when it knows one and the bare
number when it does not, so it is tried as both. Two missed calls also raise a
group summary on the same channel, which names no caller.

An arrival is a key not seen before. A posted notification is not a new one —
a progress update arrives the same way, and so does every repost an app makes of
something it is already showing.

## A silent ring never reaches the audio mode

**`MODE_RINGTONE` is not set for a call the phone will not ring for**, so the
audio mode alone cannot see a silent ring and `READ_PHONE_STATE` is load-bearing
rather than a nicety. Telecom's `CallAudioModeStateMachine` enters its ringing
state and then takes the branch that does not acquire focus:

```
CallAudioModeStateMachine: Audio focus entering RINGING state
CallAudioModeStateMachine: RINGING state, try start ringing but not acquiring audio focus
RingerAttributes{... mAcquireAudioFocus=false, mRingerAudible=false ...}
Ringer: ringer & haptics are off, user missed alerts for call
```

`setMode` is called once for the whole call, with `MODE_NORMAL`, on the way out.
The audio mode is still watched because it is what catches a VoIP app, which
asks for ringing focus without going through telephony at all.

Position reads at a glance and brightness does not, because the eye cannot rank
brightness against a shifting ambient level. Six segments carry about two and a
half bits of position, so what a pattern encodes is direction and rhythm;
brightness is spent within a frame, on giving movement a head and a tail.

The ring runs up the strip and an arriving notification runs down. Direction is
the only thing read reliably off six segments, so it is what tells a call from a
message without either having to be learnt. A priority arrival gets the same
shape brighter rather than a shape of its own.

## Permissions

`MEDIA_CONTENT_CONTROL` and `MODIFY_AUDIO_SETTINGS` come from the platform
signature. `RECORD_AUDIO`, `READ_PHONE_STATE` and `READ_CONTACTS` are runtime
permissions the signature does not grant, so they are pre-granted through
`default-permissions-org.lineageos.glyph.xml`.

The contacts provider is credential-encrypted, so no favourite can be looked up
before the user has unlocked once. The clock app is not direct-boot aware
either, so its resources are asked for until they answer rather than once at
construction, which would fail on every boot and leave the countdown inert.

## Dead ends

**Loudness as the height of the column, and a spectrum across the six
segments.** The spectrum read as undifferentiated: six brightnesses cannot be
ranked by eye, and music's energy sits so low that the bass segments pinned
while the rest stayed dark. Loudness was no better, for a reason no retuning
reaches — music is mastered to a near-constant level, so a mean over a short
window does not move while a track plays, and the bar parks at whatever that
master's loudness happens to be. Bass energy with a fast attack and a slow
release is what moves with the music, because the beat is the part that varies.

**Driving the ring off the ringtone's own audio.** A ringtone is mastered flat
and mixed loud, so the envelope the meter reads sits pinned with nothing to vary
against. A call is one event rather than a quantity.

**`USAGE_MEDIA` as the test for music.** A video autoplaying in a feed, a game,
a voice note and a settings preview all carry it, and a paused player holds its
stream open for seconds afterwards.

**Blinking the whole strip from the audio.** Touch feedback, the lock and unlock
sounds and the charging chime all carry `USAGE_ASSISTANCE_SONIFICATION`, and
notification tones carry `USAGE_NOTIFICATION`; nothing in the attributes
separates a deliberate tap from unlocking the phone. Alerts are driven from the
notification instead, which is also what makes a silent alert show at all.

**A declared notification listener approved by
`config_defaultListenerAccessPackages`.** That configuration seeds the approved
list only when a profile is created, so a declared listener is inert on any
device that was not wiped — it binds on a clean flash and never again.

**`ParanoidGlyphPhone4a`.** It does not exist upstream, and building a target
for it would have meant a Glyph app with its own settings for something the
kernel already exposes as three sysfs files.

**Driving it from SystemUI.** It already watches notifications and app-ops, so
it needs no new package, but it means carrying a device patch against
`frameworks/base` indefinitely.

**A native daemon.** Recording and music are both reachable from native audio
APIs, but notifications are not, and the timer is only published in one.

**A self-timer countdown the subject can see.** The strip faces whoever is being
photographed, so this is the one idea the hardware is obviously shaped for, and
nothing publishes it. Aperture's `CountDownView` posts `SET_TIMER_TEXT` to its
own handler and draws; no sound, no vibration, no broadcast leaves the app, and
the timer never reaches the camera framework. It would take a patch to a camera
app, which would then cover only that camera app, in a repository this device
tree does not own.

**Tap to peek.** `org.lineageos.sensor.single_tap` is a Focaltech touch-panel
gesture — `fts_gesture_single_tap_pressed` on the SPI node — not an
accelerometer heuristic, so it only sees taps on the screen. Face-down, the
screen is against the table and the sensor cannot be reached. The proximity
sensor faces the same wrong way, and SystemUI gates the tap on it anyway. Every
gesture sensor on this device points at the face that is hidden whenever the
strip is visible.
