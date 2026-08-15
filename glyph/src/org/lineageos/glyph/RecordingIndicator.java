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
 * Blinks the red indicator while an app is holding the microphone open to
 * record: a video, a voice note, a dictation.
 *
 * The test is on the source rather than the app, because the source is what
 * says why the microphone is open. The exclusions are the sources that are open
 * for reasons of their own: the call sources, where a call already announces
 * itself and blinking through every one would teach the eye to ignore red, and
 * the hotword source, which is open whenever the assistant is listening for its
 * name — that is, always.
 *
 * This does not wait on the gate. It says the microphone is live, which is
 * worth knowing whichever way the phone is lying and worth more when it is
 * being held.
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
            if (isCapture(config.getClientAudioSource())) {
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

    private static boolean isCapture(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.HOTWORD:
            case MediaRecorder.AudioSource.VOICE_CALL:
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
            case MediaRecorder.AudioSource.VOICE_DOWNLINK:
            case MediaRecorder.AudioSource.VOICE_UPLINK:
            case MediaRecorder.AudioSource.REMOTE_SUBMIX:
                return false;
            default:
                return true;
        }
    }
}
