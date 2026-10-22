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

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.ArrayList;

/**
 * Port: replaces the RenderScriptGL context: an EGL context on the surface and the loop
 * that runs the root script, waiting the delay it returns between frames.
 * Calls into the scene from other threads are queued and run here before the next frame.
 */
public final class GlThread extends Thread {
    private static final String TAG = "GlThread";
    private static final int EGL_OPENGL_ES3_BIT = 0x40;

    private final SurfaceHolder mHolder;
    private final GlScene mScene;
    private final ArrayList<Runnable> mQueue = new ArrayList<Runnable>();
    private boolean mRunning;
    private boolean mQuit;

    public GlThread(SurfaceHolder holder, GlScene scene) {
        super("GlThread");
        mHolder = holder;
        mScene = scene;
        scene.mThread = this;
    }

    public synchronized void queue(Runnable r) {
        mQueue.add(r);
        notifyAll();
    }

    /** RenderScriptScene.start()/stop(): binds or unbinds the root script. */
    public synchronized void setRunning(boolean running) {
        mRunning = running;
        notifyAll();
    }

    public void quit() {
        synchronized (this) {
            mQuit = true;
            notifyAll();
        }
        try {
            join();
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    private static EGLConfig chooseConfig(EGLDisplay display, int renderable) {
        final int[] attribs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        };
        final EGLConfig[] configs = new EGLConfig[1];
        final int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            return null;
        }
        return configs[0];
    }

    @Override
    public void run() {
        final EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        final int[] version = new int[2];
        EGL14.eglInitialize(display, version, 0, version, 1);

        // ES 3 where available: textures of any size wrap and mipmap there (pond, ...),
        // which ES 2 only allows with an extension. The shaders are ES 2 ones either way.
        EGLConfig config = chooseConfig(display, EGL_OPENGL_ES3_BIT);
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        if (config != null) {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE }, 0);
        }
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            config = chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT);
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
        }
        final EGLSurface surface = EGL14.eglCreateWindowSurface(display, config,
                mHolder.getSurface(), new int[] { EGL14.EGL_NONE }, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed");
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            return;
        }
        EGL14.eglMakeCurrent(display, surface, surface, context);

        mScene.initGl();

        while (true) {
            final ArrayList<Runnable> events = new ArrayList<Runnable>();
            synchronized (this) {
                while (!mQuit && !mRunning && mQueue.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                if (mQuit) break;
                events.addAll(mQueue);
                mQueue.clear();
            }
            for (Runnable r : events) {
                r.run();
            }
            if (!mRunning) continue;

            final long start = SystemClock.uptimeMillis();
            final int delay = mScene.frame();
            EGL14.eglSwapBuffers(display, surface);

            // The next frame comes after the delay, also when events arrive in between:
            // the scripts advance their animations per frame.
            final long next = start + delay;
            synchronized (this) {
                long wait;
                while (!mQuit && mRunning && (wait = next - SystemClock.uptimeMillis()) > 0) {
                    try {
                        wait(wait);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }

        mScene.destroyGl();
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }
}
