/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;

import java.util.Arrays;
import java.util.List;

/**
 * Blinks the whole strip once for each short sound the system makes: touch
 * feedback, notification tones, and anything else sonified rather than played.
 *
 * Counting them is enough to spot a new one. Unprivileged callers are handed
 * only the configurations that are active, so a rise in the count is a sound
 * that was not playing a moment ago.
 */
final class SoundFlash extends AudioManager.AudioPlaybackCallback {
    private static final int BRIGHTNESS = 200;
    private static final long DURATION_MS = 90;

    private final Handler mHandler;
    private final int[] mLevels = new int[Panel.SEGMENTS];

    private int mSounding;

    private final Runnable mEnd = () -> Panel.get().releaseWhite(Panel.OWNER_FLASH);

    SoundFlash(Handler handler) {
        mHandler = handler;
        Arrays.fill(mLevels, BRIGHTNESS);
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        int sounding = 0;
        for (AudioPlaybackConfiguration config : configs) {
            if (isShortSound(config.getAudioAttributes().getUsage())) {
                sounding++;
            }
        }

        if (sounding > mSounding) {
            mHandler.removeCallbacks(mEnd);
            Panel.get().setWhite(Panel.OWNER_FLASH, mLevels);
            mHandler.postDelayed(mEnd, DURATION_MS);
        }
        mSounding = sounding;
    }

    private static boolean isShortSound(int usage) {
        return usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                || usage == AudioAttributes.USAGE_NOTIFICATION
                || usage == AudioAttributes.USAGE_NOTIFICATION_EVENT;
    }
}
