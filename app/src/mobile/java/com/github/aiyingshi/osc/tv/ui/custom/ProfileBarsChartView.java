package com.github.aiyingshi.osc.tv.ui.custom;

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
 * 周柱状图：7 根竖条（周日–周六），有数据的画橙色条、没数据的画灰色小药丸；
 * 横轴只标周日 / 周三 / 周六。本周观看页用。
 */
public class ProfileBarsChartView extends View {

    public static final int BAR_COLOR = Color.parseColor("#FFF59E0B");

    private double[] values = new double[0];
    private String[] labels = new String[0];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float density;

    public ProfileBarsChartView(Context context) {
        super(context);
        init(context);
    }

    public ProfileBarsChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        barPaint.setColor(BAR_COLOR);
        barPaint.setStyle(Paint.Style.FILL);
        int emptyColor = resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
        emptyPaint.setColor(emptyColor);
        emptyPaint.setStyle(Paint.Style.FILL);
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
        setMeasuredDimension(width, (int) (88 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) return;
        float left = 6 * density;
        float right = getWidth() - 6 * density;
        float top = 8 * density;
        float bottom = getHeight() - 20 * density;
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;
        float slot = (right - left) / n;
        float barWidth = Math.min(20 * density, slot * 0.55f);
        float radius = 4 * density;
        for (int i = 0; i < n; i++) {
            float cx = left + slot * i + slot / 2;
            if (values[i] > 0) {
                float h = (float) (values[i] / max * (bottom - top));
                h = Math.max(h, 6 * density);
                rect.set(cx - barWidth / 2, bottom - h, cx + barWidth / 2, bottom);
                canvas.drawRoundRect(rect, radius, radius, barPaint);
            } else {
                float h = 6 * density;
                rect.set(cx - barWidth / 2, bottom - h, cx + barWidth / 2, bottom);
                canvas.drawRoundRect(rect, h / 2, h / 2, emptyPaint);
            }
            if (i < labels.length && !TextUtils.isEmpty(labels[i])) {
                canvas.drawText(labels[i], cx, getHeight() - 4 * density, textPaint);
            }
        }
    }
}
