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
    private RecordingIndicator mRecordingIndicator;
    private MusicVisualizer mMusicVisualizer;
    private NotificationIndicator mNotificationIndicator;
    private FaceDownStatus mFaceDownStatus;

    @Override
    public void onCreate() {
        super.onCreate();

        final Handler handler = new Handler(Looper.getMainLooper());
        final AudioManager audioManager = getSystemService(AudioManager.class);

        mRecordingIndicator = new RecordingIndicator(handler);
        audioManager.registerAudioRecordingCallback(mRecordingIndicator, handler);

        mMusicVisualizer = new MusicVisualizer();
        audioManager.registerAudioPlaybackCallback(mMusicVisualizer, handler);

        mNotificationIndicator = new NotificationIndicator(this);
        mNotificationIndicator.register();

        mFaceDownStatus = new FaceDownStatus(this, handler);
        mFaceDownStatus.register();
    }
}
