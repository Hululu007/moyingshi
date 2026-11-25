package com.github.moyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.moyingshi.osc.tv.R;

/**
 * 本月观看双线图：剧集（橙）与电影（蓝）两条线，按当月每日已看小时画；
 * 3 条横格线，横轴标 1 / 8 / 15 / 22 / 月末。没数据时只画格线与刻度。
 */
public class ProfileMonthChartView extends View {

    public static final int SERIES_COLOR = Color.parseColor("#FFF59E0B");
    public static final int MOVIES_COLOR = Color.parseColor("#FF6366F1");

    private double[] series = new double[0];
    private double[] movies = new double[0];
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float density;

    public ProfileMonthChartView(Context context) {
        super(context);
        init(context);
    }

    public ProfileMonthChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        int gridColor = resolveAttr(context, R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(1 * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
        int textColor = resolveAttr(context, R.attr.colorOnSurfaceVariant, Color.parseColor("#FF8A8A8A"));
        textPaint.setColor(textColor);
        textPaint.setTextSize(11 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private static int resolveAttr(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.data != 0) return value.data;
        return fallback;
    }

    /** 当月每日已看小时：series / movies 与当月天数等长。 */
    public void setData(@Nullable double[] dailySeries, @Nullable double[] dailyMovies) {
        series = dailySeries == null ? new double[0] : dailySeries.clone();
        movies = dailyMovies == null ? new double[0] : dailyMovies.clone();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, (int) (150 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 8 * density;
        float right = getWidth() - 8 * density;
        float top = 10 * density;
        float bottom = getHeight() - 22 * density;
        int days = Math.max(series.length, movies.length);
        if (days == 0) return;

        // 横格线
        for (int i = 1; i <= 3; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        double max = 0;
        for (double v : series) max = Math.max(max, v);
        for (double v : movies) max = Math.max(max, v);
        if (max > 0) {
            drawLine(canvas, series, SERIES_COLOR, left, right, top, bottom, max);
            drawLine(canvas, movies, MOVIES_COLOR, left, right, top, bottom, max);
        }

        // 横轴刻度：1 / 8 / 15 / 22 / 月末
        int[] marks = {1, 8, 15, 22, days};
        for (int day : marks) {
            if (day < 1 || day > days) continue;
            float x = days == 1 ? left : left + (day - 1) * (right - left) / (days - 1);
            canvas.drawText(String.valueOf(day), x, getHeight() - 6 * density, textPaint);
        }
    }

    private void drawLine(Canvas canvas, double[] values, int color,
                          float left, float right, float top, float bottom, double max) {
        if (values.length == 0) return;
        linePaint.setColor(color);
        dotPaint.setColor(color);
        Path path = new Path();
        for (int i = 0; i < values.length; i++) {
            float x = values.length == 1 ? left : left + i * (right - left) / (values.length - 1);
            float y = (float) (bottom - values[i] / max * (bottom - top));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);
        for (int i = 0; i < values.length; i++) {
            float x = values.length == 1 ? left : left + i * (right - left) / (values.length - 1);
            float y = (float) (bottom - values[i] / max * (bottom - top));
            canvas.drawCircle(x, y, 2.5f * density, dotPaint);
        }
    }
}
