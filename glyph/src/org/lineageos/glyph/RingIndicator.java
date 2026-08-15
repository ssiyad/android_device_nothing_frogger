/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Runs a comet up the strip while a call is coming in.
 *
 * The meter used to do this off the ringtone's own audio, and it barely moved:
 * a ringtone is mastered flat and mixed loud, so the bass envelope the meter
 * reads sits pinned near the top with nothing to vary against. A call is not a
 * quantity anyway. It is one event, and what a pattern says about it — that it
 * is a call, and that it is still ringing — is the whole of what there is to
 * say.
 *
 * Two sources are watched because neither covers a call on its own. The audio
 * mode catches anything that asks for ringing focus, which is how VoIP apps
 * announce themselves, but the phone on silent may never ask. Telephony's call
 * state catches that, and says nothing about VoIP. Either one ringing is
 * enough.
 *
 * None of this waits on the gate. A call is worth showing whichever way the
 * phone happens to be lying.
 */
final class RingIndicator {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 130;

    /** Segments lit behind the head, each at half the one in front. */
    private static final int TAIL = 2;

    private static final long STEP_MS = 70;

    private static final long REGISTER_RETRY_MS = 2000;

    private final int[][] mFrames = Pattern.sweep(BRIGHTNESS, TAIL, true /* up */);
    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudioManager;
    private final Pattern mPattern;

    private boolean mModeRinging;
    private boolean mCallRinging;

    private final AudioManager.OnModeChangedListener mModeListener = mode -> {
        mModeRinging = mode == AudioManager.MODE_RINGTONE;
        update();
    };

    private final TelephonyCallback mCallListener = new CallState();

    private final class CallState extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            mCallRinging = state == TelephonyManager.CALL_STATE_RINGING;
            update();
        }
    }

    RingIndicator(Context context, Handler handler, AudioManager audioManager) {
        mContext = context;
        mHandler = handler;
        mAudioManager = audioManager;
        mPattern = new Pattern(handler, Panel.OWNER_RINGING);
    }

    void register() {
        mAudioManager.addOnModeChangedListener(mHandler::post, mModeListener);
        registerCallState();
    }

    /**
     * Telephony's registry is dereferenced with no null check on the way in, so
     * asking before system_server has published it throws rather than failing.
     * This process is persistent and starts early enough to lose that race on
     * every boot, so it keeps asking — and an exception let out of here kills a
     * persistent process, which takes every other indicator with it.
     *
     * A refusal is the one answer worth accepting. READ_PHONE_STATE is a
     * runtime permission the platform signature does not grant, and it is not
     * going to arrive by being asked again; what it costs is the silent ring
     * rather than the whole indicator.
     */
    private void registerCallState() {
        try {
            mContext.getSystemService(TelephonyManager.class)
                    .registerTelephonyCallback(mHandler::post, mCallListener);
        } catch (SecurityException e) {
            Log.w(TAG, "No call state, a silent ring will not show", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Telephony not up yet, retrying", e);
            mHandler.postDelayed(this::registerCallState, REGISTER_RETRY_MS);
        }
    }

    private void update() {
        final boolean ringing = mModeRinging || mCallRinging;
        if (ringing == mPattern.isRunning()) {
            return;
        }
        if (ringing) {
            mPattern.play(mFrames, STEP_MS, true /* loop */);
        } else {
            mPattern.stop();
        }
    }
}
