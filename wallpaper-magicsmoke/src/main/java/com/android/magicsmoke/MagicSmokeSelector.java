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


package com.android.magicsmoke;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.gravarty.wallpaper.gl.GlThread;

/**
 * Port: the original showed the wallpaper behind this activity by attaching to the
 * wallpaper service through hidden APIs (IWallpaperService). Here the same scene runs in
 * a SurfaceView behind the original layout.
 */
public class MagicSmokeSelector extends Activity implements SurfaceHolder.Callback {
    private static final String LOG_TAG = "MagicSmokeSelector";
    private SharedPreferences mSharedPref;
    private int mCurrentPreset;

    private MagicSmokeRS mScene;
    private GlThread mThread;
    private boolean mResumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final FrameLayout root = new FrameLayout(this);
        final SurfaceView preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(getLayoutInflater().inflate(R.layout.selector, root, false));
        setContentView(root);

        mSharedPref = getSharedPreferences("magicsmoke", Context.MODE_PRIVATE);
        mCurrentPreset = mSharedPref.getInt("preset", MagicSmokeRS.DEFAULT_PRESET);
        if (mCurrentPreset >= MagicSmokeRS.mPreset.length) {
            mCurrentPreset = 0;
            updatePrefs();
        }
    }

    // button hook
    public void setLiveWallpaper(View v) {
        finish();
    }

    @Override
    public void onResume() {
        super.onResume();
        mResumed = true;
        if (mScene != null) {
            mScene.start();
            mThread.setRunning(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mResumed = false;
        if (mScene != null) {
            mScene.stop();
            mThread.setRunning(false);
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
    }

    public void surfaceChanged(SurfaceHolder holder, int format, final int width,
            final int height) {
        if (mScene == null) {
            mScene = new MagicSmokeRS(this, width, height);
            mScene.init(this, true);
            mThread = new GlThread(holder, mScene);
            mThread.start();
            if (mResumed) {
                mScene.start();
                mThread.setRunning(true);
            }
        } else {
            final MagicSmokeRS scene = mScene;
            mThread.queue(new Runnable() {
                public void run() {
                    scene.resize(width, height);
                }
            });
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mScene != null) {
            if (mResumed) {
                mScene.stop();
            }
            mScene = null;
        }
        if (mThread != null) {
            mThread.quit();
            mThread = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // TODO: make this conditional on preview mode. Right now we
        // don't get touch events in preview mode.
        switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mCurrentPreset == 0) {
                    mCurrentPreset = MagicSmokeRS.mPreset.length - 1;
                } else {
                    mCurrentPreset--;
                }
                updatePrefs();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updatePrefs() {
        Editor edit = mSharedPref.edit();
        edit.putInt("preset", mCurrentPreset);
        edit.apply();
    }
}
