# Frogger (Nothing Phone 4a) LineageOS Bring-up Journal

## Goals

-   Primary: Bring up a bootable `device/nothing/frogger` tree for
    LineageOS 23.2.
-   Secondary: Learn Android internals only when they directly help the
    bring-up.
-   Defer deep dives until after first successful bring-up.

## Environment

-   Arch Linux host (16 GB RAM)
-   Distrobox container
-   LineageOS 23.2 source at `~/source/android/lineage`
-   Repo tools kept inside container.

## Facts Established

-   Device codename: `frogger`
-   Marketing name: Nothing Phone (4a)
-   Android 16
-   Qualcomm Volcano family
-   Kernel: `android_kernel_msm-6.1_nothing_sm7635`
-   Boot header version: 4
-   `boot.img` contains kernel only (empty ramdisk)
-   `init_boot.img` contains first-stage ramdisk
-   Vendor SKU: volcano
-   Hardware SKU: IND
-   Build product: qssi_64

## Concepts Learned

-   GKI overview
-   Dynamic partitions (high level)
-   Boot/init/vendor_boot roles
-   LZ4-compressed CPIO ramdisks
-   Why Android uses CPIO
-   Static `/init`

## Strategy

-   Use `android_device_nothing_asteroids` as scaffolding.
-   Copy boilerplate.
-   Derive hardware-specific values from Frogger.
-   Never blindly copy BoardConfig, blobs, SELinux, partitions or kernel
    settings.

## Planned Tree

    device/nothing/frogger/
    ├── Android.bp
    ├── AndroidProducts.mk
    ├── BoardConfig.mk
    ├── device.mk
    ├── lineage_frogger.mk
    ├── overlay/
    ├── rootdir/
    ├── sepolicy/
    ├── proprietary-files.txt
    ├── extract-files.py
    └── setup-makefiles.py

## Next Steps

1.  Review Asteroids tree structure.
2.  Copy boilerplate.
3.  Create AndroidProducts.mk and lineage_frogger.mk.
4.  Derive BoardConfig.mk.
5.  Extract blobs.
6.  First build.
7.  Boot and debug.
