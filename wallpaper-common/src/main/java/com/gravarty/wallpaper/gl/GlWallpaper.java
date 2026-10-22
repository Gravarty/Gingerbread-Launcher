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

package com.gravarty.wallpaper.gl;

import android.os.Bundle;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * Port: RenderScriptWallpaper, with the RenderScriptGL context replaced by a GlThread.
 * Calls into the scene other than start()/stop() go through the render thread's queue.
 */
public abstract class GlWallpaper<T extends GlScene> extends WallpaperService {
    public Engine onCreateEngine() {
        return new RenderScriptEngine();
    }

    protected abstract T createScene(int width, int height);

    /** MagicSmoke and the Many visualizer enable touch events */
    protected boolean touchEventsEnabled() {
        return false;
    }

    private class RenderScriptEngine extends Engine {
        private GlThread mThread;
        private T mRenderer;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(touchEventsEnabled());
            surfaceHolder.setSizeFromLayout();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            destroyRenderer();
        }

        private void destroyRenderer() {
            if (mRenderer != null) {
                if (isVisible()) {
                    mRenderer.stop();
                }
                mRenderer = null;
            }
            if (mThread != null) {
                mThread.quit();
                mThread = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (mRenderer != null) {
                if (visible) {
                    mRenderer.start();
                } else {
                    mRenderer.stop();
                }
                mThread.setRunning(visible);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (mRenderer == null) {
                mRenderer = createScene(width, height);
                mRenderer.init(GlWallpaper.this, isPreview());
                mThread = new GlThread(holder, mRenderer);
                mThread.start();
                if (isVisible()) {
                    mRenderer.start();
                    mThread.setRunning(true);
                }
            } else {
                final T renderer = mRenderer;
                final int w = width, h = height;
                mThread.queue(new Runnable() {
                    public void run() {
                        renderer.resize(w, h);
                    }
                });
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            if (mRenderer != null) {
                final T renderer = mRenderer;
                final MotionEvent copy = MotionEvent.obtain(event);
                mThread.queue(new Runnable() {
                    public void run() {
                        renderer.onTouchEvent(copy);
                        copy.recycle();
                    }
                });
            }
        }

        @Override
        public void onOffsetsChanged(final float xOffset, final float yOffset,
                final float xStep, final float yStep, final int xPixels, final int yPixels) {
            if (mRenderer != null) {
                final T renderer = mRenderer;
                mThread.queue(new Runnable() {
                    public void run() {
                        renderer.setOffset(xOffset, yOffset, xStep, yStep, xPixels, yPixels);
                    }
                });
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            destroyRenderer();
        }

        @Override
        public Bundle onCommand(final String action, final int x, final int y, final int z,
                final Bundle extras, final boolean resultRequested) {
            if (mRenderer != null) {
                final T renderer = mRenderer;
                mThread.queue(new Runnable() {
                    public void run() {
                        renderer.onCommand(action, x, y, z, extras, resultRequested);
                    }
                });
            }
            return null;
        }
    }
}
