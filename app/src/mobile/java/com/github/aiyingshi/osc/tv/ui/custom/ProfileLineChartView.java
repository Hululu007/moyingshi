package com.github.aiyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 紫色折线图：线下带渐变填充，3 条横格线，不画横轴刻度。
 * 最近 7 天页与分类走势 tile 用；没数据时只画一条贴底的平线。
 */
public class ProfileLineChartView extends View {

    public static final int LINE_COLOR = Color.parseColor("#FF6366F1");

    private double[] values = new double[0];
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private float density;

    public ProfileLineChartView(Context context) {
        super(context);
        init(context);
    }

    public ProfileLineChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        int gridColor = resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(1 * density);
        linePaint.setColor(LINE_COLOR);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    private static int resolveAttr(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.data != 0) return value.data;
        return fallback;
    }

    public void setData(@Nullable double[] values) {
        this.values = values == null ? new double[0] : values.clone();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height;
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else {
            height = (int) (110 * density);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) return;
        float left = 8 * density;
        float right = getWidth() - 8 * density;
        float top = 10 * density;
        float bottom = getHeight() - 6 * density;

        // 横格线
        for (int i = 1; i <= 3; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        double max = 0;
        for (double v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;
        linePath.reset();
        fillPath.reset();
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (left + right) / 2 : left + i * (right - left) / (n - 1);
            float y = (float) (bottom - values[i] / max * (bottom - top));
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        float lastX = n == 1 ? (left + right) / 2 : right;
        fillPath.lineTo(lastX, bottom);
        fillPath.close();
        fillPaint.setShader(new LinearGradient(0, top, 0, bottom,
                (LINE_COLOR & 0x00FFFFFF) | (70 << 24), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
