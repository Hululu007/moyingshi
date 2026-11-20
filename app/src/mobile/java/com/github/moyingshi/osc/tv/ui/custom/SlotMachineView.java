package com.github.moyingshi.osc.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 喜好页老虎机：自研 Canvas 三列滚轮。
 * 每列循环滚动片名，点摇后减速、依次停下（每列停转时间错开），全部停下后回调。
 * 外观自画，深色底，与喜好页风格一致。
 */
public class SlotMachineView extends View {

    public interface OnStopListener {
        void onStop(List<String> names);
    }

    private static final int COLUMN_COUNT = 3;
    private static final int STRIP_SIZE = 30;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private final List<String> mNames = new ArrayList<>();
    private final float[] mOffset = new float[COLUMN_COUNT];
    private final float[] mEndOffset = new float[COLUMN_COUNT];
    private final boolean[] mStopped = new boolean[COLUMN_COUNT];
    private final List<ValueAnimator> mAnimators = new ArrayList<>();

    private OnStopListener mListener;
    private boolean mSpinning;
    /** cancel 触发的 onAnimationEnd 不算正常停转，拦住它误发 onStop。 */
    private boolean mCancelled;

    public SlotMachineView(Context context) {
        super(context);
        init();
    }

    public SlotMachineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mBgPaint.setColor(Color.parseColor("#FF141A26"));
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mLinePaint.setColor(Color.parseColor("#33FFFFFF"));
        mLinePaint.setStrokeWidth(2f);
        mNames.add("?");
        mNames.add("?");
        mNames.add("?");
    }

    public void setOnStopListener(OnStopListener listener) {
        mListener = listener;
    }

    public boolean isSpinning() {
        return mSpinning;
    }

    /** 设定本轮摇出的 3 个片名（不足 3 个时用“—”补位）。 */
    public void setItems(List<String> names) {
        mNames.clear();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            String name = names != null && i < names.size() ? names.get(i) : "—";
            mNames.add(TextUtils.isEmpty(name) ? "—" : name);
        }
        for (int i = 0; i < COLUMN_COUNT; i++) mOffset[i] = 0f;
        invalidate();
    }

    /** 开始滚动：三列依次停下，全部停下后回调 onStop。 */
    public void spin() {
        if (mSpinning) return;
        cancelAnimators();
        mCancelled = false;
        mSpinning = true;
        for (int i = 0; i < COLUMN_COUNT; i++) mStopped[i] = false;
        final int columnCount = COLUMN_COUNT;
        for (int i = 0; i < COLUMN_COUNT; i++) {
            final int column = i;
            float itemHeight = getItemHeight();
            mEndOffset[column] = (STRIP_SIZE - 2) * itemHeight;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(900 + column * 500L);
            animator.setInterpolator(new DecelerateInterpolator(1.5f));
            animator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                mOffset[column] = mEndOffset[column] * fraction;
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mCancelled) return;
                    mStopped[column] = true;
                    mOffset[column] = mEndOffset[column];
                    invalidate();
                    boolean allStopped = true;
                    for (int j = 0; j < columnCount; j++) {
                        if (!mStopped[j]) {
                            allStopped = false;
                            break;
                        }
                    }
                    if (allStopped) {
                        mSpinning = false;
                        if (mListener != null) mListener.onStop(new ArrayList<>(mNames));
                    }
                }
            });
            mAnimators.add(animator);
            animator.start();
        }
    }

    private float getItemHeight() {
        return getHeight() > 0 ? getHeight() / 3f : 1f;
    }

    private void cancelAnimators() {
        mCancelled = true;
        for (ValueAnimator animator : mAnimators) animator.cancel();
        mAnimators.clear();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimators();
        mSpinning = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        mRect.set(0f, 0f, width, height);
        canvas.drawRoundRect(mRect, 24f, 24f, mBgPaint);
        float columnWidth = width / (float) COLUMN_COUNT;
        float itemHeight = height / 3f;
        mTextPaint.setTextSize(Math.min(48f, columnWidth * 0.24f));
        Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
        float textCenterOffset = (metrics.descent + metrics.ascent) / 2f;
        for (int column = 0; column < COLUMN_COUNT; column++) {
            float left = column * columnWidth;
            if (column > 0) canvas.drawLine(left, 16f, left, height - 16f, mLinePaint);
            canvas.save();
            canvas.clipRect(left, 0f, left + columnWidth, height);
            for (int i = 0; i < STRIP_SIZE; i++) {
                float centerY = i * itemHeight + itemHeight / 2f - mOffset[column];
                if (centerY < -itemHeight || centerY > height + itemHeight) continue;
                String text = stripText(column, i);
                float distance = Math.abs(centerY - height / 2f);
                int alpha = distance < itemHeight / 2f ? 255 : 110;
                mTextPaint.setAlpha(alpha);
                canvas.drawText(ellipsize(text, columnWidth - 24f), left + columnWidth / 2f, centerY - textCenterOffset, mTextPaint);
            }
            canvas.restore();
        }
        mTextPaint.setAlpha(255);
        canvas.drawLine(0f, height / 2f - itemHeight / 2f, width, height / 2f - itemHeight / 2f, mLinePaint);
        canvas.drawLine(0f, height / 2f + itemHeight / 2f, width, height / 2f + itemHeight / 2f, mLinePaint);
    }

    /** 滚动带：前 29 格循环复用 3 个片名，最后一格是本列的最终结果。 */
    private String stripText(int column, int index) {
        if (index >= STRIP_SIZE - 1) return mNames.get(column);
        return mNames.get((index + column) % COLUMN_COUNT);
    }

    private String ellipsize(String text, float maxWidth) {
        if (text == null) return "";
        if (mTextPaint.measureText(text) <= maxWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0 && mTextPaint.measureText(text.substring(0, end) + suffix) > maxWidth) end--;
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }
}
