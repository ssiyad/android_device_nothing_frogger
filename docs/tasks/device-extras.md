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

## Approach

Change it in `hardware/nothing`:

1. Move `device_extras` from `DeviceExtras/public/` to `DeviceExtras/private/`,
   removing it from the platform-vendor API surface so no compat mapping is
   needed.
2. Route vendor-file access through a HAL rather than granting it to a
   system_ext app. `hal_lineage_health_default` already holds
   `rw_dir_file(hal_lineage_health_default, vendor_proc_power_supply)`.
3. Drop `sepolicy/vendor/device_extras.te` entirely, since vendor policy cannot
   reference a private type.

Deleting the vendor rules alone does not work: the type is public because of
where it is declared, not because it is referenced.

## Rejected

Forking `system/sepolicy` to add the type to `new_objects`. AOSP's error message
suggests it, but it means carrying a patch against a large AOSP repo
indefinitely to paper over a type that should not be public.

## Cost of leaving it disabled

A device-settings app. Nothing depends on it; Lineage Health charging control is
a separate HAL (`vendor.lineage.health-service.default` plus the `lineage_health`
soong config).
