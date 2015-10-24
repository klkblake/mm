package com.klkblake.mm;

import java.util.Arrays;

import static com.klkblake.mm.Util.max;

/**
 * Created by kyle on 6/10/15.
 */
public class BooleanArray {
    public boolean[] data = new boolean[16];
    public int count = 0;

    private void grow() {
        data = Arrays.copyOf(data, data.length * 3 / 2);
    }

    public void add(boolean value) {
        if (data.length == count) {
            grow();
        }
        data[count++] = value;
    }

    public void set(int index, boolean value) {
        while (index >= data.length) {
            grow();
        }
        data[index] = value;
        count = max(count, index + 1);
    }
}
