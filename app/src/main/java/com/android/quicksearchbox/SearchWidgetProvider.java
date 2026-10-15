/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Port notes: the search widget of AOSP QuickSearchBox (android-2.3.7_r1), bundled with
 * the launcher because Gingerbread's QSB doesn't exist on modern Android. Layout and
 * graphics are unchanged. What needed QSB itself is left out:
 *  - corpus selection (the configure activity) - the widget always searches the web,
 *    which was the default;
 *  - voice search hints - they came from the old Voice Search app's
 *    ACTION_GET_LANGUAGE_DETAILS broadcast, which nothing answers anymore.
 * The text field starts the device's global search (QSB was the global search then),
 * the microphone starts voice web search; it is hidden if nothing handles it, as before.
 */

package com.android.quicksearchbox;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.launcher.R;

/**
 * Search widget provider.
 *
 */
public class SearchWidgetProvider extends BroadcastReceiver {

    private static final boolean DBG = false;
    private static final String TAG = "QSB.SearchWidgetProvider";

    /**
     * The {@link Search#SOURCE} value used when starting searches from the search widget.
     */
    private static final String WIDGET_SEARCH_SOURCE = "launcher-widget";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DBG) Log.d(TAG, "onReceive(" + intent.toUri(0) + ")");
        String action = intent.getAction();
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            updateSearchWidgets(context);
        } else {
            if (DBG) Log.d(TAG, "Unhandled intent action=" + action);
        }
    }

    /**
     * Updates all search widgets.
     */
    public static void updateSearchWidgets(Context context) {
        if (DBG) Log.d(TAG, "updateSearchWidgets");
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(myComponentName(context));
        for (int appWidgetId : appWidgetIds) {
            getSearchWidgetState(context, appWidgetId).updateWidget(context, appWidgetManager);
        }
    }

    /**
     * Gets the component name of this search widget provider.
     */
    static ComponentName myComponentName(Context context) {
        return new ComponentName(context, SearchWidgetProvider.class);
    }

    private static Intent createQsbActivityIntent(Context context, String action,
            Bundle widgetAppData) {
        Intent qsbIntent = new Intent(action);
        // Port: no setPackage(), QSB was the global search activity.
        qsbIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        qsbIntent.putExtra(SearchManager.APP_DATA, widgetAppData);
        return qsbIntent;
    }

    private static SearchWidgetState getSearchWidgetState(Context context, int appWidgetId) {
        if (DBG) Log.d(TAG, "Creating appwidget state " + appWidgetId);
        SearchWidgetState state = new SearchWidgetState(appWidgetId);

        Bundle widgetAppData = new Bundle();
        widgetAppData.putString("source" /* Search.SOURCE */, WIDGET_SEARCH_SOURCE);

        // Query text view hint (web corpus)
        state.setQueryTextViewBackgroundResource(R.drawable.textfield_search_empty_google);

        // Text field click
        Intent qsbIntent = createQsbActivityIntent(
                context,
                SearchManager.INTENT_ACTION_GLOBAL_SEARCH,
                widgetAppData);
        state.setQueryTextViewIntent(qsbIntent);

        // Voice search button
        state.setVoiceSearchIntent(createVoiceWebSearchIntent(context, widgetAppData));

        // Corpus indicator (opened QSB with the corpus selector; global search now)
        state.setCorpusIndicatorIntent(qsbIntent);

        return state;
    }

    /** VoiceSearch.createVoiceWebSearchIntent() */
    private static Intent createVoiceWebSearchIntent(Context context, Bundle appData) {
        Intent intent = new Intent(RecognizerIntent.ACTION_WEB_SEARCH);
        if (context.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return null;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        if (appData != null) {
            intent.putExtra(SearchManager.APP_DATA, appData);
        }
        return intent;
    }

    private static class SearchWidgetState {
        private final int mAppWidgetId;
        private Intent mCorpusIndicatorIntent;
        private int mQueryTextViewBackgroundResource;
        private Intent mQueryTextViewIntent;
        private Intent mVoiceSearchIntent;

        public SearchWidgetState(int appWidgetId) {
            mAppWidgetId = appWidgetId;
        }

        public void setCorpusIndicatorIntent(Intent corpusIndicatorIntent) {
            mCorpusIndicatorIntent = corpusIndicatorIntent;
        }

        public void setQueryTextViewBackgroundResource(int queryTextViewBackgroundResource) {
            mQueryTextViewBackgroundResource = queryTextViewBackgroundResource;
        }

        public void setQueryTextViewIntent(Intent queryTextViewIntent) {
            mQueryTextViewIntent = queryTextViewIntent;
        }

        public void setVoiceSearchIntent(Intent voiceSearchIntent) {
            mVoiceSearchIntent = voiceSearchIntent;
        }

        public void updateWidget(Context context,AppWidgetManager appWidgetMgr) {
            if (DBG) Log.d(TAG, "Updating appwidget " + mAppWidgetId);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.search_widget);
            // Corpus indicator
            views.setImageViewResource(R.id.corpus_indicator, R.drawable.search_app_icon);
            setOnClickActivityIntent(context, views, R.id.corpus_indicator,
                    mCorpusIndicatorIntent, 0);
            // Query TextView
            views.setInt(R.id.search_widget_text, "setBackgroundResource",
                    mQueryTextViewBackgroundResource);

            setOnClickActivityIntent(context, views, R.id.search_widget_text,
                    mQueryTextViewIntent, 1);
            // Voice Search button
            if (mVoiceSearchIntent != null) {
                setOnClickActivityIntent(context, views, R.id.search_widget_voice_btn,
                        mVoiceSearchIntent, 2);
                views.setViewVisibility(R.id.search_widget_voice_btn, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.search_widget_voice_btn, View.GONE);
            }

            // Voice Search hints (not available, see port notes)
            views.setViewVisibility(R.id.voice_search_hint_container, View.GONE);
            views.setViewVisibility(R.id.search_widget_text, View.VISIBLE);
            views.setViewVisibility(R.id.corpus_indicator, View.VISIBLE);

            appWidgetMgr.updateAppWidget(mAppWidgetId, views);
        }

        private void setOnClickActivityIntent(Context context, RemoteViews views, int viewId,
                Intent intent, int requestCode) {
            // Port: distinct request codes and FLAG_IMMUTABLE (required since Android 12)
            PendingIntent pendingIntent = PendingIntent.getActivity(context,
                    mAppWidgetId * 3 + requestCode, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            views.setOnClickPendingIntent(viewId, pendingIntent);
        }
    }
}
