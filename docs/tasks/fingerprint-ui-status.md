# Remove the `ui_status` writes from the fingerprint HAL

`fingerprint/Session.cpp` writes `/sys/panel_feature/ui_status` in four places.
That node does not exist. The kernel's attribute list is:

```c
static struct attribute *panel_feature_attributes[] = {
    &panel_id1_attribute.attr, &panel_id2_attribute.attr,
    &panel_id3_attribute.attr, &fp_status_attribute.attr,
    &brightnessid_attribute.attr, NULL,
};
```

All four writes fail silently — `WriteStringToFile` returns a `bool` that nothing
checks.

The working FOD illumination path is `fp_status`.

## Change

Drop the four writes, or point them at `fp_status` if they were meant to do
something.

## Related

`brightnessid` and `panel_id*` are readable and may bear on the display
brightness curve. See [reference/display.md](../reference/display.md).
