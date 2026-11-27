package com.github.aiyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 本月观看堆叠柱状图：每天一根竖条，下段电影（紫）上段剧集（橙），高度按当天总小时；
 * 3 条横格线，横轴标 1 / 8 / 15 / 22 / 月末。没数据时只画格线与刻度。
 */
public class ProfileMonthChartView extends View {

    public static final int SERIES_COLOR = Color.parseColor("#FFF59E0B");
    public static final int MOVIES_COLOR = Color.parseColor("#FF6366F1");

    private double[] series = new double[0];
    private double[] movies = new double[0];
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path topPath = new Path();
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
        int gridColor = resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(1 * density);
        barPaint.setStyle(Paint.Style.FILL);
        int textColor = resolveAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#FF8A8A8A"));
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
        for (int i = 0; i < days; i++) {
            double s = i < series.length ? series[i] : 0;
            double m = i < movies.length ? movies[i] : 0;
            max = Math.max(max, s + m);
        }
        if (max <= 0) {
            drawLabels(canvas, days, left, right);
            return;
        }
        float slot = (right - left) / days;
        float barWidth = Math.min(10 * density, slot * 0.6f);
        float radius = 2 * density;
        for (int i = 0; i < days; i++) {
            double s = i < series.length ? series[i] : 0;
            double m = i < movies.length ? movies[i] : 0;
            if (s + m <= 0) continue;
            float cx = left + slot * i + slot / 2;
            float totalH = (float) ((s + m) / max * (bottom - top));
            float movieH = (float) (m / (s + m) * totalH);
            float barTop = bottom - totalH;
            float splitY = bottom - movieH;
            // 下段：电影（紫）；只有它时顶边也圆角
            barPaint.setColor(MOVIES_COLOR);
            if (movieH > 0) {
                if (s > 0) {
                    rect.set(cx - barWidth / 2, splitY, cx + barWidth / 2, bottom);
                    canvas.drawRect(rect, barPaint);
                } else {
                    drawTopRounded(canvas, cx - barWidth / 2, barTop, cx + barWidth / 2, bottom, radius);
                }
            }
            // 上段：剧集（橙），顶边圆角
            if (s > 0) {
                barPaint.setColor(SERIES_COLOR);
                drawTopRounded(canvas, cx - barWidth / 2, barTop, cx + barWidth / 2, splitY, radius);
            }
        }
        drawLabels(canvas, days, left, right);
    }

    /** 顶边圆角、底边直角的竖条段。 */
    private void drawTopRounded(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        if (bottom - top < radius * 2) {
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, barPaint);
            return;
        }
        topPath.reset();
        topPath.moveTo(left, bottom);
        topPath.lineTo(left, top + radius);
        topPath.arcTo(left, top, left + radius * 2, top + radius * 2, 180, 90, false);
        topPath.lineTo(right - radius, top);
        topPath.arcTo(right - radius * 2, top, right, top + radius * 2, 270, 90, false);
        topPath.lineTo(right, bottom);
        topPath.close();
        canvas.drawPath(topPath, barPaint);
    }

    private void drawLabels(Canvas canvas, int days, float left, float right) {
        // 横轴刻度：1 / 8 / 15 / 22 / 月末
        int[] marks = {1, 8, 15, 22, days};
        for (int day : marks) {
            if (day < 1 || day > days) continue;
            float x = days == 1 ? left : left + (day - 1) * (right - left) / (days - 1);
            canvas.drawText(String.valueOf(day), x, getHeight() - 6 * density, textPaint);
        }
    }
}
