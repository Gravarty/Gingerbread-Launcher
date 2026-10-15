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
 * Port notes: the "Nexus" live wallpaper of AOSP packages/wallpapers/Basic
 * (android-2.3.7_r1), bundled with the launcher. The original ran on RenderScriptGL
 * (RenderScriptWallpaper, RenderScriptScene, NexusRS and res/raw/nexus.rs), which was
 * removed from Android. This file contains the same pieces on OpenGL ES:
 *  - Engine: RenderScriptWallpaper.RenderScriptEngine (+ an EGL render thread instead of
 *    the RS context, drawing every 45 ms like the script's "return 45").
 *  - NexusRS: the Java side (state, commands, textures, program store/fragment setup).
 *  - Script: nexus.rs, line by line.
 * The script works in pixels of the original 480x800 hdpi screen, where the 960x800
 * background was drawn 1:1. Those pixels are kept as a virtual coordinate space that is
 * scaled uniformly so the background covers the screen (centered, in whole 14px cells so
 * the pulses stay on the background's grid), instead of stretching it to the screen.
 *
 * The Nexus S (crespo) shipped a variant through its device overlay
 * (device/samsung/crespo/overlay/packages/wallpapers/Basic): another background, 32-bit
 * surface, and pulses at random depths (per-pulse scale) in nexus.rs. NexusSWallpaper
 * selects it; those script changes are marked "crespo".
 */

package com.android.wallpaper.nexus;

import com.android.launcher.R;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Random;

public class NexusWallpaper extends WallpaperService {
    private static final String TAG = "NexusWallpaper";

    public Engine onCreateEngine() {
        return new RenderScriptEngine();
    }

    /** Port: true for the Nexus S (crespo) overlay variant. */
    protected boolean isCrespo() {
        return false;
    }

    /** RenderScriptWallpaper.RenderScriptEngine */
    private class RenderScriptEngine extends Engine {
        private RenderThread mThread;
        private NexusRS mRenderer;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(false);
            surfaceHolder.setSizeFromLayout();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            destroyRenderer();
        }

        private void destroyRenderer() {
            if (mThread != null) {
                mThread.quit();
                mThread = null;
            }
            mRenderer = null;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (mThread != null) {
                mThread.setRunning(visible);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (mRenderer == null) {
                mRenderer = new NexusRS(width, height, isCrespo());
                mRenderer.init(getResources(), isPreview());
                mThread = new RenderThread(holder, mRenderer);
                mThread.start();
                mThread.setRunning(isVisible());
            } else {
                final NexusRS renderer = mRenderer;
                final int w = width, h = height;
                mThread.queue(new Runnable() {
                    public void run() {
                        renderer.resize(w, h);
                    }
                });
            }
        }

        @Override
        public void onOffsetsChanged(final float xOffset, final float yOffset,
                float xStep, float yStep, final int xPixels, final int yPixels) {
            if (mRenderer == null) return;
            final NexusRS renderer = mRenderer;
            mThread.queue(new Runnable() {
                public void run() {
                    renderer.setOffset(xOffset, yOffset, xPixels, yPixels);
                }
            });
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            destroyRenderer();
        }

        @Override
        public Bundle onCommand(final String action, final int x, final int y, final int z,
                final Bundle extras, final boolean resultRequested) {
            if (mRenderer == null) return null;
            final NexusRS renderer = mRenderer;
            mThread.queue(new Runnable() {
                public void run() {
                    renderer.onCommand(action, x, y, z, extras, resultRequested);
                }
            });
            return null;
        }
    }

    /** Port: replaces the RenderScriptGL context (EGL + the root script loop). */
    private static class RenderThread extends Thread {
        private final SurfaceHolder mHolder;
        private final NexusRS mScene;
        private final ArrayList<Runnable> mQueue = new ArrayList<Runnable>();
        private boolean mRunning;
        private boolean mQuit;

        RenderThread(SurfaceHolder holder, NexusRS scene) {
            super("NexusWallpaper");
            mHolder = holder;
            mScene = scene;
        }

        synchronized void queue(Runnable r) {
            mQueue.add(r);
            notifyAll();
        }

        synchronized void setRunning(boolean running) {
            mRunning = running;
            notifyAll();
        }

        void quit() {
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

        @Override
        public void run() {
            final EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            final int[] version = new int[2];
            EGL14.eglInitialize(display, version, 0, version, 1);
            final int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            };
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] num = new int[1];
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0);
            final EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE }, 0);
            final EGLSurface surface = EGL14.eglCreateWindowSurface(display, configs[0],
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
                final int delay = mScene.draw();
                EGL14.eglSwapBuffers(display, surface);

                final long wait = delay - (SystemClock.uptimeMillis() - start);
                if (wait > 0) {
                    synchronized (this) {
                        if (!mQuit) {
                            try {
                                wait(wait);
                            } catch (InterruptedException e) {
                                // Ignore
                            }
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

    /** NexusRS + nexus.rs */
    static class NexusRS {
        // Port: the background's size in the original hdpi pixels, and nexus.rs' cell size.
        private static final int BG_WIDTH = 960;
        private static final int BG_HEIGHT = 800;

        private int mWidth;
        private int mHeight;
        private boolean mPreview;
        private Resources mResources;

        private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

        private WorldState mWorldState;
        private CommandState mCommand;

        // Port: screen px per virtual (original hdpi) px, and the background crop.
        private float mScale;
        private int mCropX;
        private int mCropY;

        static class WorldState {
            public int width;
            public int height;
            public float glWidth;
            public float glHeight;
            public int rotate;
            public int isPreview;
            public float xOffset;
            public int mode;
        }

        static class CommandState {
            public int x;
            public int y;
            public int command;
        }

        private final boolean mCrespo;

        NexusRS(int width, int height, boolean crespo) {
            mWidth = width;
            mHeight = height;
            mCrespo = crespo;

            mOptionsARGB.inScaled = false;
            mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
        }

        void init(Resources res, boolean isPreview) {
            mResources = res;
            mPreview = isPreview;
            createState();
        }

        public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
            mWorldState.xOffset = xOffset;
        }

        public void resize(int width, int height) {
            mWidth = width;
            mHeight = height;
            updateWorldSize();
        }

        /** Port: the original 480x800 screen space, scaled to cover this screen. */
        private void updateWorldSize() {
            final int shortSide = Math.min(mWidth, mHeight);
            final int longSide = Math.max(mWidth, mHeight);
            mScale = Math.max(2f * shortSide / BG_WIDTH, (float) longSide / BG_HEIGHT);

            mWorldState.width = Math.round(mWidth / mScale);
            mWorldState.height = Math.round(mHeight / mScale);
            mWorldState.rotate = mWidth > mHeight ? 1 : 0;

            // Center the background, in whole cells so it stays aligned with the pulses
            final int visibleW = mWorldState.rotate != 0
                    ? mWorldState.width : mWorldState.width * 2;
            mCropX = Math.max(0, (BG_WIDTH - visibleW) / 2) / Script.PULSE_SIZE
                    * Script.PULSE_SIZE;
            mCropY = Math.max(0, (BG_HEIGHT - mWorldState.height) / 2) / Script.PULSE_SIZE
                    * Script.PULSE_SIZE;

            mPvOrthoAlloc = setupOrthoWindow(mWorldState.width, mWorldState.height);
        }

        private void createState() {
            mWorldState = new WorldState();
            mWorldState.isPreview = mPreview ? 1 : 0;

            // Port: R.integer.nexus_mode was a device overlay (1 = "sholes" red); not set.
            mWorldState.mode = 0; // standard nexus mode

            updateWorldSize();

            mCommand = new CommandState();
            mCommand.x = -1;
            mCommand.y = -1;
            mCommand.command = 0;
        }

        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                boolean resultRequested) {
            // Port: screen px to the original pixels
            x = (int) (x / mScale);
            y = (int) (y / mScale);

            final int dw = mWorldState.width;
            final int bw = dw * 2; // was 960, the background width on the original screen
            if (mWorldState.rotate == 0) {
                // nexus.rs ignores the xOffset when rotated; we shall endeavor to do so as well
                x = (int) (x + mWorldState.xOffset * (bw-dw));
            }

            if ("android.wallpaper.tap".equals(action)) {
                sendCommand(1, x, y);
            } else if ("android.home.drop".equals(action)) {
                sendCommand(2, x, y);
            }
            return null;
        }

        private void sendCommand(int command, int x, int y) {
            mCommand.x = x;
            mCommand.y = y;
            mCommand.command = command;
        }

        // ---------------------------------------------------------------- GL (render thread)

        private int mTBackground, mTPulse, mTPulseVert, mTGlow;
        private int mProgramRgba;   // PFTexture: MODULATE RGBA
        private int mProgramRgb;    // PFTextureBG: MODULATE RGB
        private float[] mPvOrthoAlloc;
        private final float[] mModel = new float[16];
        private final float[] mMvp = new float[16];
        private final float[] mColor = new float[] { 1, 1, 1, 1 };
        private final FloatBuffer mQuad = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private int mProgram;
        private final Script mScript = new Script();

        void initGl() {
            mTBackground = loadTextureARGB(mCrespo
                    ? R.drawable.pyramid_background_crespo : R.drawable.pyramid_background);
            mTPulse = loadTextureARGB(R.drawable.pulse);
            mTPulseVert = loadTextureARGB(R.drawable.pulse_vert);
            mTGlow = loadTextureARGB(R.drawable.glow);

            final String vertex =
                    "uniform mat4 UNI_MVP;\n" +
                    "uniform vec4 UNI_Color;\n" +
                    "attribute vec2 ATTRIB_position;\n" +
                    "attribute vec2 ATTRIB_texture0;\n" +
                    "varying vec4 varColor;\n" +
                    "varying vec2 varTex0;\n" +
                    "void main() {\n" +
                    "  gl_Position = UNI_MVP * vec4(ATTRIB_position, 0.0, 1.0);\n" +
                    "  varColor = UNI_Color;\n" +
                    "  varTex0 = ATTRIB_texture0;\n" +
                    "}\n";
            final String rgba =
                    "precision mediump float;\n" +
                    "uniform sampler2D UNI_Tex0;\n" +
                    "varying vec4 varColor;\n" +
                    "varying vec2 varTex0;\n" +
                    "void main() {\n" +
                    "  vec4 col = varColor;\n" +
                    "  col.rgba *= texture2D(UNI_Tex0, varTex0).rgba;\n" +
                    "  gl_FragColor = col;\n" +
                    "}\n";
            final String rgb =
                    "precision mediump float;\n" +
                    "uniform sampler2D UNI_Tex0;\n" +
                    "varying vec4 varColor;\n" +
                    "varying vec2 varTex0;\n" +
                    "void main() {\n" +
                    "  vec4 col = varColor;\n" +
                    "  col.rgb *= texture2D(UNI_Tex0, varTex0).rgb;\n" +
                    "  gl_FragColor = col;\n" +
                    "}\n";
            final int v = compile(GLES20.GL_VERTEX_SHADER, vertex);
            mProgramRgba = link(v, compile(GLES20.GL_FRAGMENT_SHADER, rgba));
            mProgramRgb = link(v, compile(GLES20.GL_FRAGMENT_SHADER, rgb));

            mScript.initPulses();
        }

        void destroyGl() {
            GLES20.glDeleteTextures(4, new int[] { mTBackground, mTPulse, mTPulseVert, mTGlow }, 0);
        }

        /** Root script frame; returns the delay to the next frame in ms. */
        int draw() {
            // script.setClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glViewport(0, 0, mWidth, mHeight);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);   // DepthFunc.ALWAYS
            GLES20.glDisable(GLES20.GL_DITHER);
            GLES20.glEnable(GLES20.GL_BLEND);
            bindProgramFragmentStore(false);           // #pragma stateStore(PSSolid)
            return mScript.main();
        }

        /** ProgramVertex.MatrixAllocation.setupOrthoWindow(w, h) */
        private static float[] setupOrthoWindow(int w, int h) {
            final float[] m = new float[16];
            Matrix.orthoM(m, 0, 0, w, h, 0, -1, 1);
            return m;
        }

        /** PSSolid: ONE, ONE; PSBlend: SRC_ALPHA, ONE */
        void bindProgramFragmentStore(boolean blend) {
            if (blend) {
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
            } else {
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            }
        }

        void bindProgramFragment(boolean background) {
            mProgram = background ? mProgramRgb : mProgramRgba;
        }

        void color(float r, float g, float b, float a) {
            mColor[0] = r;
            mColor[1] = g;
            mColor[2] = b;
            mColor[3] = a;
        }

        void vpLoadModelMatrix(float[] matrix) {
            System.arraycopy(matrix, 0, mModel, 0, 16);
        }

        /** drawRect(): the quad gets texture (0,1) at (x1,y2) ... (0,0) at (x1,y1). */
        void drawRect(int texture, float x1, float y1, float x2, float y2) {
            mQuad.clear();
            mQuad.put(new float[] {
                x1, y2, 0, 1,
                x2, y2, 1, 1,
                x2, y1, 1, 0,
                x1, y1, 0, 0,
            });
            final int program = mProgram;
            GLES20.glUseProgram(program);
            Matrix.multiplyMM(mMvp, 0, mPvOrthoAlloc, 0, mModel, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "UNI_MVP"), 1, false,
                    mMvp, 0);
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "UNI_Color"), 1, mColor, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "UNI_Tex0"), 0);
            final int pos = GLES20.glGetAttribLocation(program, "ATTRIB_position");
            final int tex = GLES20.glGetAttribLocation(program, "ATTRIB_texture0");
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glEnableVertexAttribArray(tex);
            mQuad.position(0);
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, mQuad);
            mQuad.position(2);
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, mQuad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
            GLES20.glDisableVertexAttribArray(pos);
            GLES20.glDisableVertexAttribArray(tex);
        }

        /** loadTextureARGB(): sampler LINEAR / CLAMP */
        private int loadTextureARGB(int id) {
            final Bitmap b = BitmapFactory.decodeResource(mResources, id, mOptionsARGB);
            final int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            b.recycle();
            return tex[0];
        }

        private static int compile(int type, String source) {
            final int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            final int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        private static int link(int vertex, int fragment) {
            final int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            return program;
        }

        /** Port of res/raw/nexus.rs */
        final class Script {
            static final int MAX_PULSES = 20;
            static final int MAX_EXTRAS = 40;
            static final int PULSE_SIZE = 14; // Size in pixels of a cell
            static final int HALF_PULSE_SIZE = 7;
            static final int GLOW_SIZE = 64; // Size of the leading glow in pixels
            static final int HALF_GLOW_SIZE = 32;
            static final float SPEED = 0.2f; // (200 / 1000) Pixels per ms
            static final float SPEED_VARIANCE = 0.3f;
            static final int PULSE_NORMAL = 0;
            static final int PULSE_EXTRA = 1;
            static final int TRAIL_SIZE = 40; // Number of cells in a trail
            static final int MAX_DELAY = 2000; // Delay between a pulse going offscreen and restarting

            final class pulse_s {
                int pulseType;
                float originX;
                float originY;
                int color;
                int startTime;
                float dx;
                float dy;
                int active;
                float scale; // crespo
            }

            final pulse_s[] gPulses = new pulse_s[MAX_PULSES];
            final pulse_s[] gExtras = new pulse_s[MAX_EXTRAS];

            int gNow;

            private final Random mRandom = new Random();

            Script() {
                for (int i = 0; i < MAX_PULSES; i++) gPulses[i] = new pulse_s();
                for (int i = 0; i < MAX_EXTRAS; i++) gExtras[i] = new pulse_s();
            }

            float randf(float max) {
                return mRandom.nextFloat() * max;
            }

            float randf2(float min, float max) {
                return min + mRandom.nextFloat() * (max - min);
            }

            void setColor(int c) {
                if (mWorldState.mode == 1) {
                    // sholes red
                    color(0.9f, 0.1f, 0.1f, 0.8f);
                } else if (c == 0) {
                    // red
                    color(1.0f, 0.0f, 0.0f, 0.8f);
                } else if (c == 1) {
                    // green
                    color(0.0f, 0.8f, 0.0f, 0.8f);
                } else if (c == 2) {
                    // blue
                    color(0.0f, 0.4f, 0.9f, 0.8f);
                } else if (c == 3) {
                    // yellow
                    color(1.0f, 0.8f, 0.0f, 0.8f);
                }
            }

            void initPulse(pulse_s pulse, int pulseType) {
                if (mCrespo) {
                    initPulseCrespo(pulse, pulseType);
                    return;
                }
                final WorldState State = mWorldState;
                if (randf(1) > 0.5f) {
                    pulse.originX = (int)randf(State.width * 2 / PULSE_SIZE) * PULSE_SIZE;
                    pulse.dx = 0;
                    if (randf(1) > 0.5f) {
                        // Top
                        pulse.originY = 0;
                        pulse.dy = randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
                    } else {
                        // Bottom
                        pulse.originY = State.height;
                        pulse.dy = -randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
                    }
                } else {
                    pulse.originY = (int)randf(State.height / PULSE_SIZE) * PULSE_SIZE;
                    pulse.dy = 0;
                    if (randf(1) > 0.5f) {
                        // Left
                        pulse.originX = 0;
                        pulse.dx = randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
                    } else {
                        // Right
                        pulse.originX = State.width * 2;
                        pulse.dx = -randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
                    }
                }
                pulse.startTime = gNow + (int)randf(MAX_DELAY);

                pulse.color = (int)randf(4.0f);

                pulse.pulseType = pulseType;
                if (pulseType == PULSE_EXTRA) {
                    pulse.active = 0;
                } else {
                    pulse.active = 1;
                }
            }

            /** crespo nexus.rs initPulse() */
            void initPulseCrespo(pulse_s pulse, int pulseType) {
                final WorldState State = mWorldState;
                float scale = randf2(0.7f, 1.7f);
                pulse.scale = scale;
                if (randf(1) > 0.5f) {
                    pulse.originX = (int)randf(State.width * 2 / PULSE_SIZE) * PULSE_SIZE;
                    pulse.dx = 0;
                    if (randf(1) > 0.5f) {
                        // Top
                        pulse.originY = 0;
                        pulse.dy = scale;
                    } else {
                        // Bottom
                        pulse.originY = State.height / scale;
                        pulse.dy = -scale;
                    }
                } else {
                    pulse.originY = (int)randf(State.height / PULSE_SIZE) * PULSE_SIZE;
                    pulse.dy = 0;
                    if (randf(1) > 0.5f) {
                        // Left
                        pulse.originX = 0;
                        pulse.dx = scale;
                    } else {
                        // Right
                        pulse.originX = State.width * 2 / scale;
                        pulse.dx = -scale;
                    }
                }
                pulse.startTime = gNow + (int)randf(MAX_DELAY);

                pulse.color = (int)randf(4.0f);

                pulse.pulseType = pulseType;
                if (pulseType == PULSE_EXTRA) {
                    pulse.active = 0;
                } else {
                    pulse.active = 1;
                }
            }

            void initPulses() {
                gNow = (int) SystemClock.uptimeMillis();
                int i;
                for (i=0; i<MAX_PULSES; i++) {
                    initPulse(gPulses[i], PULSE_NORMAL);
                }
                for (i=0; i<MAX_EXTRAS; i++) {
                    pulse_s p = gExtras[i];
                    p.pulseType = PULSE_EXTRA;
                    p.active = 0;
                }
            }

            void drawBackground(int width, int height) {
                bindProgramFragment(true);
                color(1.0f, 1.0f, 1.0f, 1.0f);
                // Port: was drawRect(0, 0, width*2, height) (height*2, width when rotated),
                // i.e. the 960x800 texture 1:1 on the original screen. Drawn 1:1 in the
                // original pixels, centered.
                drawRect(mTBackground, -mCropX, -mCropY, BG_WIDTH - mCropX, BG_HEIGHT - mCropY);
            }

            void drawPulses(pulse_s[] pulseSet, int setSize) {
                final WorldState State = mWorldState;
                bindProgramFragment(false);
                bindProgramFragmentStore(true);

                int i;
                for (i=0; i<setSize; i++) {
                    pulse_s p = pulseSet[i];

                    int delta = gNow - p.startTime;

                    if (p.active != 0 && delta >= 0) {

                        if (mCrespo) {
                            float[] modelMatrix = new float[16];
                            Matrix.setIdentityM(modelMatrix, 0);
                            if (State.rotate != 0) {
                                // XXX: HAX: do not slide display in landscape
                            } else {
                                 Matrix.translateM(modelMatrix, 0,
                                         -(State.xOffset * State.width), 0, 0);
                            }
                            Matrix.scaleM(modelMatrix, 0, p.scale, p.scale, 1.0f);
                            vpLoadModelMatrix(modelMatrix);
                        }

                        float x = p.originX + (p.dx * SPEED * delta);
                        float y = p.originY + (p.dy * SPEED * delta);

                        if (p.dx < 0) {
                            float xx = x + (TRAIL_SIZE * PULSE_SIZE);
                            if (xx <= 0) {
                                initPulse(p, p.pulseType);
                            } else {
                                setColor(p.color);
                                drawRect(mTPulse, x, y, xx, y + PULSE_SIZE);
                                drawRect(mTGlow, x + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    y + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    x + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                    y + HALF_PULSE_SIZE + HALF_GLOW_SIZE);
                            }
                        } else if (p.dx > 0) {
                            x += PULSE_SIZE; // need to start on the other side of this cell
                            float xx = x - (TRAIL_SIZE * PULSE_SIZE);
                            if (xx >= State.width * 2) {
                               initPulse(p, p.pulseType);
                            } else {
                                setColor(p.color);
                                drawRect(mTPulse, x, y, xx, y + PULSE_SIZE);
                                drawRect(mTGlow, x - HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    y + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    x - HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                    y + HALF_PULSE_SIZE + HALF_GLOW_SIZE);
                            }
                        } else if (p.dy < 0) {
                            float yy = y + (TRAIL_SIZE * PULSE_SIZE);
                            if (yy <= 0) {
                               initPulse(p, p.pulseType);
                            } else {
                                setColor(p.color);
                                drawRect(mTPulseVert, x, yy, x + PULSE_SIZE, y);
                                drawRect(mTGlow, x + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    y + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    x + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                    y + HALF_PULSE_SIZE + HALF_GLOW_SIZE);
                            }
                        } else if (p.dy > 0) {
                            y += PULSE_SIZE; // need to start on the other side of this cell
                            float yy = y - (TRAIL_SIZE * PULSE_SIZE);
                            if (yy >= State.height) {
                               initPulse(p, p.pulseType);
                            } else {
                                setColor(p.color);
                                drawRect(mTPulseVert, x, yy, x + PULSE_SIZE, y);
                                drawRect(mTGlow, x + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    y - HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                    x + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                    y - HALF_PULSE_SIZE + HALF_GLOW_SIZE);
                            }
                        }
                    }
                }
            }

            void addTap(int x, int y) {
                int i;
                int count = 0;
                int color = (int)randf(4.0f);
                float scale = mCrespo ? randf2(0.9f, 1.9f) : 1.5f; // crespo
                x = (int)(x / PULSE_SIZE) * PULSE_SIZE;
                y = (int)(y / PULSE_SIZE) * PULSE_SIZE;
                for (i=0; i<MAX_EXTRAS; i++) {
                    pulse_s p = gExtras[i];
                    if (p.active == 0) {
                        if (mCrespo) {
                            p.originX = x/scale;
                            p.originY = y/scale;
                            p.scale = scale;
                        } else {
                            p.originX = x;
                            p.originY = y;
                        }

                        // stock: 1.5f; crespo: scale
                        if (count == 0) {
                            p.dx = scale;
                            p.dy = 0.0f;
                        } else if (count == 1) {
                            p.dx = -scale;
                            p.dy = 0.0f;
                        } else if (count == 2) {
                            p.dx = 0.0f;
                            p.dy = scale;
                        } else if (count == 3) {
                            p.dx = 0.0f;
                            p.dy = -scale;
                        }

                        p.active = 1;
                        p.color = color;
                        color++;
                        if (color >= 4) {
                            color = 0;
                        }
                        p.startTime = gNow;
                        count++;
                        if (count == 4) {
                            break;
                        }
                    }
                }
            }

            int main() {
                final WorldState State = mWorldState;
                final CommandState Command = mCommand;

                gNow = (int) SystemClock.uptimeMillis();

                if (Command.command != 0) {
                    Command.command = 0;
                    addTap(Command.x, Command.y);
                }

                int width = State.width;
                int height = State.height;

                float[] matrix = new float[16];
                Matrix.setIdentityM(matrix, 0);
                if (State.rotate != 0) {
                    // XXX: HAX: do not slide display in landscape
                } else {
                    Matrix.translateM(matrix, 0, -(State.xOffset * width), 0, 0);
                }

                vpLoadModelMatrix(matrix);

                drawBackground(width, height);

                drawPulses(gPulses, MAX_PULSES);
                drawPulses(gExtras, MAX_EXTRAS);

                bindProgramFragmentStore(false);
                return 45;
            }
        }
    }
}
