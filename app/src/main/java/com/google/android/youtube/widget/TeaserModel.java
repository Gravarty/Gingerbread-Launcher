/*
 * Port: TeaserModel of YouTube 1.6.19 (the widget's list of teaser videos and their
 * thumbnails). The original filled it from three GData feeds (subscription updates,
 * recommended, most viewed) that no longer exist. Here the videos come from the cache of
 * Feed (the newest videos of the channels set up in the launcher settings, loaded by FeedJob).
 * As in the original, a video without its thumbnail is not shown.
 */
package com.google.android.youtube.widget;

import android.content.Context;

import java.util.ArrayList;

final class TeaserModel {
    private final ArrayList<Feed.Video> teaserVideos = new ArrayList<Feed.Video>(10);
    private final ArrayList<byte[]> teaserThumbnails = new ArrayList<byte[]>(10);
    private final boolean hasChannels;
    private final boolean isFetching;

    TeaserModel(Context context) {
        hasChannels = !Feed.channels(context).isEmpty();
        isFetching = Feed.STATE_LOADING.equals(
                Feed.prefs(context).getString(Feed.KEY_STATE, Feed.STATE_OK));
        if (!hasChannels) return;
        for (Feed.Video video : Feed.loadCache(context)) {
            final byte[] thumb = Feed.thumbBytes(context, video.id);
            if (thumb == null) continue;
            teaserVideos.add(video);
            teaserThumbnails.add(thumb);
        }
    }

    int getNumberOfVideos() {
        return teaserVideos.size();
    }

    Feed.Video getVideo(int i) {
        return teaserVideos.get(i);
    }

    /** The thumbnail as the JPEG it is stored as (see Feed.thumbBytes). */
    byte[] getThumbnail(int i) {
        return teaserThumbnails.get(i);
    }

    boolean isFetching() {
        return isFetching;
    }

    /** Port: without channels there is nothing to fetch (the original always had a feed). */
    boolean hasChannels() {
        return hasChannels;
    }
}
