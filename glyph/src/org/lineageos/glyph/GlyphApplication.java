/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.Application;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/** The process is persistent, so registering here is enough to stay live. */
public final class GlyphApplication extends Application {
    private Gate mGate;
    private RecordingIndicator mRecordingIndicator;
    private MusicVisualizer mMusicVisualizer;
    private MusicSessions mMusicSessions;
    private CaptureIndicator mCaptureIndicator;
    private NotificationIndicator mNotificationIndicator;
    private ChargeStatus mChargeStatus;

    @Override
    public void onCreate() {
        super.onCreate();

        final Handler handler = new Handler(Looper.getMainLooper());
        final AudioManager audioManager = getSystemService(AudioManager.class);

        mGate = new Gate(this);

        mRecordingIndicator = new RecordingIndicator(handler);
        audioManager.registerAudioRecordingCallback(mRecordingIndicator, handler);

        mMusicVisualizer = new MusicVisualizer(audioManager, handler);
        audioManager.registerAudioPlaybackCallback(mMusicVisualizer, handler);
        mGate.addListener(mMusicVisualizer);

        mMusicSessions = new MusicSessions(this, handler, mMusicVisualizer::onMusicChanged);
        mMusicSessions.register();

        mCaptureIndicator = new CaptureIndicator(handler);
        audioManager.registerAudioPlaybackCallback(mCaptureIndicator, handler);

        mNotificationIndicator = new NotificationIndicator(this, mGate);
        mNotificationIndicator.register();
        mGate.addListener(mNotificationIndicator);

        mChargeStatus = new ChargeStatus(this, handler);
        mGate.addFaceDownListener(mChargeStatus);

        // Registered last, so nothing is told the state before it can act on it.
        mGate.register();
    }
}
