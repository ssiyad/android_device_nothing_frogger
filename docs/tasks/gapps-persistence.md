# Make GApps survive a ROM flash

A payload OTA rewrites `system`, `product` and `system_ext` whole. `addon.d` is
the mechanism that puts add-on packages back.

`POSTINSTALL_PATH_system` points at `backuptool_postinstall.sh`, which is what
runs the `addon.d` scripts. MindTheGapps installs `/system/addon.d/30-gapps.sh`
with `ADDOND_VERSION=3`, a standard `list_files()` and no `/sdcard` paths.

## What actually happens

`addon.d` never gets as far as reading anything. Every sideload logs this, twice,
once per invocation in `backuptool_postinstall.sh`:

```
/postinstall/system/bin/backuptool_postinstall.sh[6]:
    /postinstall/system/bin/backuptool_ab.sh: Permission denied
```

So the postinstall hook runs and then fails to execute the script that does the
work. The failure is invisible because `POSTINSTALL_OPTIONAL_system=true` and
update_engine ignores a non-zero exit from an optional script.

It is not a file mode: `backuptool_ab.sh` ships `-rwxr-xr-x root:shell`, the same
as `backuptool_postinstall.sh`, which does run. So the difference is in how the
nested exec is treated — either `/postinstall` is mounted `noexec`, or SELinux
refuses the `postinstall` domain executing a second `postinstall_file`. Which of
the two has not been established; `dmesg | grep avc` during a sideload settles it.

The earlier theory, that `backuptool_ab.sh` exports `S=/system` and so finds
nothing while the incoming image is at `/postinstall`, is untested — the script
never starts, so its view of the filesystem has never mattered.

## Trade already accepted

`otapreopt_script` no longer runs after an OTA, so the first boot after an update
is slower. Official LineageOS A/B devices make the same trade.
