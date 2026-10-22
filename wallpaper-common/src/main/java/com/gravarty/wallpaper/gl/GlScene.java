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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Port: RenderScriptScene, plus the Gingerbread RenderScript graphics runtime the wallpaper
 * scripts use (frameworks/base/libs/rs and graphics/java/android/renderscript,
 * android-2.3.7_r1) on OpenGL ES 2.0:
 *  - ProgramVertex / ProgramFragment build the same GLSL as rsProgramVertex.cpp and
 *    rsProgramFragment.cpp (fixed function emulation, or the user shader with the
 *    declarations RS put in front of it),
 *  - ProgramStore, Sampler, MatrixAllocation, SimpleMesh and texture Allocations,
 *  - the draw functions of rsScriptC_Lib.cpp (drawQuadTexCoords, drawRect,
 *    drawSimpleMeshRange, color, vpLoadModelMatrix, bindTexture, ...).
 * Like the RS context there is no depth buffer, so depth settings have no effect.
 *
 * The scripts (res/raw/*.rs) are ported to Java in the subclasses: createScript() sets up
 * what the Java side created and the script's init(), main() is the root script.
 * Everything but start()/stop() runs on the render thread.
 */
public abstract class GlScene {
    private static final String TAG = "GlScene";

    protected int mWidth;
    protected int mHeight;
    protected boolean mPreview;
    protected Resources mResources;
    protected Context mContext;

    GlThread mThread;

    public GlScene(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public void init(Context context, boolean isPreview) {
        mContext = context;
        mResources = context.getResources();
        mPreview = isPreview;
    }

    public boolean isPreview() {
        return mPreview;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public Resources getResources() {
        return mResources;
    }

    /** Main thread: the wallpaper became visible (the root script is bound). */
    public void start() {
    }

    /** Main thread: the wallpaper became invisible (the root script is unbound). */
    public void stop() {
    }

    /** Runs r on the render thread before the next frame, like Allocation.data(). */
    public final void queue(Runnable r) {
        final GlThread thread = mThread;
        if (thread != null) {
            thread.queue(r);
        }
    }

    public void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public void setOffset(float xOffset, float yOffset, float xStep, float yStep,
            int xPixels, int yPixels) {
    }

    public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
            boolean resultRequested) {
        return null;
    }

    public void onTouchEvent(MotionEvent event) {
    }

    /** The Java side's createScript(): programs, textures, meshes and the script's init(). */
    protected abstract void createScript();

    /** The root script; returns the delay to the next frame in ms. */
    protected abstract int main();

    // ------------------------------------------------------------------ the RS context

    private final float[] mClearColor = { 0.0f, 0.0f, 0.0f, 1.0f };

    private ProgramVertex mRootVertex;
    private ProgramFragment mRootFragment;
    private ProgramStore mRootStore;

    private ProgramVertex mDefaultVertex;
    private ProgramFragment mDefaultFragment;
    private ProgramStore mDefaultStore;
    private MatrixAllocation mDefaultAlloc;

    private ProgramVertex mVertex;
    private ProgramFragment mFragment;
    private ProgramStore mStore;
    private ProgramStore mLastStore;
    private final float[] mColor = { 1.f, 1.f, 1.f, 1.f };

    private final ArrayList<Integer> mEnabledAttribs = new ArrayList<Integer>();
    private final HashMap<Long, Linked> mShaderCache = new HashMap<Long, Linked>();
    private int mNextId = 1;

    /** Port: screen pixels per pixel of the original screen, for point sizes and lines. */
    protected float mPixelScale = 1.0f;

    final void initGl() {
        mDefaultAlloc = new MatrixAllocation();
        mDefaultAlloc.setupOrthoWindow(mWidth, mHeight);
        mDefaultVertex = new ProgramVertex();
        mDefaultVertex.bindAllocation(mDefaultAlloc);
        mDefaultFragment = new ProgramFragment(0, 0, false);
        mDefaultStore = new ProgramStore();
        createScript();
    }

    void destroyGl() {
        for (Linked l : mShaderCache.values()) {
            GLES20.glDeleteProgram(l.program);
        }
        mShaderCache.clear();
    }

    /** One frame of the RS context: clear, the root script's #pragma state, the script. */
    final int frame() {
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(mClearColor[0], mClearColor[1], mClearColor[2], mClearColor[3]);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        mVertex = mRootVertex != null ? mRootVertex : mDefaultVertex;
        mFragment = mRootFragment != null ? mRootFragment : mDefaultFragment;
        mStore = mRootStore != null ? mRootStore : mDefaultStore;
        mLastStore = null;
        // Port: 60 frames per second at most, as on the screens these were made for
        // (scripts returning 1 would otherwise run at 120 Hz and animate twice as fast)
        return Math.max(main(), 16);
    }

    /** ScriptC.setClearColor() */
    protected void setClearColor(float r, float g, float b, float a) {
        mClearColor[0] = r;
        mClearColor[1] = g;
        mClearColor[2] = b;
        mClearColor[3] = a;
    }

    /** pfClearColor(): the next frames are cleared with it. */
    protected void pfClearColor(float r, float g, float b, float a) {
        setClearColor(r, g, b, a);
    }

    /** #pragma stateVertex / stateFragment / stateStore of the root script (null: default) */
    protected void setRootState(ProgramVertex pv, ProgramFragment pf, ProgramStore ps) {
        mRootVertex = pv;
        mRootFragment = pf;
        mRootStore = ps;
    }

    protected void bindProgramVertex(ProgramVertex pv) {
        mVertex = pv;
    }

    protected void bindProgramFragment(ProgramFragment pf) {
        mFragment = pf;
    }

    protected void bindProgramFragmentStore(ProgramStore ps) {
        mStore = ps;
    }

    protected void bindProgramStore(ProgramStore ps) {
        mStore = ps;
    }

    protected void bindTexture(ProgramFragment pf, int slot, Texture t) {
        pf.mTexture = t;
    }

    protected void color(float r, float g, float b, float a) {
        mColor[0] = r;
        mColor[1] = g;
        mColor[2] = b;
        mColor[3] = a;
    }

    /** Loads the model matrix of the bound vertex program's MatrixAllocation. */
    protected void vpLoadModelMatrix(float[] m) {
        System.arraycopy(m, 0, mVertex.mAlloc.mModel, 0, 16);
    }

    // ------------------------------------------------------------------ draw functions

    private final FloatBuffer mQuadVtx = ByteBuffer.allocateDirect(12 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer mQuadTex = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    protected void drawQuadTexCoords(float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float x4, float y4, float z4, float u4, float v4) {
        final Linked p = setup();
        mQuadVtx.clear();
        mQuadVtx.put(x1).put(y1).put(z1).put(x2).put(y2).put(z2)
                .put(x3).put(y3).put(z3).put(x4).put(y4).put(z4);
        mQuadVtx.position(0);
        mQuadTex.clear();
        mQuadTex.put(u1).put(v1).put(u2).put(v2).put(u3).put(v3).put(u4).put(v4);
        mQuadTex.position(0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        disableAttribs();
        // VertexArray::setupGL2: user vertex programs take the attributes by slot
        enableAttrib(p.user ? p.attrib(0) : p.attrib("ATTRIB_LegacyPosition"), 3,
                GLES20.GL_FLOAT, false, 12, mQuadVtx);
        enableAttrib(p.user ? p.attrib(1) : p.attrib("ATTRIB_LegacyTexture"), 2,
                GLES20.GL_FLOAT, false, 8, mQuadTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
    }

    protected void drawQuad(float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4) {
        drawQuadTexCoords(x1, y1, z1, 0, 1,
                          x2, y2, z2, 1, 1,
                          x3, y3, z3, 1, 0,
                          x4, y4, z4, 0, 0);
    }

    protected void drawRect(float x1, float y1, float x2, float y2, float z) {
        drawQuad(x1, y2, z,
                 x2, y2, z,
                 x2, y1, z,
                 x1, y1, z);
    }

    protected void drawSimpleMesh(SimpleMesh mesh) {
        drawSimpleMeshRange(mesh, 0, mesh.mIndexCount > 0 ? mesh.mIndexCount : mesh.mCount);
    }

    protected void drawSimpleMeshRange(SimpleMesh mesh, int start, int len) {
        if (len < 1) {
            return;
        }
        final Linked p = setup();
        mesh.uploadCheck();

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.mVbo);
        disableAttribs();
        final Field[] fields = mesh.mFields;
        int slot = 0;
        for (int i = 0; i < fields.length; i++) {
            final Field f = fields[i];
            int loc;
            if (p.user) {
                loc = p.attrib(slot++);
            } else {
                if (f.kind == KIND_USER) {
                    continue;
                }
                loc = p.attrib("ATTRIB_Legacy" + KIND_NAMES[f.kind]);
            }
            if (loc < 0) continue;
            GLES20.glEnableVertexAttribArray(loc);
            GLES20.glVertexAttribPointer(loc, f.size, f.type, f.normalized, mesh.mStride,
                    f.offset);
            mEnabledAttribs.add(loc);
        }

        if (mesh.mIndexCount > 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.mIbo);
            GLES20.glDrawElements(mesh.mPrimitive, len, GLES20.GL_UNSIGNED_SHORT, start * 2);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        } else {
            GLES20.glDrawArrays(mesh.mPrimitive, start, len);
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void disableAttribs() {
        for (int loc : mEnabledAttribs) {
            GLES20.glDisableVertexAttribArray(loc);
        }
        mEnabledAttribs.clear();
    }

    private void enableAttrib(int loc, int size, int type, boolean normalized, int stride,
            java.nio.Buffer data) {
        if (loc < 0) return;
        GLES20.glEnableVertexAttribArray(loc);
        GLES20.glVertexAttribPointer(loc, size, type, normalized, stride, data);
        mEnabledAttribs.add(loc);
    }

    /** rsc->setupCheck(): program, store, vertex and fragment state. */
    private Linked setup() {
        final ProgramVertex pv = mVertex;
        final ProgramFragment pf = mFragment;
        final Linked p = link(pv, pf);
        GLES20.glUseProgram(p.program);

        // ProgramFragmentStore::setupGL2
        final ProgramStore ps = mStore;
        if (ps != mLastStore) {
            mLastStore = ps;
            if (ps.mBlendEnable) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(ps.mBlendSrc, ps.mBlendDst);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); // no depth buffer
            if (ps.mDitherEnable) {
                GLES20.glEnable(GLES20.GL_DITHER);
            } else {
                GLES20.glDisable(GLES20.GL_DITHER);
            }
        }

        // ProgramVertex::setupGL2
        if (!p.user) {
            final int color = p.attrib("ATTRIB_LegacyColor");
            if (color >= 0) {
                GLES20.glVertexAttrib4f(color, mColor[0], mColor[1], mColor[2], mColor[3]);
            }
        }
        final float[] mvp = p.mvp;
        Matrix.multiplyMM(mvp, 0, pv.mAlloc.mProjection, 0, pv.mAlloc.mModel, 0);
        GLES20.glUniformMatrix4fv(p.uMvp, 1, false, mvp, 0);
        if (pv.mConstantNames != null && pv.mConstants != null) {
            final float[] c = pv.mConstants;
            int offset = 0;
            for (int i = 0; i < pv.mConstantNames.length; i++) {
                final int size = pv.mConstantSizes[i];
                final int loc = p.uConstants[i];
                switch (size) {
                    case 1: GLES20.glUniform1f(loc, c[offset]); break;
                    case 2: GLES20.glUniform2f(loc, c[offset], c[offset + 1]); break;
                    case 3: GLES20.glUniform3f(loc, c[offset], c[offset + 1], c[offset + 2]);
                            break;
                    case 4: GLES20.glUniform4f(loc, c[offset], c[offset + 1], c[offset + 2],
                            c[offset + 3]); break;
                }
                offset += size;
            }
        }
        if (p.uPointScale >= 0) {
            GLES20.glUniform1f(p.uPointScale, mPixelScale);
        }

        // ProgramFragment::setupGL2
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (pf.mTextureEnable && pf.mTexture != null) {
            final Texture t = pf.mTexture;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.mId);
            if (pf.mSampler != null) {
                pf.mSampler.setupGL();
            } else {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                        GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                        GLES20.GL_REPEAT);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                        GLES20.GL_REPEAT);
            }
            GLES20.glUniform1i(p.uTex0, 0);
        }
        return p;
    }

    // ------------------------------------------------------------------ shader cache

    private static final class Linked {
        int program;
        boolean user;
        int uMvp;
        int uTex0;
        int uPointScale;
        int[] uConstants;
        int[] userAttribs;
        final HashMap<String, Integer> attribs = new HashMap<String, Integer>();
        final float[] mvp = new float[16];

        int attrib(String name) {
            final Integer loc = attribs.get(name);
            return loc != null ? loc : -1;
        }

        int attrib(int slot) {
            return slot < userAttribs.length ? userAttribs[slot] : -1;
        }
    }

    private Linked link(ProgramVertex pv, ProgramFragment pf) {
        final long key = ((long) pv.mId << 32) | pf.mId;
        Linked l = mShaderCache.get(key);
        if (l != null) {
            return l;
        }
        l = new Linked();
        final int vs = compile(GLES20.GL_VERTEX_SHADER, pv.mShader);
        final int fs = compile(GLES20.GL_FRAGMENT_SHADER, pf.mShader);
        final int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        final int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        l.program = program;
        l.user = pv.mUserShader != null;
        l.uMvp = GLES20.glGetUniformLocation(program, "UNI_MVP");
        l.uTex0 = GLES20.glGetUniformLocation(program, "uni_Tex0");
        l.uPointScale = GLES20.glGetUniformLocation(program, "UNI_PortPixelScale");
        if (pv.mConstantNames != null) {
            l.uConstants = new int[pv.mConstantNames.length];
            for (int i = 0; i < l.uConstants.length; i++) {
                l.uConstants[i] = GLES20.glGetUniformLocation(program,
                        "UNI_" + pv.mConstantNames[i]);
            }
        }
        if (l.user) {
            l.userAttribs = new int[pv.mInputNames.length];
            for (int i = 0; i < l.userAttribs.length; i++) {
                l.userAttribs[i] = GLES20.glGetAttribLocation(program,
                        "ATTRIB_" + pv.mInputNames[i]);
            }
        } else {
            for (String name : new String[] { "ATTRIB_LegacyPosition", "ATTRIB_LegacyColor",
                    "ATTRIB_LegacyNormal", "ATTRIB_LegacyPointSize", "ATTRIB_LegacyTexture" }) {
                l.attribs.put(name, GLES20.glGetAttribLocation(program, name));
            }
        }
        mShaderCache.put(key, l);
        return l;
    }

    private static int compile(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader)
                    + "\n" + source);
        }
        return shader;
    }

    // ------------------------------------------------------------------ programs

    /** ProgramVertex.MatrixAllocation */
    public static final class MatrixAllocation {
        final float[] mModel = new float[16];
        final float[] mProjection = new float[16];

        public MatrixAllocation() {
            RsMatrix.loadIdentity(mModel);
            RsMatrix.loadIdentity(mProjection);
        }

        public void setupOrthoWindow(int w, int h) {
            RsMatrix.loadOrtho(mProjection, 0, w, h, 0, -1, 1);
        }

        public void setupOrthoNormalized(int w, int h) {
            // range -1,1 in the narrow axis.
            if (w > h) {
                final float aspect = ((float) w) / h;
                RsMatrix.loadOrtho(mProjection, -aspect, aspect, -1, 1, -1, 1);
            } else {
                final float aspect = ((float) h) / w;
                RsMatrix.loadOrtho(mProjection, -1, 1, -aspect, aspect, -1, 1);
            }
        }

        public void setupProjectionNormalized(int w, int h) {
            // range -1,1 in the narrow axis at z = 0.
            final float[] m1 = new float[16];
            final float[] m2 = new float[16];

            if (w > h) {
                final float aspect = ((float) w) / h;
                RsMatrix.loadFrustum(m1, -aspect, aspect, -1, 1, 1, 100);
            } else {
                final float aspect = ((float) h) / w;
                RsMatrix.loadFrustum(m1, -1, 1, -aspect, aspect, 1, 100);
            }

            RsMatrix.loadRotate(m2, 180, 0, 1, 0);
            RsMatrix.loadMultiply(m1, m1, m2);

            RsMatrix.loadScale(m2, -2, 2, 1);
            RsMatrix.loadMultiply(m1, m1, m2);

            RsMatrix.loadTranslate(m2, 0, 0, 2);
            RsMatrix.loadMultiply(m1, m1, m2);

            System.arraycopy(m1, 0, mProjection, 0, 16);
        }
    }

    /** ProgramVertex: the fixed function one of ProgramVertex.Builder, or a user shader. */
    public final class ProgramVertex {
        final int mId = mNextId++;
        final String mUserShader;
        final String[] mInputNames;
        final String[] mConstantNames;
        final int[] mConstantSizes;
        final String mShader;
        MatrixAllocation mAlloc;
        float[] mConstants;

        /** ProgramVertex.Builder(rs, null, null).create() */
        public ProgramVertex() {
            this(null, null, null);
        }

        /**
         * ProgramVertex.ShaderBuilder: the shader, its inputs (the vertex element's fields
         * as name, vector size pairs) and constants (the constant element's fields as name,
         * vector size pairs, or null).
         */
        public ProgramVertex(String shader, Object[] inputs, Object[] constants) {
            mUserShader = shader;
            mInputNames = names(inputs);
            mConstantNames = names(constants);
            mConstantSizes = sizes(constants);

            // ProgramVertex::createShader()
            final StringBuilder s = new StringBuilder();
            s.append("varying vec4 varColor;\n");
            s.append("varying vec4 varTex0;\n");
            if (shader != null) {
                s.append("uniform mat4 UNI_MVP;\n");
                if (mConstantNames != null) {
                    for (int i = 0; i < mConstantNames.length; i++) {
                        s.append("uniform ").append(VEC[mConstantSizes[i]])
                                .append(" UNI_").append(mConstantNames[i]).append(";\n");
                    }
                }
                final int[] inputSizes = sizes(inputs);
                for (int i = 0; i < mInputNames.length; i++) {
                    s.append("attribute ").append(VEC[inputSizes[i]])
                            .append(" ATTRIB_").append(mInputNames[i]).append(";\n");
                }
                // Port: screen pixels per original pixel, for gl_PointSize
                s.append("uniform float UNI_PortPixelScale;\n");
                s.append(shader);
            } else {
                s.append("attribute vec4 ATTRIB_LegacyPosition;\n");
                s.append("attribute vec4 ATTRIB_LegacyColor;\n");
                s.append("attribute vec3 ATTRIB_LegacyNormal;\n");
                s.append("attribute float ATTRIB_LegacyPointSize;\n");
                s.append("attribute vec4 ATTRIB_LegacyTexture;\n");
                s.append("uniform mat4 UNI_MVP;\n");
                s.append("void main() {\n");
                s.append("  gl_Position = UNI_MVP * ATTRIB_LegacyPosition;\n");
                s.append("  gl_PointSize = ATTRIB_LegacyPointSize;\n");
                s.append("  varColor = ATTRIB_LegacyColor;\n");
                s.append("  varTex0 = ATTRIB_LegacyTexture;\n");
                s.append("}\n");
            }
            mShader = s.toString();
        }

        public void bindAllocation(MatrixAllocation alloc) {
            mAlloc = alloc;
        }

        /** bindConstants(): the constant fields' values, one after the other. */
        public void bindConstants(float[] constants) {
            mConstants = constants;
        }
    }

    private static final String[] VEC = { "", "float", "vec2", "vec3", "vec4" };

    private static String[] names(Object[] fields) {
        if (fields == null) return null;
        final String[] names = new String[fields.length / 2];
        for (int i = 0; i < names.length; i++) {
            names[i] = (String) fields[i * 2];
        }
        return names;
    }

    private static int[] sizes(Object[] fields) {
        if (fields == null) return null;
        final int[] sizes = new int[fields.length / 2];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = (Integer) fields[i * 2 + 1];
        }
        return sizes;
    }

    // ProgramFragment.Builder.EnvMode
    public static final int REPLACE = 1;
    public static final int MODULATE = 2;
    public static final int DECAL = 3;
    // ProgramFragment.Builder.Format
    public static final int ALPHA = 1;
    public static final int LUMINANCE_ALPHA = 2;
    public static final int RGB = 3;
    public static final int RGBA = 4;

    /** ProgramFragment.Builder with one texture (or none), as rsProgramFragment.cpp. */
    public final class ProgramFragment {
        final int mId = mNextId++;
        final boolean mTextureEnable;
        final String mShader;
        Texture mTexture;
        Sampler mSampler;

        /** envMode 0: no texture */
        public ProgramFragment(int envMode, int format, boolean pointSpriteTexCoord) {
            mTextureEnable = envMode != 0;
            final StringBuilder s = new StringBuilder();
            s.append("precision mediump float;\n");
            s.append("varying vec4 varColor;\n");
            s.append("varying vec4 varTex0;\n");
            if (mTextureEnable) {
                s.append("uniform sampler2D uni_Tex0;\n");
            }
            s.append("void main() {\n");
            s.append("  vec4 col = varColor;\n");
            if (mTextureEnable) {
                if (pointSpriteTexCoord) {
                    s.append("  vec2 t0 = gl_PointCoord;\n");
                } else {
                    s.append("  vec2 t0 = varTex0.xy;\n");
                }
                switch (envMode) {
                    case REPLACE:
                        switch (format) {
                            case ALPHA:
                                s.append("  col.a = texture2D(uni_Tex0, t0).a;\n");
                                break;
                            case LUMINANCE_ALPHA:
                                s.append("  col.rgba = texture2D(uni_Tex0, t0).rgba;\n");
                                break;
                            case RGB:
                                s.append("  col.rgb = texture2D(uni_Tex0, t0).rgb;\n");
                                break;
                            case RGBA:
                                s.append("  col.rgba = texture2D(uni_Tex0, t0).rgba;\n");
                                break;
                        }
                        break;
                    case MODULATE:
                        switch (format) {
                            case ALPHA:
                                s.append("  col.a *= texture2D(uni_Tex0, t0).a;\n");
                                break;
                            case LUMINANCE_ALPHA:
                                s.append("  col.rgba *= texture2D(uni_Tex0, t0).rgba;\n");
                                break;
                            case RGB:
                                s.append("  col.rgb *= texture2D(uni_Tex0, t0).rgb;\n");
                                break;
                            case RGBA:
                                s.append("  col.rgba *= texture2D(uni_Tex0, t0).rgba;\n");
                                break;
                        }
                        break;
                    case DECAL:
                        s.append("  col = texture2D(uni_Tex0, t0);\n");
                        break;
                }
            }
            s.append("  gl_FragColor = col;\n");
            s.append("}\n");
            mShader = s.toString();
        }

        public void bindSampler(Sampler sampler, int slot) {
            mSampler = sampler;
        }
    }

    // ProgramStore.BlendSrcFunc / BlendDstFunc
    public static final int ZERO = GLES20.GL_ZERO;
    public static final int ONE = GLES20.GL_ONE;
    public static final int SRC_ALPHA = GLES20.GL_SRC_ALPHA;
    public static final int ONE_MINUS_SRC_ALPHA = GLES20.GL_ONE_MINUS_SRC_ALPHA;

    /** ProgramStore (blending and dithering; depth has no buffer to work on) */
    public static final class ProgramStore {
        boolean mBlendEnable;
        int mBlendSrc = GLES20.GL_ONE;
        int mBlendDst = GLES20.GL_ZERO;
        boolean mDitherEnable = true;

        public ProgramStore() {
        }

        public ProgramStore(int src, int dst, boolean dither) {
            setBlendFunc(src, dst);
            mDitherEnable = dither;
        }

        public void setBlendFunc(int src, int dst) {
            mBlendSrc = src;
            mBlendDst = dst;
            mBlendEnable = !(src == GLES20.GL_ONE && dst == GLES20.GL_ZERO);
        }

        public void setDitherEnable(boolean enable) {
            mDitherEnable = enable;
        }
    }

    // Sampler.Value
    public static final int NEAREST = GLES20.GL_NEAREST;
    public static final int LINEAR = GLES20.GL_LINEAR;
    public static final int LINEAR_MIP_LINEAR = GLES20.GL_LINEAR_MIPMAP_LINEAR;
    public static final int WRAP = GLES20.GL_REPEAT;
    public static final int CLAMP = GLES20.GL_CLAMP_TO_EDGE;

    /** Sampler */
    public static final class Sampler {
        final int mMin, mMag, mWrapS, mWrapT;

        public Sampler(int min, int mag, int wrapS, int wrapT) {
            mMin = min;
            mMag = mag;
            mWrapS = wrapS;
            mWrapT = wrapT;
        }

        void setupGL() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mMin);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mMag);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, mWrapS);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, mWrapT);
        }
    }

    // ------------------------------------------------------------------ allocations

    /** A texture Allocation (uploadToTexture done) */
    public static final class Texture {
        int mId;
        int mWidth;
        int mHeight;
    }

    private Texture upload(Bitmap b, boolean genMips) {
        final Texture t = new Texture();
        final int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        t.mId = id[0];
        t.mWidth = b.getWidth();
        t.mHeight = b.getHeight();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.mId);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        if (genMips) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        }
        return t;
    }

    private Bitmap decode(int id, Bitmap.Config config) {
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPreferredConfig = config;
        return BitmapFactory.decodeResource(mResources, id, opts);
    }

    /** Allocation.createFromBitmapResource(rs, res, id, RGB_565 or RGBA_8888, genMips) */
    protected Texture createFromBitmapResource(int id, boolean rgba, boolean genMips) {
        final Bitmap b = decode(id, rgba ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        final Texture t = upload(b, genMips);
        b.recycle();
        return t;
    }

    /** Allocation.createFromBitmap(rs, b, RGBA_8888, genMips) */
    protected Texture createFromBitmap(Bitmap b, boolean genMips) {
        return upload(b, genMips);
    }

    /**
     * Allocation.createFromBitmapResourceBoxed(): the bitmap centered in a texture of the
     * next power of two sizes (rsi_AllocationCreateFromBitmapBoxed).
     */
    protected Texture createFromBitmapResourceBoxed(int id, boolean rgba, boolean genMips) {
        final Bitmap b = decode(id, rgba ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        final int w = b.getWidth();
        final int h = b.getHeight();
        final int w2 = higherPow2(w);
        final int h2 = higherPow2(h);
        if (w2 == w && h2 == h) {
            final Texture t = upload(b, genMips);
            b.recycle();
            return t;
        }
        final Bitmap boxed = Bitmap.createBitmap(w2, h2, rgba ? Bitmap.Config.ARGB_8888
                : Bitmap.Config.RGB_565);
        final Canvas c = new Canvas(boxed);
        c.drawBitmap(b, (w2 - w) >> 1, (h2 - h) >> 1, null);
        b.recycle();
        final Texture t = upload(boxed, genMips);
        boxed.recycle();
        return t;
    }

    private static int higherPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    /** A texture of A_8 levels (Type with Dimension.LOD), one byte array per level. */
    protected Texture createAlpha(int width, int height, byte[]... levels) {
        final Texture t = new Texture();
        final int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        t.mId = id[0];
        t.mWidth = width;
        t.mHeight = height;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.mId);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        int w = width, h = height;
        for (int lod = 0; lod < levels.length; lod++) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, lod, GLES20.GL_ALPHA, w, h, 0,
                    GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(levels[lod]));
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return t;
    }

    /** An RGBA_8888 texture Allocation of w x h, filled with uploadToTexture(abgr). */
    protected Texture createRgba(int width, int height) {
        final Texture t = new Texture();
        final int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        t.mId = id[0];
        t.mWidth = width;
        t.mHeight = height;
        return t;
    }

    /** uploadToTexture() of RGBA_8888 data, ints as in memory (0xAABBGGRR) */
    protected void uploadToTexture(Texture t, int[] abgr) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(abgr.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.asIntBuffer().put(abgr);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.mId);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, t.mWidth, t.mHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
    }

    // Element.DataKind
    public static final int KIND_USER = 0;
    public static final int KIND_COLOR = 1;
    public static final int KIND_POSITION = 2;
    public static final int KIND_TEXTURE = 3;
    public static final int KIND_NORMAL = 4;
    public static final int KIND_POINT_SIZE = 5;
    private static final String[] KIND_NAMES = {
        "User", "Color", "Position", "Texture", "Normal", "PointSize"
    };

    /** A field of a vertex Element */
    public static final class Field {
        final String name;
        final int kind;
        final int size;
        final int type;
        final boolean normalized;
        int offset;

        /** float32 with size components */
        public Field(String name, int kind, int size) {
            this(name, kind, size, GLES20.GL_FLOAT);
        }

        /** type GL_FLOAT or GL_UNSIGNED_BYTE (normalized, as Type::makeGLComponents) */
        public Field(String name, int kind, int size, int type) {
            this.name = name;
            this.kind = kind;
            this.size = size;
            this.type = type;
            this.normalized = type != GLES20.GL_FLOAT;
        }

        int bytes() {
            return type == GLES20.GL_FLOAT ? size * 4 : size;
        }
    }

    // Primitive
    public static final int POINT = GLES20.GL_POINTS;
    public static final int LINE = GLES20.GL_LINES;
    public static final int TRIANGLE = GLES20.GL_TRIANGLES;

    /**
     * SimpleMesh with one vertex Allocation (and an index Allocation). The vertex data is
     * the Allocation the script and Java write to: floats() / ints() view the same memory.
     * uploadToBufferObject() makes the next draw upload it, like Allocation.uploadCheck().
     */
    public static final class SimpleMesh {
        final Field[] mFields;
        final int mStride;
        final int mCount;
        final int mPrimitive;
        final ByteBuffer mData;
        int mIndexCount;
        ShortBuffer mIndices;
        int mVbo;
        int mIbo;
        boolean mDirty = true;
        boolean mIndicesDirty;

        public SimpleMesh(Field[] fields, int count, int indexCount, int primitive) {
            mFields = fields;
            int offset = 0;
            for (Field f : fields) {
                f.offset = offset;
                offset += f.bytes();
            }
            mStride = offset;
            mCount = count;
            mPrimitive = primitive;
            mData = ByteBuffer.allocateDirect(count * mStride).order(ByteOrder.nativeOrder());
            mIndexCount = indexCount;
            if (indexCount > 0) {
                mIndices = ByteBuffer.allocateDirect(indexCount * 2)
                        .order(ByteOrder.nativeOrder()).asShortBuffer();
            }
        }

        public ByteBuffer data() {
            return mData;
        }

        public FloatBuffer floats() {
            return mData.asFloatBuffer();
        }

        public IntBuffer ints() {
            return mData.asIntBuffer();
        }

        public int stride() {
            return mStride;
        }

        /** Allocation.data(float[]) of the vertex Allocation */
        public void data(float[] d) {
            final FloatBuffer f = mData.asFloatBuffer();
            f.put(d, 0, Math.min(d.length, f.capacity()));
            mDirty = true;
        }

        /** the index Allocation's data(short[]) */
        public void indices(short[] idx) {
            mIndices.clear();
            mIndices.put(idx);
            mIndices.position(0);
            mIndicesDirty = true;
        }

        public void uploadToBufferObject() {
            mDirty = true;
        }

        void uploadCheck() {
            if (mVbo == 0) {
                final int[] ids = new int[2];
                GLES20.glGenBuffers(2, ids, 0);
                mVbo = ids[0];
                mIbo = ids[1];
            }
            if (mDirty) {
                mDirty = false;
                mData.position(0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mCount * mStride, mData,
                        GLES20.GL_DYNAMIC_DRAW);
            }
            if (mIndicesDirty && mIndices != null) {
                mIndicesDirty = false;
                mIndices.position(0);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mIbo);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mIndexCount * 2, mIndices,
                        GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            }
        }
    }

    // ------------------------------------------------------------------ Port helpers

    /**
     * Port: the scripts work in pixels of the original 480 x 800 screens. This is the size
     * of this screen in those pixels: scaled so its short side is 480 (the original width),
     * and mPixelScale set to the screen pixels per original pixel.
     */
    protected int[] virtualSize(int width, int height) {
        mPixelScale = Math.min(width, height) / 480.0f;
        return new int[] { Math.round(width / mPixelScale), Math.round(height / mPixelScale) };
    }

    /** Port: line width for LINE meshes, one original pixel. */
    protected void lineWidth() {
        final float[] range = new float[2];
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0);
        GLES20.glLineWidth(Math.max(range[0], Math.min(range[1], mPixelScale)));
    }
}
