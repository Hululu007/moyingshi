package com.github.moyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 今天页的 24 格小时图：没看的小时画灰色小圆点贴底，看过的画橙色细竖条（高度按小时数）。
 * 横轴只画 00:00 / 12:00 / 24:00。
 */
public class ProfileDotsChartView extends View {

    public static final int BAR_COLOR = Color.parseColor("#FFF59E0B");

    private double[] values = new double[0];
    private String[] labels = new String[0];
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float density;

    public ProfileDotsChartView(Context context) {
        super(context);
        init(context);
    }

    public ProfileDotsChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        int dotColor = resolveAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#FF8A8A8A"));
        dotPaint.setColor(dotColor);
        dotPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(BAR_COLOR);
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

    /** values 与 labels 等长；labels 里空串表示该格不画刻度。 */
    public void setData(@Nullable double[] values, @Nullable String[] labels) {
        this.values = values == null ? new double[0] : values.clone();
        this.labels = labels == null ? new String[0] : labels.clone();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, (int) (110 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) return;
        float left = 10 * density;
        float right = getWidth() - 10 * density;
        float top = 12 * density;
        float bottom = getHeight() - 22 * density;
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;
        float barWidth = 5 * density;
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (left + right) / 2 : left + i * (right - left) / (n - 1);
            if (values[i] > 0) {
                float h = (float) (values[i] / max * (bottom - top));
                h = Math.max(h, 8 * density);
                rect.set(x - barWidth / 2, bottom - h, x + barWidth / 2, bottom);
                canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, barPaint);
            } else {
                canvas.drawCircle(x, bottom - 2.5f * density, 2.5f * density, dotPaint);
            }
            if (i < labels.length && !TextUtils.isEmpty(labels[i])) {
                canvas.drawText(labels[i], x, getHeight() - 6 * density, textPaint);
            }
        }
    }
}
