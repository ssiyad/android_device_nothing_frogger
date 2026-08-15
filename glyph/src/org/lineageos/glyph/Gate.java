/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The condition anything merely worth seeing waits on: the phone lying on its
 * face with the screen off.
 *
 * The vendor screen_upward sensor is on-change and wakes the machine, so this
 * costs nothing while the phone sits still and works with the screen off. It
 * only says that the up-down state changed; a single accelerometer sample says
 * which way, which avoids depending on a polarity nothing in the tree
 * documents. That sample also has to be flat, not merely tilted past the
 * horizontal, which is what keeps a phone carried in a pocket from lighting the
 * strip.
 *
 * Posture on its own has not proved reliable enough to gate on: the strip has
 * been seen running while the phone was in use. The screen is the second
 * opinion, and it is a cheap one, because a phone whose screen is on is a phone
 * whose strip is against something or facing away — either way nobody is
 * looking at it. Both have to agree.
 *
 * Face-down is published separately from the gate, because putting the phone
 * down is a moment rather than a state and the charge column belongs to that
 * moment, before the screen has had time to time out.
 */
final class Gate implements SensorEventListener {
    private static final String TAG = "Glyph";

    private static final String SCREEN_UPWARD = "android.sensor.screen_upward";
    private static final String POCKET_MODE = "android.sensor.pocket_mode";

    /** Gravity along z, in m/s². Near -9.8 is flat on its face. */
    private static final float FACE_DOWN = -7f;

    /**
     * Readings to take before deciding. A phone is still being put down when
     * the flip is reported, so the first sample catches it mid-air and reads as
     * whatever it happened to be passing through.
     */
    private static final int SETTLE_SAMPLES = 8;

    /** Told whenever the gate opens or closes. */
    interface Listener {
        void onGateChanged(boolean on);
    }

    /**
     * Told whenever the phone settles the other way up, gate or no gate. The
     * first reading is the state the sensor was already in at registration
     * rather than a flip, and is flagged so nothing reacts to a phone that has
     * been lying there since before the process started.
     */
    interface FaceDownListener {
        void onFaceDown(boolean faceDown, boolean initial);
    }

    private final Context mContext;
    private final SensorManager mSensorManager;
    private final List<Listener> mListeners = new ArrayList<>();
    private final List<FaceDownListener> mFaceDownListeners = new ArrayList<>();

    private Sensor mScreenUpward;
    private Sensor mPocketMode;
    private Sensor mAccelerometer;
    private boolean mFaceDown;
    private boolean mInteractive;
    private boolean mOn;
    private boolean mKnown;
    private int mSamples;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mInteractive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            update();
        }
    };

    Gate(Context context) {
        mContext = context;
        mSensorManager = context.getSystemService(SensorManager.class);

        for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (SCREEN_UPWARD.equals(sensor.getStringType())) {
                mScreenUpward = sensor;
            } else if (POCKET_MODE.equals(sensor.getStringType())) {
                mPocketMode = sensor;
            }
        }
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    void addListener(Listener listener) {
        mListeners.add(listener);
    }

    void addFaceDownListener(FaceDownListener listener) {
        mFaceDownListeners.add(listener);
    }

    boolean isOn() {
        return mOn;
    }

    void register() {
        mInteractive = mContext.getSystemService(PowerManager.class).isInteractive();

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        if (mScreenUpward == null || mAccelerometer == null) {
            // Nothing left to gate with, so let whatever waits on this run
            // rather than leaving it switched off for good.
            Log.w(TAG, "No screen_upward sensor, the posture half of the gate is inert");
            mFaceDown = true;
            update();
            return;
        }
        mSensorManager.registerListener(this, mScreenUpward, SensorManager.SENSOR_DELAY_NORMAL);

        // Watched only to record what it reports. Which value means in-pocket
        // is undocumented, and guessing it wrong would invert the gate rather
        // than weaken it: the strip would speak only in a pocket.
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
            mSamples = 0;
            mSensorManager.registerListener(this, mAccelerometer,
                    SensorManager.SENSOR_DELAY_UI);
            return;
        }

        if (event.sensor != mAccelerometer) {
            return;
        }
        if (++mSamples < SETTLE_SAMPLES) {
            return;
        }
        mSensorManager.unregisterListener(this, mAccelerometer);

        final boolean faceDown = event.values[2] < FACE_DOWN;
        if (faceDown != mFaceDown) {
            mFaceDown = faceDown;
            for (FaceDownListener listener : mFaceDownListeners) {
                listener.onFaceDown(faceDown, !mKnown);
            }
            update();
        }
        mKnown = true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void update() {
        final boolean on = mFaceDown && !mInteractive;
        if (on == mOn) {
            return;
        }
        mOn = on;
        Log.i(TAG, "gate " + (on ? "open" : "closed"));
        for (Listener listener : mListeners) {
            listener.onGateChanged(on);
        }
    }
}
