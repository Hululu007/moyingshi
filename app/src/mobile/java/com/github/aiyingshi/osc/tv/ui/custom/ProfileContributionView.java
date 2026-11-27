package com.github.aiyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.aiyingshi.osc.tv.R;

/**
 * 历史观看贡献格：5 行 × 14 列（近 70 天），行优先、最新一天在右下角；
 * 有观看记录的格子填实心紫色（Arcane 的配色），没记录的是浅灰底。
 * 数据是近 70 天的每日已看小时，index 0 = 69 天前，index 69 = 今天。
 */
public class ProfileContributionView extends View {

    public static final int FILLED_COLOR = Color.parseColor("#FF6366F1");

    private static final int COLS = 14;
    private static final int ROWS = 5;

    private double[] hours = new double[COLS * ROWS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int emptyColor;
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
        emptyColor = resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
    }

    private static int resolveAttr(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.data != 0) return value.data;
        return fallback;
    }

    /** 每天已看小时，70 个（近 70 天），不足 70 个的按 0 补。 */
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
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                double h = hours[row * COLS + col];
                paint.setColor(h > 0 ? FILLED_COLOR : emptyColor);
                float left = getPaddingLeft() + col * (cell + gap);
                float top = getPaddingTop() + row * (cell + gap);
                rect.set(left, top, left + cell, top + cell);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }
    }
}
