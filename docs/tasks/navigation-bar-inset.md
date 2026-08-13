# The navigationBars inset is empty

Anything that pads itself by the `navigationBars` inset draws to the physical
bottom edge. The biometric prompt is the clearest case: `BiometricViewSizeBinder`
sets its bottom guideline to that inset and nothing else, so the prompt's text
ends up level with the edge of the glass.

```kotlin
val bottomInset = windowManager.maximumWindowMetrics.windowInsets
    .getInsets(WindowInsets.Type.navigationBars()).bottom
nextConstraintSet.setGuidelineEnd(R.id.bottomGuideline, bottomInset)
```

## Why this is not the device tree's

There is no `NavigationBar0` window. Launcher3's Taskbar owns the navigation bar
and publishes the source with an empty frame, its surface parked at the bottom of
the display:

```
InsetsSource id=26250001 type=navigationBars frame=[0,0][0,0] visible=true
InsetsFrameProvider: {..., insetsSize=Insets{left=0, top=0, right=0, bottom=0}}
mSurfacePosition=Point(0, 2720)
```

Three things say the value is wrong rather than deliberate. AOSP's own
`NavigationBarModeGesturalOverlay` sets `navigation_bar_height` to 24dp, so the
mode does not intend zero. `mandatorySystemGestures` is a correct 96px, so it is
specifically this source that is empty rather than the bottom of the screen being
considered free. And no overlay here sets any navigation bar dimension, with
`navigation_mode=2` coming from the stock gestural RRO.

**The device tree also has no lever on it.** The taskbar derives the inset from
its own window frame, not from `navigation_bar_height`, so overriding that dimen
changes nothing. This belongs to `packages/apps/Launcher3` and `frameworks/base`,
both shared, and would affect every 23.2 device on gesture navigation.

## What is not affected

Bottom sheets that carry their own padding are fine, which is why this shows up
in some popovers and not others. The share sheet leaves 88px below its last row
of content because `ResolverActivity` pads itself rather than trusting the inset.
A sheet looking correct is therefore no evidence the inset is correct.

Worth confirming against another 23.2 device before reporting upstream: a phone
narrow enough that Launcher3 has no reason to show a taskbar at all is the case
that looks least intentional. This one is 408dp wide.
