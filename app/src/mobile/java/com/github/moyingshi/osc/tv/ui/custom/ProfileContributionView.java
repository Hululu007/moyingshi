package com.github.moyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.moyingshi.osc.tv.R;

/**
 * 历史观看贡献格：7 行（周一–周日）× 12 列（周），格子颜色深浅按当天已看小时分 4 档。
 * 数据是近 12 周（周一对齐）的每日已看小时，index = 列 × 7 + 行。
 */
public class ProfileContributionView extends View {

    private static final int COLS = 12;
    private static final int ROWS = 7;

    private double[] hours = new double[COLS * ROWS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int emptyColor;
    private int primaryColor;
    private float gap;
    private float radius;

    public ProfileContributionView(Context context) {
        super(context);
        init(context);
    }

    public ProfileContributionView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        gap = 3 * density;
        radius = 2 * density;
        emptyColor = resolveAttr(context, R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
        primaryColor = resolveAttr(context, R.attr.colorPrimary, Color.parseColor("#FF0B57D0"));
    }

    private static int resolveAttr(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.data != 0) return value.data;
        return fallback;
    }

    /** 每天已看小时，84 个（12 周 × 7 天），不足 84 个的按 0 补。 */
    public void setData(@Nullable double[] dailyHours) {
        hours = new double[COLS * ROWS];
        if (dailyHours != null) {
            System.arraycopy(dailyHours, 0, hours, 0, Math.min(dailyHours.length, hours.length));
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        float cell = (width - getPaddingLeft() - getPaddingRight() - (COLS - 1) * gap) / COLS;
        int height = (int) (getPaddingTop() + getPaddingBottom() + ROWS * cell + (ROWS - 1) * gap);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cell = (getWidth() - getPaddingLeft() - getPaddingRight() - (COLS - 1) * gap) / COLS;
        for (int col = 0; col < COLS; col++) {
            for (int row = 0; row < ROWS; row++) {
                double h = hours[col * ROWS + row];
                paint.setColor(colorFor(h));
                float left = getPaddingLeft() + col * (cell + gap);
                float top = getPaddingTop() + row * (cell + gap);
                rect.set(left, top, left + cell, top + cell);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }
    }

    private int colorFor(double hours) {
        if (hours <= 0) return emptyColor;
        int alpha = hours <= 0.5 ? 90 : hours <= 1 ? 140 : hours <= 2 ? 190 : 255;
        return (primaryColor & 0x00FFFFFF) | (alpha << 24);
    }
}
