package com.klkblake.mm.common;

import java.util.Arrays;

import static com.klkblake.mm.common.Util.max;

/**
 * Created by kyle on 6/10/15.
 */
public class LongArray {
    public long[] data = new long[16];
    public int count = 0;

    private void grow() {
        data = Arrays.copyOf(data, data.length * 3 / 2);
    }

    public void add(long value) {
        if (data.length == count) {
            grow();
        }
        data[count++] = value;
    }

    public void set(int index, long value) {
        while (index >= data.length) {
            grow();
        }
        data[index] = value;
        count = max(count, index + 1);
    }
}
