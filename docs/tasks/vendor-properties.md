# Evaluate the remaining vendor property groups

`vendor.prop` carries 217 properties against stock's 499. The gap and stock's
values are listed in [data/vendor-props-missing.txt](../data/vendor-props-missing.txt).

Treat that file as a lead list, never a patch.

## Find the reader first

```sh
grep -rl "<prop>" --include=*.c --include=*.cpp --include=*.h --include=*.rc \
    hardware/ vendor/nothing/ device/ frameworks/
strings -a <shipped blob> | grep <prop>
```

Every group examined so far has been inert: the consumers are Nothing's
proprietary framework and HAL components this tree does not ship, or legacy QCOM
HALs for other SoCs it does not build. Copying such a property achieves nothing
except the appearance of configuration.

| Group | Count | Consumer |
|---|---|---|
| audio | 81 | legacy `audio_extn` HAL; this tree builds the PAL/AGM stack |
| display | 28 | `nt-services.jar`, not shipped |
| glyph | 20 | Nothing's Glyph framework, not shipped |
| `ro.vendor.nothing.*` | 65 | unexamined; likely `nt-services.jar` |
| sys, sf, newAAC, qcom, product, bt | 24 | unexamined |

Split any adoption into one commit per subsystem, so a regression bisects to a
group rather than to a single large change.

## Values that differ and are deliberate

| Property | Reason to keep |
|---|---|
| `vendor.display.enable_rounded_corner=1` | Read by the display HAL (`include/display_properties.h`). Stock pairs `0` with `enable_ic_hw_roundedcorner`, which this HAL does not read, so matching stock removes rounded corners with no hardware fallback |
| `ro.vendor.build.version.sdk=36` | Vendor is legitimately built against an older API under Treble |
| `ro.product.first_api_level=35` | See [clear bring-up switches](release-switches.md) |

`ro.bionic.cpu_variant` and `dalvik.vm.isa.arm64.variant` are `cortex-a76` where
stock uses `kryo300`. These affect code generation and are worth checking against
the actual core family.
