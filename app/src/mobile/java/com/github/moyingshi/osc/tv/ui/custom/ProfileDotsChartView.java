package com.github.moyingshi.osc.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.moyingshi.osc.tv.R;

/**
 * 点图：每格一个点，点的高度按值、大小按值微调；横轴只画给出的刻度（其余格留空）。
 * 今天页用 24 格（00:00 / 12:00 / 24:00），本周/本月页按天。
 */
public class ProfileDotsChartView extends View {

    private double[] values = new double[0];
    private String[] labels = new String[0];
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        int primary = resolveAttr(context, R.attr.colorPrimary, Color.parseColor("#FF0B57D0"));
        dotPaint.setColor(primary);
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
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (left + right) / 2 : left + i * (right - left) / (n - 1);
            float y = (float) (bottom - values[i] / max * (bottom - top));
            float r = (float) (2.5 * density + values[i] / max * 2.5 * density);
            canvas.drawCircle(x, y, Math.max(r, 2 * density), dotPaint);
            if (i < labels.length && !TextUtils.isEmpty(labels[i])) {
                canvas.drawText(labels[i], x, getHeight() - 6 * density, textPaint);
            }
        }
    }
}
