# The lock screen clock falls back to Roboto

The large clock asks for a font family nothing registers. `DefaultClockProvider`
builds its typeface from a hardcoded name:

```kotlin
val FLEX_TYPEFACE by lazy {
    // TODO(b/364680873): Move constant to config_clockFontFamily when shipping
    Typeface.create("google-sans-flex-clock", Typeface.NORMAL)
}
```

`packages/overlays/Lineage/fonts/etc/fonts_customization.xml` registers
thirty-eight families from `GoogleSansFlex-Regular.ttf` — `google-sans`,
`google-sans-flex`, `google-sans-flex-medium` and so on — but not that one.
`Typeface.create()` returns the default face for an unknown family instead of
failing, so the clock renders in Roboto and nothing logs a word about it.

Everything else is already in place, which is what makes it look like a missing
font rather than a missing name: `GoogleSansFlex-Regular.ttf` ships in
`/product/fonts`, and `org.lineageos.overlay.font.googlesansflex` is enabled and
targets `android`.

**GApps is not the answer, and looks like it should be.** MindTheGapps contains
no fonts at all — no `.ttf`, no `.otf`, no font config — and the
`fonts_customization.xml` on a device with GApps installed still carries the
LineageOS copyright header. The font is LineageOS', not Google's.

`config_clockFontFamily` is a red herring. It is `monospace` in our build,
because the blanking in `vendor/lineage/overlay/common` does not take effect,
but only the legacy `clock_default_small` and `clock_default_large` layouts read
it. The flex clock ignores it entirely.

## Why this has no cheap fix

`SystemFonts.OEM_XML` is a single hardcoded path, `/product/etc/fonts_customization.xml`.
There is no per-partition customisation file, so the fix means replacing a file
owned by `packages/overlays/Lineage`, which is upstream and not forked here.
Three mechanisms exist and each costs something:

| Mechanism | Cost |
|---|---|
| Fork the Lineage overlays repo | A sixth fork to track and rebase, for eight lines |
| Copy the file into this tree | 477 lines of upstream data that drifts silently; any family Lineage adds later is lost |
| Derive it at build time | A non-standard Make rule plus filtering `fonts_customization.xml` out of `PRODUCT_PACKAGES`, since `lineage_frogger.mk` is the only place that runs after the `vendor/lineage` inherit |

A Magisk `post-fs-data` script deriving the file from the live one avoids all
three, and is the obvious answer right up until Magisk goes away.

## The family, when it is wanted

Shaped after `variable-display-large`, the largest text Lineage defines from this
font. `opsz` is 57 because that is the highest optical size anything in the file
asks for; a larger value risks falling outside the axis range the font declares.
One weight is enough, because `AnimatableClockView` animates weight through the
`wght` axis rather than by selecting another registered font.

```xml
<family customizationType="new-named-family" name="google-sans-flex-clock" fallback="google-sans">
    <font style="normal" weight="400">GoogleSansFlex-Regular.ttf
        <axis tag="wght" stylevalue="400"/>
        <axis tag="wdth" stylevalue="100"/>
        <axis tag="GRAD" stylevalue="0"/>
        <axis tag="opsz" stylevalue="57.0"/>
    </font>
</family>
```

Whatever ships it must land at `/product/etc/fonts_customization.xml` labelled
`system_file`. Any other label and the font manager drops every customised
family, not just the clock.
