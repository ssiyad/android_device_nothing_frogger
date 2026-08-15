/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.Notification;
import android.app.Person;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.List;

/**
 * Whether a notification is about someone the user has starred.
 *
 * The caller is looked for in several places because no one of them is
 * dependable. A Person carries a URI that is either a contact or a number and
 * is the right answer when it is there; the legacy people array carries the
 * same thing as bare strings.
 *
 * com.android.dialer publishes neither. Its missed-call notification titles
 * itself "Missed call" and puts the caller in the text, as the contact's
 * display name if it knows one and the bare number if it does not — so the text
 * is tried both ways, and the title only after it, for the sake of a dialer
 * that puts the caller where it belongs.
 *
 * Matching a display name is the weakest leg: two contacts can share one. It
 * fails on the safe side though — this phone holds a starred "Home" and an
 * unstarred "Home 2", and an exact match keeps them apart.
 *
 * The contacts provider is credential-encrypted, so none of this can be
 * answered before the user has unlocked once. That is a failure to look up
 * rather than an error.
 */
final class Favourites {
    private static final String TAG = "Glyph";

    private static final String TEL = "tel:";

    private final ContentResolver mResolver;

    Favourites(Context context) {
        mResolver = context.getContentResolver();
    }

    boolean isStarred(Notification notification) {
        final List<Person> people = notification.extras.getParcelableArrayList(
                Notification.EXTRA_PEOPLE_LIST, Person.class);
        if (people != null) {
            for (Person person : people) {
                if (person != null && isStarredUri(person.getUri())) {
                    return true;
                }
            }
        }

        final String[] legacy = notification.extras.getStringArray(Notification.EXTRA_PEOPLE);
        if (legacy != null) {
            for (String uri : legacy) {
                if (isStarredUri(uri)) {
                    return true;
                }
            }
        }

        return isStarredCaller(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                || isStarredCaller(
                        notification.extras.getCharSequence(Notification.EXTRA_TITLE));
    }

    /** A caller written out as text, which is a name or a number and says which by failing. */
    private boolean isStarredCaller(CharSequence caller) {
        if (caller == null) {
            return false;
        }
        final String text = caller.toString();
        return isStarredNumber(text) || isStarredName(text);
    }

    private boolean isStarredUri(String uri) {
        if (uri == null) {
            return false;
        }
        if (uri.startsWith(TEL)) {
            return isStarredNumber(Uri.decode(uri.substring(TEL.length())));
        }
        if (uri.startsWith(ContactsContract.Contacts.CONTENT_URI.toString())) {
            return starred(Uri.parse(uri), ContactsContract.Contacts.STARRED, null, null);
        }
        return false;
    }

    private boolean isStarredNumber(String number) {
        return starred(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(number)),
                ContactsContract.PhoneLookup.STARRED, null, null);
    }

    private boolean isStarredName(String name) {
        return starred(ContactsContract.Contacts.CONTENT_URI,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " = ?",
                new String[] { name });
    }

    private boolean starred(Uri uri, String column, String selection, String[] args) {
        try (Cursor cursor = mResolver.query(uri, new String[] { column },
                selection, args, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (cursor.getInt(0) != 0) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the contacts", e);
        }
        return false;
    }
}
