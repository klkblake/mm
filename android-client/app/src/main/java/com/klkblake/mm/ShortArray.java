package com.klkblake.mm;

import java.util.Arrays;

/**
 * Created by kyle on 6/10/15.
 */
public class ShortArray {
    public short[] data = new short[16];
    public int count = 0;

    public void add(short value) {
        if (data.length == count) {
            data = Arrays.copyOf(data, data.length * 3 / 2);
        }
        data[count++] = value;
    }
}
