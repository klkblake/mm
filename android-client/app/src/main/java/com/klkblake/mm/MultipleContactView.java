package com.klkblake.mm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;

import static com.klkblake.mm.common.Util.max;

@SuppressLint("ViewConstructor")
public class MultipleContactView extends View {
    // This block contains the variables accessed during a draw
    private TextPaint textPaint;
    private Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaintRecentMessage;
    private Bitmap[] avatars;
    private String[] ellipsizedNames;
    private int[] colors;
    public final int count;
    private int rowCount;
    private int top;
    private int avatarSize = (int) (40 * App.density);
    private final int step = (int) (avatarSize + 2 * App.density);
    private int avatar1Left;
    private int circle2CenterX;
    private int circleRadius = (int) (12 * App.density);
    private int textMargin = (int) (16 * App.density);
    private int textOffset;
    private boolean drawRecent;
    private boolean even;

    private int preText = step + avatarSize + textMargin;
    private int postText = textMargin + circleRadius + step + circleRadius;
    private Bitmap defaultAvatar;
    private String[] names;
    private int textWidth = -1;

    public MultipleContactView(Context context, int count, TextPaint textPaint, TextPaint textPaintRecentMessage, Bitmap defaultAvatar) {
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

        this.textPaint = textPaint;
        this.textPaintRecentMessage = textPaintRecentMessage;
        Paint.FontMetricsInt fm = textPaint.getFontMetricsInt();
        textOffset = fm.top;
        this.defaultAvatar = defaultAvatar;
    }

    private void ellipsizeNames() {
        for (int i = 0; i < count; i++) {
            ellipsizedNames[i] = TextUtils.ellipsize(names[i], textPaint, textWidth, TextUtils.TruncateAt.END).toString();
        }
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
        if (textWidth != -1) {
            ellipsizeNames();
        }
        if (this.drawRecent != drawRecent) {
            requestLayout();
        }
        this.drawRecent = drawRecent;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = preText + postText;
        int height = rowCount * step;
        if (even && drawRecent) {
            height += step >> 1;
        }
        width += getPaddingLeft() + getPaddingRight();
        height += getPaddingTop() + getPaddingBottom();
        width = max(width, getSuggestedMinimumWidth());
        height = max(height, getSuggestedMinimumHeight());
        setMeasuredDimension(getDefaultSize(width, widthMeasureSpec), resolveSizeAndState(height, heightMeasureSpec, 0));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        top = getPaddingTop();
        avatar1Left = getPaddingLeft();
        circle2CenterX = w - getPaddingRight() - circleRadius;
        textWidth = w - preText - postText - getPaddingLeft() - getPaddingRight();
        ellipsizeNames();
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
        int y = top - textOffset;
        for (int i = 0; i < count; i++, y += textStep) {
            canvas.drawText(ellipsizedNames[i], textLeft, y, textPaint);
        }
        if (drawRecent) {
            canvas.drawText("Recent message text", textLeft, y, textPaintRecentMessage);
        }
    }
}
