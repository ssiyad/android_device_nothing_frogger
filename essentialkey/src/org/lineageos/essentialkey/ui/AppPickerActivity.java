/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialkey.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import org.lineageos.essentialkey.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks one launchable activity.
 *
 * Enumerated through the package manager rather than through LauncherApps, which
 * gates most of its methods on the caller being the active launcher. Package
 * visibility filtering does not apply either: this shares android.uid.system, and
 * an appId below the first application uid is exempt, so QUERY_ALL_PACKAGES would
 * be dead weight.
 *
 * There is a search box because a phone holds well over a hundred of these and a
 * plain list is unusable at that length.
 */
public class AppPickerActivity extends FragmentActivity {
    public static final String EXTRA_COMPONENT = "component";

    private final List<Entry> mAll = new ArrayList<>();
    private final List<Entry> mShown = new ArrayList<>();

    private Adapter mAdapter;

    private static final class Entry {
        final ComponentName component;
        final CharSequence label;
        final Drawable icon;

        Entry(ComponentName component, CharSequence label, Drawable icon) {
            this.component = component;
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_picker);

        load();
        mShown.addAll(mAll);

        mAdapter = new Adapter();
        final ListView list = findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_COMPONENT,
                    mShown.get(position).component.flattenToShortString()));
            finish();
        });

        ((EditText) findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void load() {
        final PackageManager pm = getPackageManager();
        final Intent launchable = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);

        for (ResolveInfo info : pm.queryIntentActivities(launchable,
                PackageManager.ResolveInfoFlags.of(0))) {
            mAll.add(new Entry(
                    new ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    info.loadLabel(pm),
                    info.loadIcon(pm)));
        }

        final Collator collator = Collator.getInstance();
        mAll.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
    }

    private void filter(String query) {
        final String needle = query.toLowerCase(Locale.getDefault()).trim();
        mShown.clear();
        for (Entry entry : mAll) {
            if (needle.isEmpty()
                    || entry.label.toString().toLowerCase(Locale.getDefault()).contains(needle)) {
                mShown.add(entry);
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mShown.size();
        }

        @Override
        public Object getItem(int position) {
            return mShown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.app_picker_item, parent, false);

            final Entry entry = mShown.get(position);
            ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(entry.icon);
            ((TextView) view.findViewById(R.id.label)).setText(entry.label);
            return view;
        }
    }
}
