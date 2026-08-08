# Resolve the duplicate `battery` thermal zone

Two thermal zones are both named `battery`. No `qcom/*.dtsi` defines one of them,
so one comes from the devicetree and the other is registered by a driver.

`thermal-engine.conf` matches zones by name, so which one it binds to is not
deterministic.

## Investigation

```sh
for z in /sys/class/thermal/thermal_zone*; do
    echo "$z $(cat $z/type) $(cat $z/temp)"
done | grep battery
```

Identify the registering driver for the zone that has no devicetree node, then
either rename it or drop the devicetree zone.

## Related constraint

`frogger-base-overlay.dts` is `/plugin/`, a true dtbo overlay, and **an overlay
cannot delete a node from the base DTB**. `/delete-node/` entries against
`volcano.dtb` are silent no-ops, so a base zone survives alongside any
replacement declared under a different name, and both poll the same ADC channel.

Redefining a label inside a replacement zone is worse than the duplication
itself: cooling-maps resolve trips by phandle, and a stolen label points a zone's
map at trips belonging to a different zone, which fails every bind with `-ENXIO`.
