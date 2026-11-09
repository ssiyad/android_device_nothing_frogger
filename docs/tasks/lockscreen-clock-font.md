# The lock screen clock falls back to Roboto

**Fixed upstream.** LineageOS commit `9e5784b` ("fonts: Use GoogleSansFlex for
clock font") registers `google-sans-flex-clock` in
`packages/overlays/Lineage/fonts/etc/fonts_customization.xml`, as a rounded
GoogleSansFlex (`ROND` 100). Nothing in this tree is needed.

## What the bug was

The large clock asks for a font family by a hardcoded name.
`DefaultClockProvider` builds its typeface like this:

```kotlin
val FLEX_TYPEFACE by lazy {
    // TODO(b/364680873): Move constant to config_clockFontFamily when shipping
    Typeface.create("google-sans-flex-clock", Typeface.NORMAL)
}
```

Before `9e5784b`, no family had that name. `Typeface.create()` returns the
default face for an unknown family and logs nothing, so the clock used Roboto.

## Do not add the family again

The workaround was a KernelSU module whose `post-fs-data` script added
`google-sans-flex-clock` to a copy of `/product/etc/fonts_customization.xml`
and bind-mounted the copy over the original. Now that upstream registers the
family too, the name occurs twice, and zygote crashes before the first boot
completes:

```text
Typeface.<clinit> → SystemFonts.getSystemPreinstalledFontConfigFromLegacyXml
  → FontCustomizationParser.validateAndTransformToResult
java.lang.IllegalArgumentException: new-named-family requires unique name attribute
```

Anything that changes `fonts_customization.xml` must replace a family, not
add one with a name that already exists. The file must also keep the
`system_file` label. With any other label, the font manager drops every
customised family, not only the clock.
