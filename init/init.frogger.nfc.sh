#!/vendor/bin/sh

sku=$(getprop ro.boot.hardware.sku)

# Frogger only ever ships an ST54L, so unlike Asteroids there is no ST21/ST54
# hardware probe and no Base/Pro (pbid) split -- Frogger has neither a pbid nor
# the 1-0008 hw_version node. The only variation is the FeliCa-enabled
# configuration used on the Japanese SKU.
#
# NOTE: this selection is derived from the configuration files stock ships, not
# observed on a running device -- the reference unit is an IND, which has no NFC
# hardware populated. Verify on an EEA/JPN/ROW/TUR unit. See docs/open-items.md.
case "$sku" in
    "JPN")
        setprop vendor.nfc.config_file_name "libnfc-hal-st-st54l-felica.conf"
        ;;
    *)
        setprop vendor.nfc.config_file_name "libnfc-hal-st.conf"
        ;;
esac

setprop vendor.nfc_model "ST54L"
