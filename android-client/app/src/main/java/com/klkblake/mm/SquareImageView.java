package com.klkblake.mm;

import android.content.Context;
import android.widget.ImageView;

/**
 * Created by kyle on 4/10/15.
 */
public class SquareImageView extends ImageView {
    public SquareImageView(Context context) {
        super(context);
        setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = getMeasuredWidth();
        super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
    }
}
