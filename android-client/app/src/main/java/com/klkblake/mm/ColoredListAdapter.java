package com.klkblake.mm;

import android.content.res.TypedArray;
import android.widget.BaseAdapter;
import android.widget.TextView;

/**
 * Created by kyle on 28/11/15.
 */
public abstract class ColoredListAdapter extends BaseAdapter {
    protected int textColorLight, textColorDark;

    public ColoredListAdapter() {
        TypedArray colors = App.context.getTheme().obtainStyledAttributes(new int[]{
                android.R.attr.textColorPrimary,
                android.R.attr.textColorPrimaryInverse
        });
        textColorLight = colors.getColor(0, 0xffffffff);
        textColorDark = colors.getColor(1, 0xff000000);
        colors.recycle();
    }

    protected void setTextViewForBackground(TextView view, int color) {
        if (Util.perceivedBrightness(color) < 0.5f) {
            view.setTextColor(textColorLight);
        } else {
            view.setTextColor(textColorDark);
        }
    }
}
