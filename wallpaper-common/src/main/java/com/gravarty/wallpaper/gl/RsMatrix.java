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

/**
 * Port: the RenderScript matrix functions of the scripts (matrixLoadIdentity, matrixRotate,
 * ...), from frameworks/base/libs/rs/rsMatrix.cpp (android-2.3.7_r1). Column-major float[16]
 * like there. Kept line by line, including loadRotate() only normalizing an axis that
 * already has length 1, so unnormalized axes give the same matrices as on Gingerbread.
 */
public final class RsMatrix {
    private RsMatrix() {
    }

    private static float get(float[] m, int i, int j) {
        return m[i * 4 + j];
    }

    private static void set(float[] m, int i, int j, float v) {
        m[i * 4 + j] = v;
    }

    public static void loadIdentity(float[] m) {
        set(m, 0, 0, 1);
        set(m, 1, 0, 0);
        set(m, 2, 0, 0);
        set(m, 3, 0, 0);

        set(m, 0, 1, 0);
        set(m, 1, 1, 1);
        set(m, 2, 1, 0);
        set(m, 3, 1, 0);

        set(m, 0, 2, 0);
        set(m, 1, 2, 0);
        set(m, 2, 2, 1);
        set(m, 3, 2, 0);

        set(m, 0, 3, 0);
        set(m, 1, 3, 0);
        set(m, 2, 3, 0);
        set(m, 3, 3, 1);
    }

    public static void load(float[] m, float[] v) {
        System.arraycopy(v, 0, m, 0, 16);
    }

    public static void loadRotate(float[] m, float rot, float x, float y, float z) {
        float c, s;
        m[3] = 0;
        m[7] = 0;
        m[11] = 0;
        m[12] = 0;
        m[13] = 0;
        m[14] = 0;
        m[15] = 1;
        rot *= (float) (Math.PI / 180.0f);
        c = (float) Math.cos(rot);
        s = (float) Math.sin(rot);

        final float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(len != 1)) {
            final float recipLen = 1.f / len;
            x *= recipLen;
            y *= recipLen;
            z *= recipLen;
        }
        final float nc = 1.0f - c;
        final float xy = x * y;
        final float yz = y * z;
        final float zx = z * x;
        final float xs = x * s;
        final float ys = y * s;
        final float zs = z * s;
        m[0] = x * x * nc + c;
        m[4] = xy * nc - zs;
        m[8] = zx * nc + ys;
        m[1] = xy * nc + zs;
        m[5] = y * y * nc + c;
        m[9] = yz * nc - xs;
        m[2] = zx * nc - ys;
        m[6] = yz * nc + xs;
        m[10] = z * z * nc + c;
    }

    public static void loadScale(float[] m, float x, float y, float z) {
        loadIdentity(m);
        m[0] = x;
        m[5] = y;
        m[10] = z;
    }

    public static void loadTranslate(float[] m, float x, float y, float z) {
        loadIdentity(m);
        m[12] = x;
        m[13] = y;
        m[14] = z;
    }

    /** m = lhs * rhs; m may be lhs or rhs. */
    public static void loadMultiply(float[] m, float[] lhs, float[] rhs) {
        final float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            float ri0 = 0;
            float ri1 = 0;
            float ri2 = 0;
            float ri3 = 0;
            for (int j = 0; j < 4; j++) {
                final float rhs_ij = get(rhs, i, j);
                ri0 += get(lhs, j, 0) * rhs_ij;
                ri1 += get(lhs, j, 1) * rhs_ij;
                ri2 += get(lhs, j, 2) * rhs_ij;
                ri3 += get(lhs, j, 3) * rhs_ij;
            }
            set(out, i, 0, ri0);
            set(out, i, 1, ri1);
            set(out, i, 2, ri2);
            set(out, i, 3, ri3);
        }
        System.arraycopy(out, 0, m, 0, 16);
    }

    public static void loadOrtho(float[] m, float l, float r, float b, float t, float n,
            float f) {
        loadIdentity(m);
        m[0] = 2 / (r - l);
        m[5] = 2 / (t - b);
        m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(f + n) / (f - n);
    }

    public static void loadFrustum(float[] m, float l, float r, float b, float t, float n,
            float f) {
        loadIdentity(m);
        m[0] = 2 * n / (r - l);
        m[5] = 2 * n / (t - b);
        m[8] = (r + l) / (r - l);
        m[9] = (t + b) / (t - b);
        m[10] = -(f + n) / (f - n);
        m[11] = -1;
        m[14] = -2 * f * n / (f - n);
        m[15] = 0;
    }

    public static void multiply(float[] m, float[] rhs) {
        loadMultiply(m, m, rhs);
    }

    public static void rotate(float[] m, float rot, float x, float y, float z) {
        final float[] tmp = new float[16];
        loadRotate(tmp, rot, x, y, z);
        multiply(m, tmp);
    }

    public static void scale(float[] m, float x, float y, float z) {
        final float[] tmp = new float[16];
        loadScale(tmp, x, y, z);
        multiply(m, tmp);
    }

    public static void translate(float[] m, float x, float y, float z) {
        final float[] tmp = new float[16];
        loadTranslate(tmp, x, y, z);
        multiply(m, tmp);
    }
}
