#
# SPDX-FileCopyrightText: 2025 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# WITH_ADB_INSECURE was set here during bring-up, for the window where the
# device bootlooped: ro.adb.secure=0 means adb needs no authorisation dialog,
# and a dialog cannot be tapped on a phone that never reaches the launcher.
# That is how the audio bootloop was diagnosed. It boots reliably now, so the
# unauthenticated-adb-to-any-USB-host trade is no longer worth making.
#
# If a future build bootloops and the console is needed again, set it here and
# nowhere else: it must precede the vendor/lineage inherit below, which reads
# it (vendor/lineage/config/common.mk). BoardConfig.mk does not work -- board
# config is evaluated after product config.

# Put the time of day in LINEAGE_BUILD_DATE, so the zip name and
# ro.lineage.version identify a single build rather than a day's worth of them.
# Two builds on the same date otherwise produce the same file name, and the
# packaging step writes the zip through whatever inode already holds that name.
# Like WITH_ADB_INSECURE above, this is read by vendor/lineage/config/common.mk
# and so must precede the inherit below.
LINEAGE_VERSION_APPEND_TIME_OF_DAY := true

$(call inherit-product, device/nothing/frogger/device.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_BRAND := Nothing
PRODUCT_DEVICE := frogger
PRODUCT_MANUFACTURER := Nothing
PRODUCT_MODEL := A069
PRODUCT_NAME := lineage_frogger

PRODUCT_GMS_CLIENTID_BASE := android-nothing

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="qssi_64-user 16 BQ2A.250913.001-BP2A.250605.031.A3 2603091830 release-keys" \
    BuildFingerprint=Nothing/Frogger/Frogger:16/BQ2A.250913.001-BP2A.250605.031.A3/2603091830:user/release-keys \
    DeviceName=Frogger \
    DeviceProduct=Frogger \
    SystemDevice=Frogger \
    SystemName=Frogger
