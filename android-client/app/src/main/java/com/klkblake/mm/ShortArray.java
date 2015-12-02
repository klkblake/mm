package com.klkblake.mm;

import java.util.Arrays;

import static com.klkblake.mm.common.Util.max;

/**
 * Created by kyle on 6/10/15.
 */
public class ShortArray {
    public short[] data = new short[16];
    public int count = 0;

    private void grow() {
        data = Arrays.copyOf(data, data.length * 3 / 2);
    }

    public void add(short value) {
        if (data.length == count) {
            grow();
        }
        data[count++] = value;
    }

    public void set(int index, short value) {
        while (index >= data.length) {
            grow();
        }
        data[index] = value;
        count = max(count, index + 1);
    }
}
