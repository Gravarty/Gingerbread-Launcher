/*
 * Port: loads the YouTube widget's videos (Feed.refresh()) off the main thread, with no time
 * limit like a broadcast receiver has, once there's a network.
 */
package com.google.android.youtube.widget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

public class FeedJob extends JobService {
    private static final int JOB_ID = 0x7957;

    private static final int JOB_ID_PERIODIC = 0x7958;

    private static final String EXTRA_FORCE = "force";

    /**
     * A refresh that nobody waits for (boot, widget added, timer): only with a network and a
     * battery that isn't low, skipped if the videos are fresh (see onStartJob), and it never
     * replaces a pending forced one.
     */
    static void schedule(Context context) {
        schedule(context, false);
    }

    /** A refresh somebody waits for (channels changed, "touch to try again"): as soon as there's a network. */
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
            final JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(context, FeedJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras);
            if (!force) b.setRequiresBatteryNotLow(true);
            js.schedule(b.build());
        } catch (RuntimeException e) {
            // never take the launcher down because of the widget's refresh job
            android.util.Log.w("YouTubeWidget", "schedule failed: " + e);
        }
    }

    /** The regular refresh, every Feed.intervalMinutes() (setting in the launcher settings). */
    static void schedulePeriodic(Context context) {
        final JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            final long period = Feed.intervalMinutes(context) * 60000L;
            final android.app.job.JobInfo pending = js.getPendingJob(JOB_ID_PERIODIC);
            if (pending != null && pending.isPeriodic() && pending.getIntervalMillis() == period) {
                return;
            }
            final PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(EXTRA_FORCE, true); // its time has come: no freshness check
            js.schedule(new JobInfo.Builder(JOB_ID_PERIODIC, new ComponentName(context, FeedJob.class))
                    .setPeriodic(period)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setExtras(extras)
                    .build());
        } catch (RuntimeException e) {
            android.util.Log.w("YouTubeWidget", "schedulePeriodic failed: " + e);
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
        }
    }

    private volatile Thread mThread;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        if (!params.getExtras().getBoolean(EXTRA_FORCE, false) && Feed.isFresh(app)) {
            return false; // loaded recently: no network, no work
        }
        mThread = new Thread(() -> {
            boolean ok = false;
            String before = null;
            String stateBefore = null;
            try {
                // the spinner while there's nothing to show yet
                if (Feed.loadCache(app).isEmpty()) {
                    Feed.prefs(app).edit().putString(Feed.KEY_STATE, Feed.STATE_LOADING).apply();
                    WidgetProvider.updateAll(app);
                }
                before = shown(app);
                stateBefore = Feed.prefs(app).getString(Feed.KEY_STATE, Feed.STATE_OK);
                ok = Feed.refresh(app);
            } catch (Throwable t) {
                android.util.Log.w("YouTubeWidget", "refresh failed: " + t);
            } finally {
                final String state = ok ? Feed.STATE_OK : Feed.STATE_ERROR;
                Feed.prefs(app).edit().putString(Feed.KEY_STATE, state).apply();
                // rebuilding the widget restarts its flipper at the first video: only if something changed
                if (before == null || !state.equals(stateBefore) || !before.equals(shown(app))) {
                    WidgetProvider.updateAll(app);
                }
                mThread = null;
                jobFinished(params, !ok); // not ok: try again later (with backoff)
            }
        });
        mThread.start();
        return true;
    }

    /** What the widget shows: the videos that have a thumbnail (as TeaserModel does), in order, with their titles. */
    private static String shown(Context context) {
        final StringBuilder sb = new StringBuilder();
        for (Feed.Video v : Feed.loadCache(context)) {
            if (Feed.thumbFile(context, v.id).isFile()) {
                sb.append(v.id).append('|').append(v.title).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // the system stopped it (e.g. the network is gone): run again later
    }
}
