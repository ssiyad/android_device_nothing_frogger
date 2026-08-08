# Clear bring-up switches before release

| Switch | Location |
|---|---|
| `androidboot.selinux=permissive` | `BoardConfig.mk`, `BOARD_BOOTCONFIG` |

Removing the permissive bootconfig is [switch SELinux to
enforcing](selinux-enforcing.md).

## Keep

`BOARD_API_LEVEL_PROP_OVERRIDE := 34` reads as a bring-up hack because the build
warns it is test-only, but 34 is what stock reports for `ro.board.api_level` and
`ro.board.first_api_level`. The vendor partition was built at API 34 while the
system is Android 16. Removing it lets the build derive 36 and misdescribe the
vendor image, and it is why the vendor and odm fingerprints legitimately read
`:14/`.

`PRODUCT_SHIPPING_API_LEVEL` stays at **35**. It is a compliance switch selecting
which launch requirements the build must meet, not a property. At 36 it enables
the 16 KB page-size check, which rejects `adpl` and `ATFWD-daemon` on a
`CONFIG_ARM64_4K_PAGES` kernel, and makes `host_init_verifier` fatal on stock
init scripts that declare no user, such as `vendor.nicmd`. Stock reporting 36 is
not a reason to claim it.

## If `WITH_ADB_INSECURE` is ever needed again

It also leaves `ro.debuggable=1`, by skipping
`PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG`.

It belongs in `lineage_frogger.mk` above the `vendor/lineage` inherit; nowhere
else works, because board config is evaluated after product config.

Do **not** set `ro.adb.secure=0` directly — `vendor/lineage/config/common.mk`
already assigns it and Soong fails on duplicate sysprop assignments.
