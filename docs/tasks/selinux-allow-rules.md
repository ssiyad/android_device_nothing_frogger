# Write missing allow rules

Denials whose target type is already correct need a rule in
`sepolicy/vendor/<domain>.te`. Which denials qualify, and which are meant to
stay denied, is in [selinux.md](../reference/selinux.md).

## Outstanding

| Domain | Target | Question |
|---|---|---|
| `mediametrics` | `same_process_hal_file` | is it real? |
| `vendor_qtelephony` | `default_android_service` | nothing publishes the service |

**`mediametrics`** reads, maps and executes `/vendor/lib64/libutils.so` and
`libbase.so`. `adbroot` did the same thing in the same second, which is what an
`adb root` session plus a `dumpsys` looks like, and a coredomain loading the
vendor copy of libutils is a loader bug rather than a policy gap — it would give
the process two libutils. AOSP gates this deliberately:
`system/sepolicy/private/domain.te` grants `same_process_hal_file:file` to every
domain **except** coredomain, "access is explicitly granted to individual
coredomains". Re-observe it on a build with no adb attached before writing
anything, and note that the rule would belong in `sepolicy/private/`, not
`sepolicy/vendor/`.

**`vendor_qtelephony`** looks up `nothing.radio.ntphone` and lands on
`default_android_service` because no `service_contexts` entry names it. Stock
has no entry either, and `service list` on the device does not show it: the
Nothing telephony app that would publish it does not ship here. A label for a
service that never appears buys nothing, so this waits for something to
register.
