# The biometric prompt's indicator runs off the bottom

The indicator line — "Touch the fingerprint sensor" and the error messages —
draws past the bottom edge of the display. Most visible in GPay, which shows the
prompt on every payment.

Measured from a `uiautomator` dump taken while the prompt was up. It works
despite `FLAG_SECURE`, which blocks screenshots but not accessibility, and is
the only way to see this prompt's geometry:

| view | y | comes from |
|---|---|---|
| `panel` | 1728..2720 | |
| `biometric_icon` | 2382..**2588** | UDFPS sensor 2485, radius 103 |
| `button_bar` | **2480**..2648 | anchored to `bottomGuideline`, 2720 − 72 |
| `indicator` | **2660..2720** | overflows |

`biometric_prompt_one_pane_layout.xml` constrains the indicator between the two:

```xml
app:layout_constraintTop_toBottomOf="@+id/biometric_icon"
app:layout_constraintBottom_toTopOf="@+id/button_bar"
app:layout_constraintVertical_bias="0.0"
```

The top anchor is at 2588 and the bottom anchor at 2480, so the range is
inverted and there is nothing to centre within. `vertical_bias="0.0"` resolves
that by packing to the top anchor: 2588 plus the 24dp margin is 2660, and 60px
of text then runs to the edge.

## Not the navigation bar inset

`button_bar` already honours the inset correctly — its bottom is exactly
2720 − 72. The collision is between the icon and the button bar and was
identical when the inset was zero, which is why fixing that inset changed
nothing here. Do not re-open it on the assumption that it did.

## No device-tree value fixes it

The sensor is at `ro.vendor.fingerprint.sensor_location=612|2485|103` in
`vendor.prop`. For the indicator to land above the guideline the icon's bottom
must be at most 2516, so the radius would have to drop to 31px, about 10dp. That
is not a real sensor, and the radius is not a layout knob: enrolment and the
lock screen icon read the same value.

The sensor sits at 91% of display height, and the layout assumes a sensor high
enough that the button bar clears it. That assumption belongs to SystemUI.

**Reading the property needs root.** It is declared `scope: Internal` in
`hardware/nothing/fingerprint/fingerprint.sysprop`, so the `shell` domain is
denied the read and `getprop` prints an empty line rather than failing. That
reads exactly like an unset property. Check `/vendor/build.prop` instead, or
`dumpsys activity service com.android.systemui | grep udfpsLocation`, which
prints the location SystemUI resolved.

## If it is ever worth fixing

SystemUI ships no `overlayable.xml`, so `FroggerSystemUIOverlay` can override
the layout outright — no fork, unlike the font in
[lockscreen-clock-font.md](lockscreen-clock-font.md). The cost is carrying 262
lines of AOSP layout referencing 34 dimens, styles and ids that must stay in
step across updates, and a drifted copy breaks the prompt rather than degrading
it. Weighed against a clipped indicator line while authentication itself works,
that trade has not been worth taking.
