/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.frogger.maxrestest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Captures one frame at the sensor's maximum resolution, to establish whether the HAL accepts a
 * full-size stream configuration.
 *
 * The camera HAL computes the full-resolution stream configurations and then publishes only a
 * subset to Android; a framework patch reinstates the rest under
 * SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION. Nothing else on the device asks for those
 * modes -- CameraX declines to select them and the GCam build present does not use the API -- so
 * this exists to ask for one directly.
 *
 *   adb shell pm grant org.lineageos.frogger.maxrestest android.permission.CAMERA
 *   adb shell am start -n org.lineageos.frogger.maxrestest/.MaxResTestActivity --es camera 4
 *   adb logcat -d -s MaxResTest
 *
 * Extras: "camera" (id, default 4), "format" ("jpeg" default, or "raw").
 */
public class MaxResTestActivity extends Activity {
    private static final String TAG = "MaxResTest";
    private static final long TIMEOUT_MS = 20000;

    private HandlerThread mThread;
    private Handler mHandler;
    private CameraDevice mCamera;
    private ImageReader mReader;
    private boolean mFinished;
    private boolean mRemosaic;
    private boolean mMaxRes;
    private String[] mNtFeatures = new String[0];

    /**
     * Nothing's processing lives in CHI nodes that ship in this ROM -- rawhdr, tfesupernight,
     * darkvision, portrait, ldc, frt and the rest -- and each has a vendor tag that looks like its
     * switch. The tags are settable by any application; whether setting one makes CHI select a
     * graph that contains the node is the open question.
     */
    private static final String NT_PREFIX = "com.nothing.camera.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mThread = new HandlerThread("MaxResTest");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        final Intent intent = getIntent();
        final String cameraId = intent.getStringExtra("camera") != null
                ? intent.getStringExtra("camera") : "4";
        final boolean raw = "raw".equalsIgnoreCase(intent.getStringExtra("format"));
        final String remosaic = intent.getStringExtra("remosaic");
        mRemosaic = remosaic == null || !("0".equals(remosaic) || "false".equalsIgnoreCase(remosaic));
        Log.i(TAG, "remosaic vendor tag " + (mRemosaic ? "enabled" : "disabled"));

        // "size": max (default) uses the maximum resolution map; normal uses the ordinary one, so
        // the Nothing feature tags can be tested without the full-size rejection getting in first.
        mMaxRes = !"normal".equalsIgnoreCase(intent.getStringExtra("size"));
        final String nt = intent.getStringExtra("nt");
        if (nt != null && !nt.isEmpty()) {
            mNtFeatures = nt.split(",");
        }
        Log.i(TAG, "size=" + (mMaxRes ? "maximum" : "normal")
                + " ntFeatures=" + java.util.Arrays.toString(mNtFeatures));
        final int format = raw ? ImageFormat.RAW_SENSOR : ImageFormat.JPEG;

        mHandler.postDelayed(() -> fail("timed out after " + TIMEOUT_MS + " ms"), TIMEOUT_MS);

        try {
            run(cameraId, format, raw);
        } catch (Throwable t) {
            fail("exception: " + t);
            Log.e(TAG, "stack", t);
        }
    }

    private void run(String cameraId, int format, boolean raw) throws CameraAccessException {
        final CameraManager manager = getSystemService(CameraManager.class);
        final CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);

        final int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean ultraHigh = false;
        if (caps != null) {
            for (int cap : caps) {
                if (cap == CameraMetadata
                        .REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) {
                    ultraHigh = true;
                }
            }
        }
        Log.i(TAG, "camera " + cameraId + " ULTRA_HIGH_RESOLUTION_SENSOR=" + ultraHigh);
        if (mMaxRes && !ultraHigh) {
            fail("camera " + cameraId + " does not advertise ULTRA_HIGH_RESOLUTION_SENSOR");
            return;
        }

        final StreamConfigurationMap map = chars.get(mMaxRes
                ? CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                : CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            fail("no stream configuration map");
            return;
        }

        // Full-size configurations run below 20 fps, and StreamConfigurationMap classifies
        // anything that slow as "high resolution" and serves it from a separate accessor. The
        // sizes we are after are in that second list, not the first.
        final Size[] normal = map.getOutputSizes(format);
        final Size[] slow = map.getHighResolutionOutputSizes(format);
        Log.i(TAG, (mMaxRes ? "maximum resolution" : "default") + " map: getOutputSizes=" + (normal == null ? 0 : normal.length)
                + " getHighResolutionOutputSizes=" + (slow == null ? 0 : slow.length));

        Size best = null;
        for (Size[] group : new Size[][] {normal, slow}) {
            if (group == null) {
                continue;
            }
            for (Size s : group) {
                if (best == null || (long) s.getWidth() * s.getHeight()
                        > (long) best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        if (best == null) {
            fail("no maximum resolution sizes for format " + format);
            return;
        }
        Log.i(TAG, "chosen=" + best + " format=" + (raw ? "RAW_SENSOR" : "JPEG"));

        final Size size = best;
        mReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), format, 2);
        mReader.setOnImageAvailableListener(reader -> onImage(reader, cameraId, size, raw),
                mHandler);

        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                mCamera = device;
                Log.i(TAG, "camera opened");
                try {
                    configure(device);
                } catch (Throwable t) {
                    fail("configure threw: " + t);
                }
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                fail("camera disconnected");
            }

            @Override
            public void onError(CameraDevice device, int error) {
                fail("camera error " + error);
            }
        }, mHandler);
    }

    /**
     * CamX rejects a full-size stream in CheckValidStreamConfig against a limit of the binned
     * size, ignoring SENSOR_PIXEL_MODE -- it does not implement the Android maximum resolution
     * pixel mode. Nothing has its own switch for this, a vendor tag read by the CHI feature2
     * layer, and configure_streams is handed the session parameters, so setting it there is the
     * plausible way to raise that limit.
     */
    private static final CaptureRequest.Key<Integer> REMOSAIC_ENABLE =
            new CaptureRequest.Key<>("com.nothing.camera.remosaic.enable", Integer.class);

    /** Sets each requested Nothing feature tag, reporting which the framework accepted. */
    private void applyNtFeatures(CaptureRequest.Builder b, String where) {
        for (String feature : mNtFeatures) {
            final String name = feature.trim();
            if (name.isEmpty()) {
                continue;
            }
            // "night" is a mode, the rest are enables; both are int32.
            final String tag = NT_PREFIX + name + ("night".equals(name) ? ".mode" : ".enable");
            try {
                b.set(new CaptureRequest.Key<>(tag, Integer.class), 1);
                Log.i(TAG, "set " + tag + "=1 (" + where + ")");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "tag not settable: " + tag + " -- " + e);
            }
        }
    }

    private void configure(CameraDevice device) throws CameraAccessException {
        final CaptureRequest.Builder params = device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
        if (mMaxRes) {
            // Only valid alongside a maximum-resolution stream; the framework rejects the
            // request outright if it disagrees with how the streams were configured.
            params.set(CaptureRequest.SENSOR_PIXEL_MODE,
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
        if (mRemosaic && mMaxRes) {
            try {
                params.set(REMOSAIC_ENABLE, 1);
                Log.i(TAG, "set com.nothing.camera.remosaic.enable=1 in session parameters");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "vendor tag com.nothing.camera.remosaic.enable not settable: " + e);
            }
        }
        applyNtFeatures(params, "session parameters");

        final List<OutputConfiguration> outputs =
                Collections.singletonList(new OutputConfiguration(mReader.getSurface()));

        final SessionConfiguration config = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, Runnable::run,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        Log.i(TAG, "session configured -- HAL ACCEPTED the stream");
                        try {
                            capture(session);
                        } catch (Throwable t) {
                            fail("capture threw: " + t);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        fail("HAL REJECTED the stream at configure_streams");
                    }
                });
        config.setSessionParameters(params.build());
        device.createCaptureSession(config);
    }

    private void capture(CameraCaptureSession session) throws CameraAccessException {
        final CaptureRequest.Builder request = mCamera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
        if (mMaxRes) {
            request.set(CaptureRequest.SENSOR_PIXEL_MODE,
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
        if (mRemosaic && mMaxRes) {
            try {
                request.set(REMOSAIC_ENABLE, 1);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "vendor tag not settable on request: " + e);
            }
        }
        applyNtFeatures(request, "request");
        request.addTarget(mReader.getSurface());

        session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                    TotalCaptureResult result) {
                Log.i(TAG, "capture completed");
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                    CaptureFailure failure) {
                fail("capture failed, reason " + failure.getReason());
            }
        }, mHandler);
    }

    private void onImage(ImageReader reader, String cameraId, Size size, boolean raw) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) {
                fail("null image");
                return;
            }
            final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            final File out = new File(getExternalFilesDir(null),
                    "maxres_cam" + cameraId + "_" + size.getWidth() + "x" + size.getHeight()
                            + (raw ? ".raw" : ".jpg"));
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(bytes);
            }
            Log.i(TAG, "RESULT PASS wrote " + out.getAbsolutePath() + " (" + bytes.length
                    + " bytes)");
        } catch (Throwable t) {
            fail("writing image: " + t);
            return;
        }
        cleanup();
    }

    private synchronized void fail(String why) {
        if (mFinished) {
            return;
        }
        Log.e(TAG, "RESULT FAIL " + why);
        cleanup();
    }

    private synchronized void cleanup() {
        if (mFinished) {
            return;
        }
        mFinished = true;
        if (mCamera != null) {
            mCamera.close();
            mCamera = null;
        }
        if (mReader != null) {
            mReader.close();
            mReader = null;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mThread != null) {
            mThread.quitSafely();
        }
    }
}
