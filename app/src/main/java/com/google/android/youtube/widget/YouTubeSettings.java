/*
 * YouTube widget settings, shown in the launcher settings (Widgets): the channels whose new
 * videos it shows (typed in, or imported from the subscriptions.csv of Google Takeout) and
 * the refresh interval. Every change is applied at once.
 */
package com.google.android.youtube.widget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher.R;
import com.android.launcher2.GbDialog;
import com.android.launcher2.GbSettingsList;

import java.io.InputStream;
import java.util.LinkedHashSet;

public final class YouTubeSettings {
    private static final int REQUEST_IMPORT = 0x7957;
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;

    private final Activity mActivity;
    private LinkedHashSet<String> mChannels;
    private TextView mChannelsSummary, mIntervalSummary;

    public static YouTubeSettings add(Activity activity, GbSettingsList page) {
        return new YouTubeSettings(activity, page);
    }

    private YouTubeSettings(Activity activity, GbSettingsList page) {
        mActivity = activity;
        mChannels = Feed.channels(activity);
        page.addCategory(R.string.yt_widget_label);
        mChannelsSummary = item(page, R.string.yt_channels, null, v -> editChannels());
        item(page, R.string.yt_import, activity.getString(R.string.yt_import_summary),
                v -> importCsv());
        mIntervalSummary = item(page, R.string.yt_refresh_interval, null, v -> chooseInterval());
        updateSummaries();
    }

    private TextView item(GbSettingsList page, int title, CharSequence summary,
            View.OnClickListener click) {
        final View v = page.addItem(title, summary);
        v.setOnClickListener(click);
        return (TextView) v.findViewById(R.id.settings_item_summary);
    }

    private void updateSummaries() {
        GbSettingsList.setSummary(mChannelsSummary, mChannels.isEmpty()
                ? mActivity.getString(R.string.yt_channels_none)
                : mActivity.getString(R.string.yt_channels_count, mChannels.size()));
        GbSettingsList.setSummary(mIntervalSummary, label(Feed.intervalMinutes(mActivity)));
    }

    private String label(int minutes) {
        return minutes < 60 ? mActivity.getString(R.string.genie_minutes, minutes)
                : mActivity.getResources().getQuantityString(R.plurals.genie_hours,
                        minutes / 60, minutes / 60);
    }

    private void chooseInterval() {
        final CharSequence[] labels = new CharSequence[Feed.INTERVALS.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = label(Feed.INTERVALS[i]);
            if (Feed.INTERVALS[i] == Feed.intervalMinutes(mActivity)) checked = i;
        }
        new GbDialog.Builder(mActivity)
                .setTitle(R.string.yt_refresh_interval)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Feed.prefs(mActivity).edit()
                            .putInt(Feed.KEY_INTERVAL, Feed.INTERVALS[which]).apply();
                    FeedJob.reschedule(mActivity);
                    updateSummaries();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One channel ID per line; links and other text around the IDs are fine. */
    private void editChannels() {
        final EditText input = GbDialog.newEditText(mActivity);
        input.setTextColor(0xff000000); // the dialog background is light
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(join(mChannels));
        input.setSelection(input.getText().length());
        new GbDialog.Builder(mActivity)
                .setTitle(R.string.yt_channels)
                .setMessage(R.string.yt_channels_message)
                .setView(GbDialog.wrapField(mActivity, input))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    mChannels = Feed.parseChannels(input.getText().toString());
                    save();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String join(LinkedHashSet<String> ids) {
        final StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(id);
        }
        return sb.toString();
    }

    private void importCsv() {
        final Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            mActivity.startActivityForResult(pick, REQUEST_IMPORT);
        } catch (Exception e) {
            Toast.makeText(mActivity, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    /** Call from the settings activity's onActivityResult. */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        try {
            final InputStream in = mActivity.getContentResolver().openInputStream(uri);
            final String text;
            try {
                // subscriptions.csv: "Channel Id,Channel Url,Channel Title"
                text = Feed.readFully(new java.io.BufferedInputStream(
                        new LimitedInputStream(in, MAX_IMPORT_BYTES)));
            } finally {
                if (in != null) in.close();
            }
            final LinkedHashSet<String> found = Feed.parseChannels(text);
            if (found.isEmpty()) {
                Toast.makeText(mActivity, R.string.yt_import_failed, Toast.LENGTH_LONG).show();
                return;
            }
            mChannels.addAll(found);
            save();
            Toast.makeText(mActivity, mActivity.getString(R.string.yt_imported, found.size()),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mActivity, R.string.yt_import_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Stops reading after a limit, so a wrong (huge) file can't fill the memory. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private int mLeft;
        LimitedInputStream(InputStream in, int limit) {
            super(in);
            mLeft = limit;
        }
        @Override
        public int read() throws java.io.IOException {
            if (mLeft <= 0) return -1;
            final int b = super.read();
            if (b >= 0) mLeft--;
            return b;
        }
        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            if (mLeft <= 0) return -1;
            final int n = super.read(buf, off, Math.min(len, mLeft));
            if (n > 0) mLeft -= n;
            return n;
        }
    }

    private void save() {
        Feed.prefs(mActivity).edit()
                .putString(Feed.KEY_CHANNELS, join(mChannels))
                .putString(Feed.KEY_STATE, mChannels.isEmpty() ? Feed.STATE_OK : Feed.STATE_LOADING)
                .apply();
        updateSummaries();
        WidgetProvider.updateAll(mActivity);
        if (!mChannels.isEmpty()) FeedJob.scheduleNow(mActivity);
    }
}
