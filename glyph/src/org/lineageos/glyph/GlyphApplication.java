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
    private SoundFlash mSoundFlash;
    private NotificationIndicator mNotificationIndicator;

    @Override
    public void onCreate() {
        super.onCreate();

        final Handler handler = new Handler(Looper.getMainLooper());
        final AudioManager audioManager = getSystemService(AudioManager.class);

        mRecordingIndicator = new RecordingIndicator(handler);
        audioManager.registerAudioRecordingCallback(mRecordingIndicator, handler);

        mMusicVisualizer = new MusicVisualizer();
        audioManager.registerAudioPlaybackCallback(mMusicVisualizer, handler);

        mSoundFlash = new SoundFlash(handler);
        audioManager.registerAudioPlaybackCallback(mSoundFlash, handler);

        mNotificationIndicator = new NotificationIndicator(this);
        mNotificationIndicator.register();
    }
}
