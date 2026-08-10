# Build configuration

Values in `BoardConfig.mk` and `lineage_frogger.mk` that read like bring-up
leftovers but are load-bearing, and the reasoning that keeps them from being
tidied away.

## API levels

`BOARD_API_LEVEL_PROP_OVERRIDE := 34` reads as a hack because the build warns it
is test-only, but 34 is what stock reports for `ro.board.api_level` and
`ro.board.first_api_level`. The vendor partition was built at API 34 while the
system is Android 16. Removing it lets the build derive 36 and misdescribe the
vendor image, and it is why the vendor and odm fingerprints legitimately read
`:14/`.

`PRODUCT_SHIPPING_API_LEVEL` stays at **35**. It is a compliance switch
selecting which launch requirements the build must meet, not a property. At 36
it enables the 16 KB page-size check, which rejects `adpl` and `ATFWD-daemon` on
a `CONFIG_ARM64_4K_PAGES` kernel, and makes `host_init_verifier` fatal on stock
init scripts that declare no user, such as `vendor.nicmd`. Stock reporting 36 is
not a reason to claim it.

## SELinux

The runtime mode is bootconfig, and a permissive build is one that *adds*
`androidboot.selinux=permissive` to `BOARD_BOOTCONFIG`. Enforcing is the absence
of the line, so there is nothing to grep for when checking which mode a tree is
in — check `/proc/bootconfig` on the device instead.

`SELINUX_IGNORE_NEVERALLOWS := false` is independent of the runtime mode. It
makes neverallow violations fail the build, which is where they belong: a
violation means the policy is wrong rather than incomplete.

## If `WITH_ADB_INSECURE` is ever needed again

It also leaves `ro.debuggable=1`, by skipping
`PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG`.

It belongs in `lineage_frogger.mk` above the `vendor/lineage` inherit; nowhere
else works, because board config is evaluated after product config.

Do **not** set `ro.adb.secure=0` directly — `vendor/lineage/config/common.mk`
already assigns it and Soong fails on duplicate sysprop assignments.
