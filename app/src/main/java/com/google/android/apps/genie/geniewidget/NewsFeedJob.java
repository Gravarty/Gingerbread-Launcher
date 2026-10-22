/*
 * Port: loads the news widget's stories (NewsFeed.refresh()) off the main thread, with no time
 * limit like a broadcast receiver has, once there's a network.
 */
package com.google.android.apps.genie.geniewidget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

public class NewsFeedJob extends JobService {
    private static final int JOB_ID = 0x7959;

    private static final int JOB_ID_PERIODIC = 0x795B;

    /** Until v02 the periodic job was flexible over its whole period: replaced (see schedulePeriodic). */
    private static final int JOB_ID_PERIODIC_OLD = 0x795A;

    private static final String EXTRA_FORCE = "force";

    /**
     * A refresh that nobody waits for (boot, widget added, timer): only with a network and a
     * battery that isn't low, skipped if the stories are fresh (see onStartJob), and it never
     * replaces a pending forced one.
     */
    static void schedule(Context context) {
        schedule(context, false);
    }

    /** A refresh somebody waits for (feeds changed, "retry"): as soon as there's a network. */
    static void scheduleNow(Context context) {
        schedule(context, true);
    }

    private static void schedule(Context context, boolean force) {
        final JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            if (!force && js.getPendingJob(JOB_ID) != null) return; // one is waiting already
            final PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(EXTRA_FORCE, force);
            final JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, NewsFeedJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras);
            if (!force) b.setRequiresBatteryNotLow(true);
            js.schedule(b.build());
        } catch (RuntimeException e) {
            // never take the launcher down because of the widget's refresh job
            android.util.Log.w("NewsWidget", "schedule failed: " + e);
        }
    }

    /** The regular refresh, every NewsFeed.intervalMinutes() (setting in the launcher settings). */
    static void schedulePeriodic(Context context) {
        final JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            // the news interval, or the weather one if that is shorter (one job for both)
            final long period = NewsWidgetProvider.periodMinutes(context) * 60000L;
            final JobInfo pending = js.getPendingJob(JOB_ID_PERIODIC);
            js.cancel(JOB_ID_PERIODIC_OLD);
            if (pending != null && pending.isPeriodic() && pending.getIntervalMillis() == period) {
                return;
            }
            final PersistableBundle extras = new PersistableBundle();
            // not forced: the job loads only what is due (news, weather), nothing if all is fresh
            extras.putBoolean(EXTRA_FORCE, false);
            js.schedule(new JobInfo.Builder(JOB_ID_PERIODIC,
                    new ComponentName(context, NewsFeedJob.class))
                    // flex: the run is in the last 5 minutes of the period (default: anywhere in
                    // it), so two runs are never much closer than the interval, nor far apart
                    .setPeriodic(period, 5 * 60000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setExtras(extras)
                    .build());
        } catch (RuntimeException e) {
            android.util.Log.w("NewsWidget", "schedulePeriodic failed: " + e);
        }
    }

    /** The interval was changed. */
    static void reschedule(Context context) {
        final JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID_PERIODIC);
        schedulePeriodic(context);
    }

    static void cancel(Context context) {
        final JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            js.cancel(JOB_ID);
            js.cancel(JOB_ID_PERIODIC);
            js.cancel(JOB_ID_PERIODIC_OLD);
        }
    }

    private volatile Thread mThread;

    /**
     * News and weather in one run, so the network is woken once. Each only if it is due (or the
     * job is forced), and nothing at all, not even a thread, if both are fresh. The widgets are
     * drawn only if what they show changed (NewsWidgetProvider.signature()).
     */
    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        if (!NewsWidgetProvider.hasWidgets(app)) return false; // no widget: nothing to keep up to date
        final boolean force = params.getExtras().getBoolean(EXTRA_FORCE, false);
        final boolean newsDue = !NewsFeed.feeds(app).isEmpty() && (force || !NewsFeed.isFresh(app));
        final int[] weatherIds = NewsWidgetProvider.weatherIds(app, force);
        if (!newsDue && weatherIds.length == 0) return false; // nothing to do: no network, no work
        mThread = new Thread(() -> {
            boolean newsOk = true, weatherOk = true;
            String drawn = null;
            try {
                drawn = NewsWidgetProvider.signature(app);
                final boolean noNews = NewsFeed.loadCache(app).isEmpty();
                // the spinner, but only while there is nothing to show yet
                boolean spinner = false;
                if (newsDue) {
                    NewsFeed.prefs(app).edit()
                            .putString(NewsFeed.KEY_STATE, NewsFeed.STATE_LOADING).apply();
                    spinner = noNews;
                }
                if (NewsWidgetProvider.markWeatherLoading(app, weatherIds) && noNews) {
                    spinner = true;
                }
                if (spinner) {
                    NewsWidgetProvider.updateAll(app);
                    drawn = NewsWidgetProvider.signature(app);
                }
                if (newsDue) newsOk = NewsFeed.refresh(app);
                weatherOk = NewsWidgetProvider.loadWeather(app, weatherIds);
            } catch (Throwable t) {
                android.util.Log.w("NewsWidget", "refresh failed: " + t);
                newsOk = false;
                weatherOk = false;
            } finally {
                if (newsDue) {
                    NewsFeed.prefs(app).edit().putString(NewsFeed.KEY_STATE,
                            newsOk ? NewsFeed.STATE_OK : NewsFeed.STATE_ERROR).apply();
                }
                // rebuilding the widget restarts its flipper at the first story: only if something changed
                if (drawn == null || !drawn.equals(NewsWidgetProvider.signature(app))) {
                    NewsWidgetProvider.updateAll(app);
                }
                mThread = null;
                jobFinished(params, !((!newsDue || newsOk) && weatherOk)); // failed: later, with backoff
            }
        });
        mThread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // the system stopped it (e.g. the network is gone): run again later
    }
}
