package com.klkblake.mm;

import java.util.Arrays;

/**
 * Created by kyle on 6/10/15.
 */
public class IntArray {
    public int[] data = new int[16];
    public int count = 0;

    public void add(int value) {
        if (data.length == count) {
            data = Arrays.copyOf(data, data.length * 3 / 2);
        }
        data[count++] = value;
    }
}
