/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;

import java.util.List;

/**
 * Blinks the red indicator while any app records with the camcorder audio
 * source. Video recorded with audio muted does not register.
 */
final class RecordingIndicator extends AudioManager.AudioRecordingCallback {
    private static final int BRIGHTNESS = 110;
    private static final long BLINK_MS = 700;

    private final Handler mHandler;

    private boolean mRecording;
    private boolean mLit;

    private final Runnable mBlink = new Runnable() {
        @Override
        public void run() {
            mLit = !mLit;
            Panel.get().setRed(Panel.RED_RECORDING, mLit ? BRIGHTNESS : 0);
            mHandler.postDelayed(this, BLINK_MS);
        }
    };

    RecordingIndicator(Handler handler) {
        mHandler = handler;
    }

    @Override
    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        boolean recording = false;
        for (AudioRecordingConfiguration config : configs) {
            if (config.getClientAudioSource() == MediaRecorder.AudioSource.CAMCORDER) {
                recording = true;
                break;
            }
        }

        if (recording == mRecording) {
            return;
        }
        mRecording = recording;

        if (recording) {
            mLit = false;
            mHandler.post(mBlink);
        } else {
            mHandler.removeCallbacks(mBlink);
            Panel.get().releaseRed(Panel.RED_RECORDING);
        }
    }
}
