/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialkey;

/**
 * The only surface the two halves of this app share: the settings UI runs in its
 * own process and writes these, the key handler runs inside system_server and
 * reads them.
 *
 * Actions are stored as tokens rather than as the ordinals Lineage's own key
 * actions use. An ordinal list has to keep its order forever and carries a
 * validator that has to be widened every time it grows; a token can be read out
 * of `settings get` and means the same thing next year.
 */
public final class Constants {
    private Constants() {
    }

    public static final String TAG = "EssentialKey";

    /** android.provider.Settings.System keys, one per gesture. */
    public static final String KEY_SINGLE_PRESS = "essential_key_single_press";
    public static final String KEY_DOUBLE_PRESS = "essential_key_double_press";
    public static final String KEY_LONG_PRESS = "essential_key_long_press";

    public static final String ACTION_NONE = "none";
    public static final String ACTION_SCREENSHOT = "screenshot";
    public static final String ACTION_CAMERA = "camera";
    public static final String ACTION_FLASHLIGHT = "flashlight";
    public static final String ACTION_PLAY_PAUSE = "play_pause";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_PREVIOUS = "previous";
    public static final String ACTION_DND = "dnd";
    public static final String ACTION_RINGER = "ringer";
    public static final String ACTION_ROTATION = "rotation";

    /** Followed by a flattened ComponentName. */
    public static final String ACTION_APP_PREFIX = "app:";

    public static final String DEFAULT_SINGLE_PRESS = ACTION_SCREENSHOT;
    public static final String DEFAULT_DOUBLE_PRESS = ACTION_NONE;
    public static final String DEFAULT_LONG_PRESS = ACTION_NONE;

    public static String defaultFor(String key) {
        switch (key) {
            case KEY_SINGLE_PRESS:
                return DEFAULT_SINGLE_PRESS;
            case KEY_DOUBLE_PRESS:
                return DEFAULT_DOUBLE_PRESS;
            default:
                return DEFAULT_LONG_PRESS;
        }
    }
}
