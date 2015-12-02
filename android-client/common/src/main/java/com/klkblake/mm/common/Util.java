package com.klkblake.mm.common;

import java.nio.charset.StandardCharsets;

import static java.lang.Math.pow;

/**
 * Created by kyle on 5/10/15.
 */
public class Util {
    public static int min(int a, int b) {
        return a < b ? a : b;
    }

    public static long min(long a, long b) {
        return a < b ? a : b;
    }

    public static int max(int a, int b) {
        return a > b ? a : b;
    }

    public static int calculateSampleSize(int width, int height, int reqWidth, int reqHeight) {
        return min(width / reqWidth, height / reqHeight);
    }

    // This computes CIE luminance, but only uses approximate gamma conversion.
    public static float luminance(int color) {
        float sr = ((color >> 16) & 0xff) / 255.0f;
        float sg = ((color >> 8) & 0xff) / 255.0f;
        float sb = (color & 0xff) / 255.0f;
        float r = (float) pow(sr, 2.2);
        float g = (float) pow(sg, 2.2);
        float b = (float) pow(sb, 2.2);
        return 0.2126f*r + 0.7152f*g + 0.0722f*b;
    }

    public static boolean isDark(int color) {
        return luminance(color) < 0.5f;
    }

    public static void impossible(Throwable e) {
        throw new RuntimeException("Not quite as impossible as anticipated", e);
    }

    public static byte[] utf8Encode(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static String utf8Decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static int us2i(short x) {
        return x & 0xffff;
    }

    public static int ub2i(byte x) {
        return x & 0xff;
    }
}
