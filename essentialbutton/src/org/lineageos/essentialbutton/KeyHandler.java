/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialbutton;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

/**
 * The Essential Button, as seen from inside system_server.
 *
 * PhoneWindowManager loads this class out of the apk named beside it in
 * config_deviceKeyHandlerLibs and calls it from
 * {@code interceptKeyBeforeQueueing}, which runs on the input dispatcher's
 * thread: anything slow here stalls every key and touch on the device. So this
 * class does nothing but recognise the keycode, take a wakelock and hand two
 * primitives to a thread of its own.
 *
 * Returning null consumes the event. Nothing downstream sees the key at all,
 * which is the point -- there is no fallback behaviour to fall back to, so a
 * failure to load this class leaves the key completely dead. That is why the
 * constructor logs unconditionally: the line is the only evidence that the
 * resource override naming this apk actually took effect, and the apk path is
 * written out by hand in two files that no build step checks against each other.
 */
public class KeyHandler implements DeviceKeyHandler {
    /**
     * Long enough to cover the long-press timer and the multi-press window that
     * may follow it. Timed rather than released by hand: a wakelock leaked
     * inside system_server is a flat battery, and losing one costs a missed
     * press.
     */
    private static final long WAKELOCK_MS = 1000;

    private final HandlerThread mThread;
    private final Handler mHandler;
    private final PowerManager.WakeLock mWakeLock;
    private final PressDetector mDetector;

    public KeyHandler(Context context) {
        mThread = new HandlerThread(Constants.TAG, Process.THREAD_PRIORITY_DISPLAY);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG + ":gesture");

        mDetector = new PressDetector(mHandler, new ActionExecutor(context, mHandler));

        Log.i(Constants.TAG, "KeyHandler loaded");
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_MACRO_1) {
            return event;
        }

        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        // The event is recycled once this returns, so nothing may outlive the
        // call but primitives.
        mWakeLock.acquire(WAKELOCK_MS);
        mHandler.post(() -> mDetector.onKey(down));

        return null;
    }
}
