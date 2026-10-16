package com.android.launcher2;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;

/**
 * Tweaks: notification badges. TouchWiz read the counts from Samsung's BadgeProvider, which
 * the apps filled themselves; today the counts come from the notifications, counted like
 * Launcher3 (NotificationListener / DotInfo: Notification.number, at least 1 each, without
 * group summaries and channels that don't show badges).
 */
public class BadgeListener extends NotificationListenerService {
    private static final HashMap<String, Integer> sCounts = new HashMap<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static Runnable sOnChange;

    /** Count for the package, 0 = no badge. Main thread. */
    static int getCount(String packageName) {
        final Integer c = packageName == null ? null : sCounts.get(packageName);
        return c == null ? 0 : c;
    }

    static void setOnChange(Runnable r) {
        sOnChange = r;
    }

    static boolean isEnabled(Context context) {
        return android.app.NotificationManager.class.cast(
                context.getSystemService(Context.NOTIFICATION_SERVICE))
                .isNotificationListenerAccessGranted(
                        new ComponentName(context, BadgeListener.class));
    }

    @Override
    public void onListenerConnected() {
        update();
    }

    @Override
    public void onListenerDisconnected() {
        post(new HashMap<>());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        update();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        update();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        update();
    }

    private void update() {
        final HashMap<String, Integer> counts = new HashMap<>();
        final StatusBarNotification[] active;
        final RankingMap ranking;
        try {
            active = getActiveNotifications();
            ranking = getCurrentRanking();
        } catch (RuntimeException e) {
            return; // not connected
        }
        if (active == null) return;
        final Ranking r = new Ranking();
        for (StatusBarNotification sbn : active) {
            if (ranking != null && ranking.getRanking(sbn.getKey(), r) && !r.canShowBadge()) {
                continue;
            }
            final Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            final Integer old = counts.get(sbn.getPackageName());
            counts.put(sbn.getPackageName(), (old == null ? 0 : old) + Math.max(1, n.number));
        }
        post(counts);
    }

    private static void post(final HashMap<String, Integer> counts) {
        sMain.post(() -> {
            sCounts.clear();
            sCounts.putAll(counts);
            if (sOnChange != null) sOnChange.run();
        });
    }
}
