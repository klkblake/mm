package com.klkblake.mm;

import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.MotionEvent;
import android.view.View;
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

    protected void initSelectableView(TextView view, Drawable background) {
        view.setBackground(background.mutate());
        // This is a workaround for a bug in Android 5.0
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                v.drawableHotspotChanged(event.getX(), event.getY());
                return false;
            }
        });
    }

    protected void setSelectableBackgroundColor(TextView view, int color) {
        LayerDrawable background = (LayerDrawable) view.getBackground();
        ColorDrawable solidColor = (ColorDrawable) background.getDrawable(0);
        solidColor.setColor(color);
        if (Util.perceivedBrightness(color) < 0.5f) {
            view.setTextColor(textColorLight);
        } else {
            view.setTextColor(textColorDark);
        }
    }
}
