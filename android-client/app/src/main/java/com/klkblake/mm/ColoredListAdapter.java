package com.klkblake.mm;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

/**
 * Created by kyle on 28/11/15.
 */
public abstract class ColoredListAdapter extends BaseAdapter {
    private final ContextThemeWrapper lightContext, darkContext;

    public ColoredListAdapter(Context context) {
        lightContext = new ContextThemeWrapper(context, R.style.ThemeOverlay_AppCompat_Light);
        darkContext = new ContextThemeWrapper(context, R.style.ThemeOverlay_AppCompat_Dark);
    }

    protected ContextThemeWrapper contextForTheme(boolean isDark) {
        return isDark ? darkContext : lightContext;
    }

    protected LayoutInflater inflaterForTheme(boolean isDark) {
        Context context = contextForTheme(isDark);
        return (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    protected void initSelectableView(TextView view, Drawable background) {
        view.setBackground(background.mutate());
    }

    protected void bugfixPropagateHotspotChanges(View view) {
        // Android 5.0 is buggy and doesn't propagate hotspots to ListView children. Additionally,
        // it has bizarre rules for propagating hotspots to children, and doesn't even propagate
        // them correctly.
        // All three issues fixed in 5.1
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        if (view instanceof ViewGroup) {
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) {
                        View child = vg.getChildAt(i);
                        float x = event.getX();
                        float y = event.getY() - child.getTop();
                        child.drawableHotspotChanged(x, y);
                    }
                    return false;
                }
            });
        } else {
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    v.drawableHotspotChanged(event.getX(), event.getY());
                    return false;
                }
            });
        }
    }

    protected ColorDrawable getSelectableBackgroundColor(TextView view) {
        LayerDrawable background = (LayerDrawable) view.getBackground();
        return (ColorDrawable) background.getDrawable(0);
    }

    protected RippleDrawable getSelectableBackgroundRipple(TextView view) {
        LayerDrawable background = (LayerDrawable) view.getBackground();
        return (RippleDrawable) background.getDrawable(1);
    }
}
