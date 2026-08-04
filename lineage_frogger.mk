#
# SPDX-FileCopyrightText: 2025 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Bring-up: sets ro.adb.secure=0, so adb needs no on-device authorisation
# dialog -- which cannot be tapped on a device that will not boot. Also leaves
# ro.debuggable=1 by skipping PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG.
#
# This MUST be set before the vendor/lineage inherit below, which reads it
# (vendor/lineage/config/common.mk). Setting it in BoardConfig.mk does nothing:
# board config is evaluated after product config.
# REMOVE BEFORE RELEASE.
WITH_ADB_INSECURE := true

$(call inherit-product, device/nothing/frogger/device.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_BRAND := Nothing
PRODUCT_DEVICE := frogger
PRODUCT_MANUFACTURER := Nothing
PRODUCT_MODEL := A069
PRODUCT_NAME := lineage_frogger

PRODUCT_GMS_CLIENTID_BASE := android-nothing

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="qssi_64-user 16 BQ2A.250913.001-BP2A.250605.031.A3 2606301839 release-keys" \
    BuildFingerprint=Nothing/Frogger/Frogger:14/UKQ1.250915.001/2606301839:user/release-keys \
    DeviceName=Frogger \
    DeviceProduct=Frogger \
    SystemDevice=Frogger \
    SystemName=Frogger
