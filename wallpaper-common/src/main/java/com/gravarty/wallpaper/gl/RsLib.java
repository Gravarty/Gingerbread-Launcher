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

import android.os.SystemClock;

import java.util.Calendar;
import java.util.Random;

/**
 * Port: the math and time functions of the RenderScript runtime the scripts call, from
 * frameworks/base/libs/rs/rsScriptC_Lib.cpp and rsNoise.cpp (android-2.3.7_r1), kept as
 * they were there (including mapf() and the lerpf macro of the noise functions).
 * rand() is java.util.Random; RAND_MAX scaling as in randf().
 */
public final class RsLib {
    private RsLib() {
    }

    public static final float PI = 3.1415926f;

    private static final Random sRandom = new Random();

    /** rand(): 0 .. RAND_MAX (0x7fffffff) */
    private static int rand() {
        return sRandom.nextInt() & 0x7fffffff;
    }

    // ------------------------------------------------------------------- Math routines

    public static float sinf_fast(float x) {
        final float A = (float) (1.0f / (2.0f * Math.PI));
        final float B = -16.0f;
        final float C = 8.0f;

        // scale angle for easy argument reduction
        x *= A;

        if (Math.abs(x) >= 0.5f) {
            // argument reduction
            x = x - (float) Math.ceil(x + 0.5f) + 1.0f;
        }

        final float y = B * x * Math.abs(x) + C * x;
        return 0.2215f * (y * Math.abs(y) - y) + y;
    }

    public static float cosf_fast(float x) {
        x += (float) (Math.PI / 2);

        final float A = (float) (1.0f / (2.0f * Math.PI));
        final float B = -16.0f;
        final float C = 8.0f;

        // scale angle for easy argument reduction
        x *= A;

        if (Math.abs(x) >= 0.5f) {
            // argument reduction
            x = x - (float) Math.ceil(x + 0.5f) + 1.0f;
        }

        final float y = B * x * Math.abs(x) + C * x;
        return 0.2215f * (y * Math.abs(y) - y) + y;
    }

    public static float randf(float max) {
        final float r = (float) rand();
        return r / 0x7fffffff * max;
    }

    public static float randf2(float min, float max) {
        final float r = (float) rand();
        return r / 0x7fffffff * (max - min) + min;
    }

    public static float clampf(float amount, float low, float high) {
        return amount < low ? low : (amount > high ? high : amount);
    }

    public static float minf(float a, float b) {
        return a < b ? a : b;
    }

    public static float absf(float v) {
        return Math.abs(v);
    }

    public static float radf(float degrees) {
        return degrees * (PI / 180.0f);
    }

    public static float degf(float radians) {
        return radians * (180.0f / PI);
    }

    public static float lerpf(float start, float stop, float amount) {
        return start + (stop - start) * amount;
    }

    public static float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    public static float mapf(float minStart, float minStop, float maxStart, float maxStop,
            float value) {
        return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
    }

    // ------------------------------------------------------------------- Time routines

    public static int second() {
        return Calendar.getInstance().get(Calendar.SECOND);
    }

    public static int minute() {
        return Calendar.getInstance().get(Calendar.MINUTE);
    }

    public static int hour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }

    public static int uptimeMillis() {
        return (int) SystemClock.uptimeMillis();
    }

    // ------------------------------------------------------------------- Color

    private static void hsbToRgb(float h, float s, float b, float[] rgb) {
        float red = 0.0f;
        float green = 0.0f;
        float blue = 0.0f;

        float x = h;
        float y = s;
        float z = b;

        float hf = (x - (int) x) * 6.0f;
        int ihf = (int) hf;
        float f = hf - ihf;
        float pv = z * (1.0f - y);
        float qv = z * (1.0f - y * f);
        float tv = z * (1.0f - y * (1.0f - f));

        switch (ihf) {
            case 0:         // Red is the dominant color
                red = z;
                green = tv;
                blue = pv;
                break;
            case 1:         // Green is the dominant color
                red = qv;
                green = z;
                blue = pv;
                break;
            case 2:
                red = pv;
                green = z;
                blue = tv;
                break;
            case 3:         // Blue is the dominant color
                red = pv;
                green = qv;
                blue = z;
                break;
            case 4:
                red = tv;
                green = pv;
                blue = z;
                break;
            case 5:         // Red is the dominant color
                red = z;
                green = pv;
                blue = qv;
                break;
        }

        rgb[0] = red;
        rgb[1] = green;
        rgb[2] = blue;
    }

    public static int hsbToAbgr(float h, float s, float b, float a) {
        final float[] rgb = new float[3];
        hsbToRgb(h, s, b, rgb);
        return (int) (a * 255.0f) << 24 |
               (int) (rgb[2] * 255.0f) << 16 |
               (int) (rgb[1] * 255.0f) << 8 |
               (int) (rgb[0] * 255.0f);
    }

    // ------------------------------------------------------------------- rsNoise.cpp

    private static final int B = 0x100;
    private static final int BM = 0xff;
    private static final int N = 0x1000;

    private static final int[] p = new int[B + B + 2];
    private static final float[][] g2 = new float[B + B + 2][2];
    private static final float[] g1 = new float[B + B + 2];
    private static boolean noise_start = true;

    private static float noise_sCurve(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static void normalizef2(float[] v) {
        final float s = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        v[0] = v[0] / s;
        v[1] = v[1] / s;
    }

    private static void noise_init() {
        int i, j, k;

        for (i = 0; i < B; i++) {
            p[i] = i;

            g1[i] = (float) ((rand() % (B + B)) - B) / B;

            for (j = 0; j < 2; j++)
                g2[i][j] = (float) ((rand() % (B + B)) - B) / B;
            normalizef2(g2[i]);
            // Port: g3 (noisef3) is not used by the scripts; its rand() calls are kept so
            // the sequence matches
            for (j = 0; j < 3; j++)
                rand();
        }

        for (i = B - 1; i >= 0; i--) {
            k = p[i];
            p[i] = p[j = rand() % B];
            p[j] = k;
        }

        for (i = 0; i < B + 2; i++) {
            p[B + i] = p[i];
            g1[B + i] = g1[i];
            for (j = 0; j < 2; j++)
                g2[B + i][j] = g2[i][j];
        }
    }

    public static synchronized float noisef2(float x, float y) {
        int bx0, bx1, by0, by1, b00, b10, b01, b11;
        float rx0, rx1, ry0, ry1, sx, sy, a, b, t, u, v;
        float[] q;
        int i, j;

        if (noise_start) {
            noise_start = false;
            noise_init();
        }

        t = x + N;
        bx0 = ((int) t) & BM;
        bx1 = (bx0 + 1) & BM;
        rx0 = t - (int) t;
        rx1 = rx0 - 1.0f;

        t = y + N;
        by0 = ((int) t) & BM;
        by1 = (by0 + 1) & BM;
        ry0 = t - (int) t;
        ry1 = ry0 - 1.0f;

        i = p[bx0];
        j = p[bx1];

        b00 = p[i + by0];
        b10 = p[j + by0];
        b01 = p[i + by1];
        b11 = p[j + by1];

        sx = noise_sCurve(rx0);
        sy = noise_sCurve(ry0);

        q = g2[b00]; u = rx0 * q[0] + ry0 * q[1];
        q = g2[b10]; v = rx1 * q[0] + ry0 * q[1];
        a = u + (v - u) * sx;

        q = g2[b01]; u = rx0 * q[0] + ry1 * q[1];
        q = g2[b11]; v = rx1 * q[0] + ry1 * q[1];
        b = u + (v - u) * sx;

        // the lerpf macro without parentheses: 1.5f*lerpf(a, b, sy)
        return 1.5f * a + (b - a) * sy;
    }

    public static float turbulencef2(float x, float y, float octaves) {
        float t = 0.0f;

        for (float f = 1.0f; f <= octaves; f *= 2)
            t += Math.abs(noisef2(f * x, f * y)) / f;
        return t;
    }
}
