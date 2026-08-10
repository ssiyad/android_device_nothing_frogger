#!/system/bin/sh
# Frogger SELinux denial collector -- see /data/adb/avc/collect.sh
#
# Runs from post-fs-data.d, not service.d: 00-avc-policy.sh loads a policy with
# no Magisk types, init then refuses to start a service whose seclabel does not
# resolve, and Magisk's late_start service mode never runs. Ordered after the
# policy script so the collection covers the policy it is meant to measure.
# The collector waits for sys.boot_completed itself before touching logcat.
/data/adb/avc/collect.sh &
