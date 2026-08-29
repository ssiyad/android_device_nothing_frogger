# Re-enable DeviceExtras

`DeviceExtras` is commented out of `PRODUCT_PACKAGES` in `device.mk`, along with
the two `sepolicy/vendor/device_extras.te` rules.

## Why it is disabled

`treble_sepolicy_tests_202404` fails:

```
The following public types were found added to the policy without an entry into
the compatibility mapping file(s) ... device_extras
```

`hardware/nothing/sepolicy/DeviceExtras/public/device_extras.te` declares
`type device_extras, domain;`. A **public** type needs an entry under
`new_objects` in `system/sepolicy/private/compat/<ver>/<ver>.ignore.cil`, and
there is no device-side hook to supply one — `BOARD_PLAT_PUBLIC_SEPOLICY_DIR`
adds public policy, not mappings.

This is a build-time API-compatibility check, unrelated to the runtime SELinux
mode.

## Why step one cannot stand alone

Moving `device_extras` to `private/` removes the compat-mapping failure and, in
the same move, removes the only way to grant the access.

`vendor_proc_power_supply` is declared in
`hardware/nothing/sepolicy/common/vendor/file.te` — a **vendor** type. Vendor
policy may reference plat *public* types, and plat policy may not reference
vendor types at all. So the type is public precisely because that is what lets
`sepolicy/vendor/device_extras.te` grant it a vendor-labelled file. Make it
private and neither side can express the rule.

Routing through a HAL is therefore forced rather than optional.

Confirmed while checking: there is no device hook for the compat mapping. The
`ignore.cil` files are enumerated by hand in `system/sepolicy/compat/Android.bp`,
so the only way in is a fork of `system/sepolicy`, which stays rejected.

## What the app actually needs

One path, `/proc/charger/nt_otg_enable`, for one feature — an OTG toggle with a
quick-settings tile. That is the whole of it:
`grep -rhoE '"/(sys|proc|dev)/…"'` over the package returns that single string.

Two vendor domains already hold the access it would need:

| Domain | Rule |
|---|---|
| `hal_lineage_health_default` | `rw_dir_file(…, vendor_proc_power_supply)` |
| `hal_nt_charger` | `rw_dir_file(…, vendor_proc_power_supply)` |

So a HAL host exists. What does not exist is an interface: Lineage Health's AIDL
is charging control and has nowhere to put an OTG switch, and `hal_nt_charger`
would need an interface declared, a VINTF entry, and `binder_call` from a
system_ext app to a vendor HAL.

## The honest trade

That is an app change, a HAL interface, and a VINTF entry, to restore a single
toggle that nothing depends on. Worth doing only if DeviceExtras grows a second
feature, or if the OTG toggle is wanted specifically.

The third option nobody has costed is relabelling `/proc/charger/nt_otg_enable`
to a plat public type, which would let plat-private policy grant a private
`device_extras` directly. It avoids the HAL entirely, but it moves a vendor node
onto the platform's label namespace and `hal_nt_charger` writes the same node, so
its own rule would have to move with it.

## Cost of leaving it disabled

A device-settings app. Nothing depends on it; Lineage Health charging control is
a separate HAL (`vendor.lineage.health-service.default` plus the `lineage_health`
soong config).
