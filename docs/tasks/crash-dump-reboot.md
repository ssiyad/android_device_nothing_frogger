# Catch the reboot to the crash dump screen

The device reboots at random into the crash dump screen XBL puts up ahead of the
bootloader. No trigger is known, so the first job is not to debug it but to make
sure the next one leaves something to read.

**The obvious way out of that screen destroys the evidence.** pstore lives in
RAM at `0x81f20000` and survives a warm reboot but not a power-hold, which is a
PMIC-level reset — so holding power to escape the crash dump screen erases the
backtrace that explains it. Leave the screen with a warm reboot into LineageOS
recovery, which has a root shell where stock recovery has only sideload, and run
`tools/grab-logs.sh` from there.

| Entry | Holds |
|---|---|
| `dmesg-ramoops-*` | the panic backtrace — the one that matters |
| `console-ramoops-*` | the previous boot's console, for what led up to it |
| `pmsg-ramoops-*` | the previous boot's userspace ring buffer |

`nt_log:boot_log/` is written by Android userspace, so it stops at the point the
crash began rather than describing it.

Nothing in this tree sets `persist.vendor.ssr.enable_ramdumps` or
`persist.vendor.sys.rawdump_copy`, so the `init.qcom.rc` triggers that start
`vendor.ss_ramdump` and arm the rawdump copy never fire. Both are worth setting
by hand before the next occurrence, but neither replaces pstore: they cover
subsystem restarts — ADSP, modem — and the crash dump screen is the applications
processor going down.
