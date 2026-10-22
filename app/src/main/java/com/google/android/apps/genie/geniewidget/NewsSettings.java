/*
 * News widget settings, shown in the launcher settings (Widgets): the RSS / Atom feeds whose
 * stories it shows (typed in or pasted, one link per line) and the refresh interval. The
 * weather next to the news is set up in the weather settings above. Every change is applied
 * at once.
 */
package com.google.android.apps.genie.geniewidget;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher.R;
import com.android.launcher2.GbDialog;
import com.android.launcher2.GbSettingsList;

import java.util.LinkedHashSet;

public final class NewsSettings {
    private final Activity mActivity;
    private LinkedHashSet<String> mFeeds;
    private TextView mFeedsSummary, mIntervalSummary;

    public static void add(Activity activity, GbSettingsList page) {
        new NewsSettings(activity, page);
    }

    private NewsSettings(Activity activity, GbSettingsList page) {
        mActivity = activity;
        mFeeds = NewsFeed.feeds(activity);
        page.addCategory(R.string.genie_news_category);
        mFeedsSummary = item(page, R.string.genie_news_feeds, v -> editFeeds());
        mIntervalSummary = item(page, R.string.genie_refresh_interval, v -> chooseInterval());
        updateSummaries();
    }

    private TextView item(GbSettingsList page, int title, View.OnClickListener click) {
        final View v = page.addItem(title, null);
        v.setOnClickListener(click);
        return (TextView) v.findViewById(R.id.settings_item_summary);
    }

    private void updateSummaries() {
        GbSettingsList.setSummary(mFeedsSummary, mFeeds.isEmpty()
                ? mActivity.getString(R.string.genie_news_feeds_none)
                : mActivity.getResources().getQuantityString(R.plurals.genie_news_feeds_count,
                        mFeeds.size(), mFeeds.size()));
        final int minutes = NewsFeed.intervalMinutes(mActivity);
        GbSettingsList.setSummary(mIntervalSummary, label(minutes));
    }

    private String label(int minutes) {
        return minutes < 60 ? mActivity.getString(R.string.genie_minutes, minutes)
                : mActivity.getResources().getQuantityString(R.plurals.genie_hours,
                        minutes / 60, minutes / 60);
    }

    private void chooseInterval() {
        final CharSequence[] labels = new CharSequence[NewsFeed.INTERVALS.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = label(NewsFeed.INTERVALS[i]);
            if (NewsFeed.INTERVALS[i] == NewsFeed.intervalMinutes(mActivity)) checked = i;
        }
        new GbDialog.Builder(mActivity)
                .setTitle(R.string.genie_refresh_interval)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    NewsFeed.prefs(mActivity).edit()
                            .putInt(NewsFeed.KEY_INTERVAL, NewsFeed.INTERVALS[which]).apply();
                    NewsFeedJob.reschedule(mActivity);
                    updateSummaries();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One feed link per line; an empty field removes all feeds. */
    private void editFeeds() {
        final EditText input = GbDialog.newEditText(mActivity);
        input.setTextColor(0xff000000); // the dialog background is light
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(join(mFeeds));
        input.setSelection(input.getText().length());
        new GbDialog.Builder(mActivity)
                .setTitle(R.string.genie_news_feeds)
                .setMessage(R.string.genie_news_feeds_message)
                .setView(GbDialog.wrapField(mActivity, input))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    final String text = input.getText().toString();
                    final LinkedHashSet<String> feeds = NewsFeed.parseFeeds(text);
                    if (feeds.isEmpty() && !text.trim().isEmpty()) {
                        Toast.makeText(mActivity, R.string.genie_news_feeds_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    mFeeds = feeds;
                    save();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String join(LinkedHashSet<String> urls) {
        final StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(url);
        }
        return sb.toString();
    }

    private void save() {
        NewsFeed.clearCache(mActivity);
        NewsFeed.prefs(mActivity).edit()
                .putString(NewsFeed.KEY_FEEDS, join(mFeeds))
                .putString(NewsFeed.KEY_STATE, mFeeds.isEmpty() ? NewsFeed.STATE_OK : NewsFeed.STATE_LOADING)
                .apply();
        updateSummaries();
        NewsWidgetProvider.updateAll(mActivity);
        if (!mFeeds.isEmpty()) NewsFeedJob.scheduleNow(mActivity);
    }
}
