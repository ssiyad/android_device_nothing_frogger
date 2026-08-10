/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

/**
 * Shows the charge level as a column for a few seconds when the phone is set
 * down on its face, which is the moment the strip becomes the only thing worth
 * looking at.
 *
 * The vendor screen_upward sensor is on-change and wakes the machine, so this
 * costs nothing while the phone sits still and works with the screen off. It
 * only says that the up-down state changed; a single accelerometer sample says
 * which way, which avoids depending on a polarity nothing in the tree
 * documents.
 *
 * That sample also has to be flat, not merely tilted past the horizontal, which
 * is what keeps a phone carried in a pocket from lighting the strip.
 */
final class FaceDownStatus implements SensorEventListener {
    private static final String TAG = "Glyph";

    private static final String SCREEN_UPWARD = "android.sensor.screen_upward";
    private static final String POCKET_MODE = "android.sensor.pocket_mode";

    private static final int BRIGHTNESS = 140;
    private static final long DURATION_MS = 3000;

    /** Gravity along z, in m/s². Near -9.8 is flat on its face. */
    private static final float FACE_DOWN = -7f;

    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final BatteryManager mBatteryManager;
    private final PowerManager.WakeLock mWakeLock;
    private final int[] mLevels = new int[Panel.SEGMENTS];

    private Sensor mScreenUpward;
    private Sensor mPocketMode;
    private Sensor mAccelerometer;
    private boolean mFaceDown;
    private boolean mKnown;

    private final Runnable mHide = new Runnable() {
        @Override
        public void run() {
            Panel.get().releaseWhite(Panel.OWNER_STATUS);
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    };

    FaceDownStatus(Context context, Handler handler) {
        mHandler = handler;
        mSensorManager = context.getSystemService(SensorManager.class);
        mBatteryManager = context.getSystemService(BatteryManager.class);
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);

        for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (SCREEN_UPWARD.equals(sensor.getStringType())) {
                mScreenUpward = sensor;
            } else if (POCKET_MODE.equals(sensor.getStringType())) {
                mPocketMode = sensor;
            }
        }
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    void register() {
        if (mScreenUpward == null || mAccelerometer == null) {
            Log.w(TAG, "No screen_upward sensor, the face-down status is inert");
            return;
        }
        mSensorManager.registerListener(this, mScreenUpward, SensorManager.SENSOR_DELAY_NORMAL);

        // Watched only to record what it reports. Which value means in-pocket
        // is undocumented, and guessing it wrong would invert the gate rather
        // than weaken it: the status would appear only in a pocket.
        if (mPocketMode != null) {
            mSensorManager.registerListener(this, mPocketMode, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == mPocketMode) {
            Log.i(TAG, "pocket_mode " + event.values[0]);
            return;
        }

        if (event.sensor == mScreenUpward) {
            Log.i(TAG, "screen_upward " + event.values[0]);
            // An on-change sensor also reports its current value on
            // registration, so the first reading settles the state below
            // rather than counting as a flip.
            mSensorManager.registerListener(this, mAccelerometer,
                    SensorManager.SENSOR_DELAY_UI);
            return;
        }

        if (event.sensor != mAccelerometer) {
            return;
        }
        mSensorManager.unregisterListener(this, mAccelerometer);

        final boolean faceDown = event.values[2] < FACE_DOWN;
        if (faceDown != mFaceDown) {
            mFaceDown = faceDown;
            if (faceDown && mKnown) {
                show();
            }
        }
        mKnown = true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void show() {
        final int capacity = mBatteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (capacity < 0) {
            return;
        }

        // The strip holds its own pattern across suspend, but the release does
        // not, so the machine has to stay up for as long as this is showing.
        mWakeLock.acquire(DURATION_MS * 2);

        final double height = Panel.SEGMENTS * capacity / 100.0;
        for (int i = 0; i < Panel.SEGMENTS; i++) {
            final double fill = height - (Panel.SEGMENTS - 1 - i);
            mLevels[i] = fill >= 1 ? BRIGHTNESS : fill <= 0 ? 0 : (int) (BRIGHTNESS * fill);
        }
        Panel.get().setWhite(Panel.OWNER_STATUS, mLevels);

        mHandler.removeCallbacks(mHide);
        mHandler.postDelayed(mHide, DURATION_MS);
    }
}
