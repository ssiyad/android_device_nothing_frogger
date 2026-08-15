/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.os.Handler;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Holds something back until a system service has been published.
 *
 * A manager caches its binder the first time one is built, using a lookup that
 * returns null rather than one that waits, and the manager itself is then kept
 * per context and never built again. A manager built too early therefore holds
 * a null for the life of the process, and every call through it throws — so
 * retrying the call is futile, however patiently it is done. Only holding back
 * the first call works.
 *
 * This process is persistent and starts before system_server has published much
 * of anything, so it loses that race as a matter of course rather than
 * occasionally. `telephony.registry` has been seen to take over a minute.
 *
 * Asking ServiceManager directly is what makes this possible: it answers
 * whether the service is there without building the manager that would cache
 * the answer.
 */
final class ServiceWait {
    private static final String TAG = "Glyph";

    private static final long FIRST_RETRY_MS = 500;

    /**
     * The wait backs off rather than polling flat out. A service that is
     * refused rather than late never answers, and every ask is an SELinux
     * denial in the log — a steady poll wrote 251 of them in ten minutes and
     * rotated away everything worth reading, including the lines that would
     * have shown what was working.
     */
    private static final long MAX_RETRY_MS = 30000;

    /** Long enough for a slow boot, short enough not to poll for ever. */
    private static final long GIVE_UP_MS = 300000;

    private ServiceWait() {}

    static void whenPublished(Handler handler, String name, Runnable action) {
        poll(handler, name, action, SystemClock.uptimeMillis(), FIRST_RETRY_MS);
    }

    private static void poll(Handler handler, String name, Runnable action, long since,
            long delay) {
        if (ServiceManager.getService(name) != null) {
            action.run();
            return;
        }
        if (SystemClock.uptimeMillis() - since > GIVE_UP_MS) {
            Log.w(TAG, "Gave up waiting for " + name);
            return;
        }
        final long next = Math.min(delay * 2, MAX_RETRY_MS);
        handler.postDelayed(() -> poll(handler, name, action, since, next), delay);
    }
}
