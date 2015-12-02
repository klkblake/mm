package com.klkblake.mm;

import java.nio.charset.StandardCharsets;

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

    public static float perceivedBrightness(int color) {
        float r = ((color >> 16) & 0xff) / 255.0f;
        float g = ((color >> 8) & 0xff) / 255.0f;
        float b = (color & 0xff) / 255.0f;
        return (float) Math.sqrt(0.299*r*r + 0.587*g*g + 0.114*b*b);
    }

    public static boolean isDark(int color) {
        return perceivedBrightness(color) < 0.5f;
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
