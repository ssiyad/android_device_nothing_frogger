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
        if (!ultraHigh) {
            fail("camera " + cameraId + " does not advertise ULTRA_HIGH_RESOLUTION_SENSOR");
            return;
        }

        final StreamConfigurationMap map = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
        if (map == null) {
            fail("no SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION");
            return;
        }

        final Size[] sizes = map.getOutputSizes(format);
        if (sizes == null || sizes.length == 0) {
            fail("no maximum resolution sizes for format " + format);
            return;
        }

        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        Log.i(TAG, "maximum resolution sizes=" + sizes.length + " chosen=" + best
                + " format=" + (raw ? "RAW_SENSOR" : "JPEG"));

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

    private void configure(CameraDevice device) throws CameraAccessException {
        final CaptureRequest.Builder params = device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
        params.set(CaptureRequest.SENSOR_PIXEL_MODE,
                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);

        final List<OutputConfiguration> outputs =
                Collections.singletonList(new OutputConfiguration(mReader.getSurface()));

        final SessionConfiguration config = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, Runnable::run,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        Log.i(TAG, "session configured -- HAL ACCEPTED the full-size stream");
                        try {
                            capture(session);
                        } catch (Throwable t) {
                            fail("capture threw: " + t);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        fail("HAL REJECTED the full-size stream at configure_streams");
                    }
                });
        config.setSessionParameters(params.build());
        device.createCaptureSession(config);
    }

    private void capture(CameraCaptureSession session) throws CameraAccessException {
        final CaptureRequest.Builder request = mCamera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
        request.set(CaptureRequest.SENSOR_PIXEL_MODE,
                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
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
