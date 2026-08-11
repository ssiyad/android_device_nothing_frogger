/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;

import java.util.List;

/**
 * Flashes red once for a camera action: a shutter click, or a video recording
 * starting or stopping.
 *
 * Camera sounds are the only ordinary ones that carry enforced audibility,
 * which is what separates them from every other short sonification, and the
 * flag survives the redaction applied to unprivileged listeners. A capture with
 * the shutter sound switched off makes no sound and so raises nothing here.
 */
final class CaptureIndicator extends AudioManager.AudioPlaybackCallback {
    private static final int BRIGHTNESS = 180;
    private static final long FLASH_MS = 120;

    private final Handler mHandler;

    private int mSounding;

    private final Runnable mEnd = () -> Panel.get().releaseRed(Panel.RED_CAPTURE);

    CaptureIndicator(Handler handler) {
        mHandler = handler;
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        int sounding = 0;
        for (AudioPlaybackConfiguration config : configs) {
            final AudioAttributes attributes = config.getAudioAttributes();
            if (attributes.getUsage() == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    && (attributes.getFlags() & AudioAttributes.FLAG_AUDIBILITY_ENFORCED) != 0) {
                sounding++;
            }
        }

        if (sounding > mSounding) {
            mHandler.removeCallbacks(mEnd);
            Panel.get().setRed(Panel.RED_CAPTURE, BRIGHTNESS);
            mHandler.postDelayed(mEnd, FLASH_MS);
        }
        mSounding = sounding;
    }
}
