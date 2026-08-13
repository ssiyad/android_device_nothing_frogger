# System bars and insets

Where the status bar, the shade header and the bottom inset get their geometry.
Each of these looks like a device-tree misconfiguration and only one is.

## The status bar is as tall as the punch hole

`config_mainBuiltInDisplayCutout` reaches y=162, so the StatusBar window is laid
out `fillx162` and provides a `statusBars` inset of 162 whatever
`status_bar_height` says. That split matters: everything reading the inset is
right, and everything reading the dimen is wrong by the difference.

`ShadeHeaderController` centres its clock in `status_bar_height`, so a dimen
smaller than the cutout puts the shade's clock above the real status bar's while
both look individually plausible. Keep the dimen equal to the cutout height, and
keep `status_bar_padding_top` at 0 — padding there moves the status bar without
moving the shade header, which is how the two came to disagree in the first
place. With both correct, each centres at y=81, which is also the hole's centre.

## The two bars cannot be aligned exactly

The shade header draws a larger clock and larger status icons than the status
bar. Their horizontal padding is separate:

| | Padding from |
|---|---|
| Status bar | `status_bar_padding_start`, applied by `PhoneStatusBarView` |
| Shade header | `qs_panel_padding`, applied by `ShadeHeaderController` and `combined_qs_header` |

Because the content differs in size, equal padding does not put the ink in the
same place: at 16dp the header ran 12px wider on the left and 24px on the right.
One symmetric value cannot close both gaps, so `qs_panel_padding` splits the
difference. Raising it is safe — nothing else reads it except a bottom margin in
`data_usage.xml`, and QS tile margins come from elsewhere.

## The bottom inset follows the gesture hint

**This is a setting, not a build property.** `navigation_bar_hint` in
`LineageSettings.System` decides whether apps get a bottom inset at all:

```java
// TaskbarStashController, phone mode
mStashedHeight = SettingsCache...getValue(NAVIGATION_BAR_HINT_URI)
        ? getDimensionPixelSize(R.dimen.taskbar_stashed_size)
        : 0;
```

That height is what `getContentHeightToReportToApps()` returns on a phone in
gesture navigation, and it becomes the `navigationBars` inset. Hidden hint means
a zero inset, and anything padding itself by that inset draws to the physical
edge — WhatsApp's compose bar, and the biometric prompt, whose bottom guideline
is set to that inset and nothing else. With the hint on the inset is 72px.

Read it with the provider, not the `settings` command, which returns null
because the key does not live in the AOSP tables:

```sh
content query --uri content://lineagesettings/system --where "name=\"navigation_bar_hint\""
```

Two things follow. A bottom sheet that looks correct proves nothing — the share
sheet pads itself and keeps its 88px whatever the inset is. And there is no
device-tree fix, because the coupling is Launcher3's: the only way to have no
hint and a correct inset is to change Launcher3.
