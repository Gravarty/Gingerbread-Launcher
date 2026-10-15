package com.android.launcher2;

import com.google.android.libraries.launcherclient.IScrollCallback;
import com.google.android.libraries.launcherclient.LauncherClient;
import com.google.android.libraries.launcherclient.StaticInteger;

/**
 * Tweaks: the Google feed left of the first home screen, like Lawnchair's
 * OverlayCallbackImpl. The Workspace sends its overscroll, the feed sends back its
 * progress and the launcher moves out of its way like Launcher3's
 * Workspace.onOverlayScrollChanged().
 */
class FeedOverlay implements IScrollCallback {
    static final int PROVIDER_OFF = 0;
    static final int PROVIDER_GOOGLE = 1;
    static final int PROVIDER_AIDL_BRIDGE = 2;
    static final String AIDL_BRIDGE_PACKAGE = "amirz.aidlbridge";

    private final Launcher mLauncher;
    private final LauncherClient mClient;
    private boolean mAttached;
    private boolean mScrolling;
    private float mProgress;

    FeedOverlay(Launcher launcher, int provider) {
        mLauncher = launcher;
        LauncherClient.sBridgePackage =
                provider == PROVIDER_AIDL_BRIDGE ? AIDL_BRIDGE_PACKAGE : null;
        // Lawnchair's flags: feed enabled | 2 | 4 | 8
        mClient = new LauncherClient(launcher, this, new StaticInteger(1 | 2 | 4 | 8));
    }

    /** True when the feed is connected and can be pulled in. */
    boolean isAttached() {
        return mAttached;
    }

    boolean isShowing() {
        return mProgress > 0;
    }

    // Workspace: overscroll left of the first screen

    void startScroll() {
        if (!mScrolling) {
            mScrolling = true;
            mClient.startScroll();
        }
    }

    void setScroll(float progress) {
        mClient.setScroll(progress);
    }

    void endScroll() {
        if (mScrolling) {
            mScrolling = false;
            mClient.endScroll();
        }
    }

    void hide(boolean animate) {
        mClient.hideOverlay(animate);
    }

    // IScrollCallback

    @Override
    public void onOverlayScrollChanged(float progress) {
        mProgress = Math.max(0, Math.min(progress, 1));
        // Launcher3: translated by the progress, faded with DEACCEL_3
        final DragLayer dragLayer = mLauncher.getDragLayer();
        dragLayer.setTranslationX(dragLayer.getMeasuredWidth() * mProgress);
        dragLayer.setAlpha((float) Math.pow(1 - mProgress, 6));
    }

    @Override
    public void onServiceStateChanged(boolean overlayAttached) {
        mAttached = overlayAttached;
        if (!overlayAttached) onOverlayScrollChanged(0);
    }

    // Activity

    void onAttachedToWindow() { mClient.onAttachedToWindow(); }
    void onDetachedFromWindow() { mClient.onDetachedFromWindow(); }
    void onStart() { mClient.onStart(); }
    void onResume() { mClient.onResume(); }
    void onPause() { mClient.onPause(); }
    void onStop() { mClient.onStop(); }
    void onDestroy() { mClient.onDestroy(); }
}
