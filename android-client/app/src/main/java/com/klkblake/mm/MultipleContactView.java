package com.klkblake.mm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import com.klkblake.mm.common.Util;

import java.util.ArrayList;

import static com.klkblake.mm.common.Util.ceil;
import static com.klkblake.mm.common.Util.max;
import static com.klkblake.mm.common.Util.min;

@SuppressLint("ViewConstructor")
public class MultipleContactView extends View {
    // This block contains the variables accessed during a draw
    private Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaintSmall;
    private final TextPaint textPaintLarge;
    private TextPaint textPaintRecentMessage;
    private Bitmap[] avatars;
    private String[] ellipsizedNames;
    private int[] colors;
    private String recentMessage = "Recent message text";
    public final int count;
    private int rowCount;
    private int top;
    private int avatarSize = (int) (40 * App.density);
    private int stepPadding = (int) (2 * App.density);
    private final int step = (int) (avatarSize + stepPadding);
    private int avatar1Left;
    private int circle2CenterX;
    private int circleRadius = (int) (12 * App.density);
    private int textMargin = (int) (16 * App.density);
    private final int textOffsetLarge;
    private int textOffsetSmall;
    private int singleModeNameTop = (int) (4*App.density);
    private int singleModeRecentBaseline = (int) (36*App.density);
    private boolean drawRecent;
    private boolean isSingle;
    private boolean even;

    private int preText = step + avatarSize + textMargin;
    private int postText = textMargin + circleRadius + step + circleRadius;
    private Bitmap defaultAvatar;
    private String[] names;

    public MultipleContactView(Context context, int count, TextPaint textPaintLarge, TextPaint textPaintSmall, TextPaint textPaintRecentMessage, Bitmap defaultAvatar) {
        super(context);
        this.count = count;
        even = (count & 1) == 0;
        rowCount = count >> 1;
        if (!even) {
            rowCount++;
        }
        avatars = new Bitmap[count];
        names = new String[count];
        ellipsizedNames = new String[count];
        colors = new int[count];

        this.textPaintLarge = textPaintLarge;
        this.textPaintSmall = textPaintSmall;
        this.textPaintRecentMessage = textPaintRecentMessage;
        Paint.FontMetricsInt fm = textPaintLarge.getFontMetricsInt();
        textOffsetLarge = fm.top;
        textPaintSmall.getFontMetricsInt(fm);
        textOffsetSmall = fm.top;
        this.defaultAvatar = defaultAvatar;
    }

    public void setSubusers(ArrayList<AndroidUser.SubUser> subusers, int offset, boolean drawRecent) {
        for (int i = 0; i < count; i++) {
            AndroidUser.SubUser subuser = subusers.get(i + offset);
            if (subuser.hasAvatar()) {
                avatars[i] = subuser.getAvatar();
            } else {
                avatars[i] = defaultAvatar;
            }
            names[i] = subuser.getName();
            colors[i] = subuser.getColor();
        }
        this.drawRecent = drawRecent;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        String concat = null;
        if (count == 2) {
            concat = names[0] + " and " + names[1];
        }

        int width;
        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else {
            width = 0;
            if (count == 2) {
                width = ceil(textPaintLarge.measureText(concat));
            } else {
                for (String name : names) {
                    width = max(width, ceil(textPaintSmall.measureText(name)));
                }
            }
            width = max(width, ceil(textPaintSmall.measureText(recentMessage)));
            width += preText + postText + getPaddingLeft() + getPaddingRight();
            width = max(width, getSuggestedMinimumWidth());
            if (widthMode == MeasureSpec.AT_MOST) {
                width = min(width, widthSize);
            }
        }
        int textWidth = width - preText - postText - getPaddingLeft() - getPaddingRight();

        isSingle = count == 2 && ceil(textPaintLarge.measureText(concat)) <= textWidth;
        if (isSingle) {
            ellipsizedNames[0] = concat;
        } else {
            for (int i = 0; i < count; i++) {
                ellipsizedNames[i] = TextUtils.ellipsize(names[i], textPaintSmall, textWidth, TextUtils.TruncateAt.END).toString();
            }
        }

        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else {
            if (isSingle) {
                height = avatarSize;
            } else {
                height = rowCount * step - stepPadding;
                if (!isSingle && drawRecent && even) {
                    height += step >> 1;
                }
            }
            height += getPaddingTop() + getPaddingBottom();
            height = max(height, getSuggestedMinimumHeight());
            if (heightMode == MeasureSpec.AT_MOST) {
                height = min(height, heightSize);
            }
        }

        top = getPaddingTop();
        avatar1Left = getPaddingLeft();
        circle2CenterX = width - getPaddingRight() - circleRadius;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // TODO RTL awareness
        int avatar2Left = avatar1Left + step;
        int avatar2Right = avatar2Left + avatarSize;
        for (int i = 0, j = 0, y0 = top; i < rowCount; i++, y0 += step) {
            canvas.drawBitmap(avatars[j++], avatar1Left, y0, avatarPaint);
            if (i < rowCount - 1 || even) {
                canvas.drawBitmap(avatars[j++], avatar2Left, y0, avatarPaint);
            }
        }

        int circle1CenterX = circle2CenterX - step;
        int circleCenterY = top + (avatarSize >> 1);
        for (int i = 0, j = 0, y = circleCenterY; i < rowCount; i++, y += step) {
            circlePaint.setColor(colors[j++]);
            canvas.drawCircle(circle1CenterX, y, circleRadius, circlePaint);
            if (i < rowCount - 1 || even) {
                circlePaint.setColor(colors[j++]);
                canvas.drawCircle(circle2CenterX, y, circleRadius, circlePaint);
            }
        }

        int textLeft = avatar2Right + textMargin;
        int textStep = step >> 1;
        if (isSingle) {
            int y = top - textOffsetLarge;
            canvas.drawText(ellipsizedNames[0], textLeft, y + singleModeNameTop, textPaintLarge);
            canvas.drawText(recentMessage, textLeft, top + singleModeRecentBaseline, textPaintRecentMessage);
        } else {
            int y = top - textOffsetSmall;
            for (int i = 0; i < count; i++, y += textStep) {
                canvas.drawText(ellipsizedNames[i], textLeft, y, textPaintSmall);
            }
            if (drawRecent) {
                canvas.drawText(recentMessage, textLeft, y, textPaintRecentMessage);
            }
        }
    }
}
