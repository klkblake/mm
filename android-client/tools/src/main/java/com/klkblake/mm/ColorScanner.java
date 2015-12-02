package com.klkblake.mm;

import com.klkblake.mm.common.Util;

import java.util.ArrayList;

/**
 * Created by kyle on 3/12/15.
 */
public class ColorScanner {
    public static void main(String[] args) {
        ArrayList<Integer> colors = new ArrayList<>();
        for (int i = 0; i <= 0xffffff; i++) {
            if (Util.luminance(i) == 0.5f) {
                System.out.printf("COLOREXACT: %x\n", i);
                colors.add(i);
            }
        }
        for (int color : colors) {
            for (int shift : new int[]{0, 8, 16}) {
                int channel = (color >> shift) & 0xff;
                channel--;
                int color2 = color & ~(0xff << shift);
                color2 |= channel << shift;
                System.out.printf("COLORSIMILAR: %x %f\n", color2, Util.luminance(color2));
            }
        }
        boolean nonefound = true;
        float target = 0.5f;
        while (nonefound) {
            int targetBits = Float.floatToRawIntBits(target);
            target = Float.intBitsToFloat(targetBits - 1);
            for (int i = 0; i <= 0xffffff; i++) {
                if (Util.luminance(i) == target) {
                    nonefound = false;
                    System.out.printf("COLORCLOSE: %x %f\n", i, target);
                }
            }
        }
    }
}
