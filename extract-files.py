#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'hardware/qcom-caf/common/libqti-perfd-client',
    'hardware/qcom-caf/sm8650',
    'hardware/qcom-caf/wlan',
    'vendor/qcom/opensource/commonsys/display',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/qcom/opensource/dataservices',
]

def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'vendor' else None

lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    (
        'com.qualcomm.qti.dpm.api@1.0',
        'libntf',
        'vendor.qti.ImsRtpService-V1-ndk',
        'vendor.qti.diaghal@1.0',
        'vendor.qti.hardware.dpmaidlservice-V1-ndk',
        'vendor.qti.hardware.qccsyshal@1.0',
        'vendor.qti.hardware.qccsyshal@1.1',
        'vendor.qti.hardware.qccsyshal@1.2',
        'vendor.qti.hardware.wifidisplaysession@1.0',
        'vendor.qti.imsrtpservice@3.0',
        'vendor.qti.imsrtpservice@3.1',
        'vendor.qti.qccvndhal_aidl-V1-ndk',
    ): lib_fixup_vendor_suffix,
    (
        'libar-pal',
        'libar-acdb',
        'liblx-osal',
        'libats',
        'libagm',
        'libpalclient',
    ): lib_fixup_remove,
}

blob_fixups: blob_fixups_user_type = {
    'system_ext/lib64/libwfdnative.so': blob_fixup()
        .add_needed('libinput_shim.so'),
    'system_ext/lib64/vendor.qti.hardware.qccsyshal@1.2-halimpl.so': blob_fixup()
        .replace_needed('libprotobuf-cpp-full.so','libprotobuf-cpp-full-21.7.so'), 
    'vendor/bin/qcc-vendor': blob_fixup()
        .add_needed('libbinder_shim.so'),
    'vendor/bin/qms': blob_fixup()
        .add_needed('libbinder_shim.so'),
    'vendor/bin/xtra-daemon': blob_fixup()
        .add_needed('libbinder_shim.so'),
    'vendor/etc/media_codecs_volcano_v0.xml': blob_fixup()
        .regex_replace(r'(?s)(<MediaCodecs.*?>)',r'\1\n    <Include href="media_codecs_dolby_audio.xml" />'),
    'vendor/lib64/libarcsoft_dark_vision_raw.so': blob_fixup()
        .clear_symbol_version('remote_register_buf')
        .clear_symbol_version('rpcmem_alloc')
        .clear_symbol_version('rpcmem_free'),
    'vendor/lib64/libcne.so': blob_fixup()
        .add_needed('libbinder_shim.so'),
    # libmorpho_video_stabilizer pulls libui, and Soong then sees two versions of
    # the same aidl_interface in one module and refuses to build:
    #
    #   module "com.morpho.node.eisv2": depends on multiple versions of the same
    #     aidl_interface
    #       via libcommonchiutils          -> graphics.allocator-V1-ndk
    #       via libmorpho_video_stabilizer -> libui -> graphics.allocator-V2-ndk
    #
    # This is why the Morpho nodes were deferred in c7b6801, on the mistaken
    # reasoning that they were video-stabilisation only. They are not: the log
    # shows com.morpho.node.gme failing and taking
    # MultiCameraBayerSATNoBPSFrogger0_0_cam_2 -- a preview pipeline -- with it.
    #
    # Dropping libui from this one blob breaks the V2 edge. blob_fixups is keyed
    # by path, so unlike lib_fixups it does not affect every blob linking libui.
    # Safe at runtime because libui is already mapped in the camera provider
    # process (verified on device), loaded by something other than the camera
    # libraries, so the symbols still resolve at dlopen.
    #
    # The alternative -- dropping allocator-V1-ndk from libcommonchiutils --
    # would touch a library the working camera path already depends on.
    # libarcsoft_triple_sat.old.so is a copy of the primary library under a
    # different filename, and carries DT_SONAME "libarcsoft_triple_sat.so".
    # Soong rejects that:
    #
    #   error: DT_SONAME "libarcsoft_triple_sat.so" must be equal to the file
    #   name "libarcsoft_triple_sat.old.so"
    #
    # ChiNode3SAT dlopens the primary by full path and only falls back to .old,
    # so this file should never be reached now that the primary ships. Kept for
    # parity with stock rather than dropped, with the soname corrected.
    #
    # Note this also changes linker behaviour if the fallback is ever taken:
    # with the stock soname the linker would return the already-loaded primary,
    # whereas now it would map a second copy.
    'vendor/lib64/libarcsoft_triple_sat.old.so': blob_fixup()
        .fix_soname(),
    'vendor/lib64/libmorpho_video_stabilizer.so': blob_fixup()
        .remove_needed('libui.so'),
    'vendor/lib64/libmorpho_RapidEffect.so': blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock'),
    'vendor/lib64/libntcamallocator.so': blob_fixup()
        .add_needed('libui_shim.so'),
    'vendor/lib64/libntcamskia.so': blob_fixup()
        .add_needed('libnativewindow.so'),
    'vendor/lib64/vendor.libdpmframework.so': blob_fixup()
        .add_needed('libbinder_shim.so')
        .add_needed('libhidlbase_shim.so'),
    'vendor/lib64/libqcc_sdk.so': blob_fixup()
        .add_needed('libbinder_shim.so'),
    'vendor/lib64/libqcodec2_core.so': blob_fixup()
        .add_needed('libcodec2_shim.so'),
    (
        'vendor/lib64/libcapiv2uvvendor.so',
        'vendor/lib64/liblistensoundmodel2vendor.so',
        'vendor/lib64/libVoiceSdk.so',
    ): blob_fixup().replace_needed(
        'libtensorflowlite_c.so',
        'libtensorflowlite_c_vendor.so',
    ),
    (
		'vendor/bin/poweropt-service',
		'vendor/lib64/libapengine.so',
		'vendor/lib64/libgamepoweroptfeature.so',
		'vendor/lib64/liblearningmodule.so',
		'vendor/lib64/liboffscreenpoweroptfeature.so',
		'vendor/lib64/libpowercore.so',
		'vendor/lib64/libvideooptfeature.so',
        'vendor/lib64/libdpps.so',
        'vendor/lib64/libsnapdragoncolor-manager.so',
    ): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    'vendor/etc/init/vendor.qti.media.c2@1.0-service.rc': blob_fixup()
        .regex_replace(r'writepid\s+/dev/cpuset/foreground/tasks', 'task_profiles ProcessCapacityHigh HighPerformance'),
}  # fmt: skip

module = ExtractUtilsModule(
    'frogger',
    'nothing',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
