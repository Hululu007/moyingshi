package com.github.moyingshi.osc.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.github.moyingshi.osc.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 喜好页老虎机：三列海报滚动（仿 Rove 外观自画）。
 * 每列是一条海报竖带，点摇后减速、依次停下（900/1400/1900ms 错峰），
 * 每列停在摇出的那部片海报正中；海报缺失/加载失败用素色占位，绝不画“?”。
 * 首次摇之前三列也是中性素色占位。
 */
public class SlotMachineView extends LinearLayout {

    public interface OnStopListener {
        void onStop(List<String> names);
    }

    private static final int COLUMN_COUNT = 3;
    private static final int STRIP_SIZE = 12;

    private final FrameLayout[] mColumns = new FrameLayout[COLUMN_COUNT];
    private final LinearLayout[] mStrips = new LinearLayout[COLUMN_COUNT];
    private final List<List<Vod>> mStripLists = new ArrayList<>();
    private final List<ImageView> mImages = new ArrayList<>();
    private final List<ValueAnimator> mAnimators = new ArrayList<>();
    private final boolean[] mStopped = new boolean[COLUMN_COUNT];

    private final List<Vod> mResults = new ArrayList<>();
    private final List<Vod> mFillers = new ArrayList<>();

    private OnStopListener mListener;
    private boolean mSpinning;
    /** cancel 触发的 onAnimationEnd 不算正常停转，拦住它误发 onStop。 */
    private boolean mCancelled;
    private int mItemHeight;

    public SlotMachineView(Context context) {
        super(context);
        init();
    }

    public SlotMachineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF141A26"));
        bg.setCornerRadius(24f);
        setBackground(bg);
        for (int i = 0; i < COLUMN_COUNT; i++) {
            mStripLists.add(new ArrayList<>());
            FrameLayout column = new FrameLayout(getContext());
            LayoutParams columnParams = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            int gap = dp(2);
            columnParams.setMargins(i == 0 ? 0 : gap, 0, i == COLUMN_COUNT - 1 ? 0 : gap, 0);
            column.setLayoutParams(columnParams);
            column.setClipChildren(true);
            LinearLayout strip = new LinearLayout(getContext());
            strip.setOrientation(LinearLayout.VERTICAL);
            strip.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            column.addView(strip);
            mColumns[i] = column;
            mStrips[i] = strip;
            addView(column);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 素色占位：填充与机身底色明显拉开（#FF2A3648 对 #FF141A26），再加 1dp 描边与圆角，任何时候都不许一整块死黑。 */
    private Drawable placeholder() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#FF2A3648"));
        drawable.setStroke(dp(1), Color.parseColor("#FF3D4E66"));
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    public void setOnStopListener(OnStopListener listener) {
        mListener = listener;
    }

    public boolean isSpinning() {
        return mSpinning;
    }

    /** 设定本轮摇出的 3 部片 + 池内其它片做滚动填充（不够 3 部不崩，缺位用占位）。 */
    public void setItems(List<Vod> results, List<Vod> fillers) {
        mResults.clear();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            mResults.add(results != null && i < results.size() ? results.get(i) : null);
        }
        mFillers.clear();
        if (fillers != null) mFillers.addAll(fillers);
        buildStripLists();
        if (mItemHeight > 0) buildStrips();
    }

    /** 兼容旧调用：只有片名、无海报时整列用素色占位。 */
    public void setItems(List<String> names) {
        List<Vod> results = new ArrayList<>();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            Vod vod = new Vod();
            vod.setName(names != null && i < names.size() && !TextUtils.isEmpty(names.get(i)) ? names.get(i) : "");
            results.add(vod);
        }
        setItems(results, null);
    }

    /** 每列滚动带：前 11 格为池内其它片海报（打乱循环），最后一格是本列摇出的片。 */
    private void buildStripLists() {
        List<Vod> shuffled = new ArrayList<>(mFillers);
        Collections.shuffle(shuffled, new Random());
        for (int column = 0; column < COLUMN_COUNT; column++) {
            List<Vod> strip = new ArrayList<>();
            for (int i = 0; i < STRIP_SIZE - 1; i++) {
                strip.add(shuffled.isEmpty() ? null : shuffled.get((column * 5 + i) % shuffled.size()));
            }
            strip.add(mResults.get(column));
            mStripLists.set(column, strip);
        }
    }

    private void buildStrips() {
        clearImages();
        for (int column = 0; column < COLUMN_COUNT; column++) {
            LinearLayout strip = mStrips[column];
            strip.removeAllViews();
            strip.setTranslationY(0f);
            for (Vod vod : mStripLists.get(column)) {
                ImageView image = new ImageView(getContext());
                image.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, mItemHeight));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageDrawable(placeholder());
                String pic = vod == null ? null : vod.getPic();
                if (!TextUtils.isEmpty(pic)) {
                    Glide.with(getContext()).load(pic).centerCrop().placeholder(placeholder()).error(placeholder()).into(image);
                }
                mImages.add(image);
                strip.addView(image);
            }
        }
    }

    private void clearImages() {
        for (ImageView image : mImages) {
            try {
                Glide.with(getContext()).clear(image);
            } catch (Exception ignored) {
                // View 已脱离上下文时 Glide clear 可能抛错，忽略。
            }
        }
        mImages.clear();
    }

    /** 开始滚动：三列依次停下，全部停下后回调 onStop。 */
    public void spin() {
        if (mSpinning || mItemHeight <= 0) return;
        cancelAnimators();
        mCancelled = false;
        mSpinning = true;
        for (int i = 0; i < COLUMN_COUNT; i++) mStopped[i] = false;
        final int columnCount = COLUMN_COUNT;
        final float target = (STRIP_SIZE - 1) * (float) mItemHeight;
        for (int i = 0; i < COLUMN_COUNT; i++) {
            final int column = i;
            final LinearLayout strip = mStrips[column];
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(900 + column * 500L);
            animator.setInterpolator(new DecelerateInterpolator(1.5f));
            animator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                strip.setTranslationY(-target * fraction);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mCancelled) return;
                    mStopped[column] = true;
                    strip.setTranslationY(-target);
                    boolean allStopped = true;
                    for (int j = 0; j < columnCount; j++) {
                        if (!mStopped[j]) {
                            allStopped = false;
                            break;
                        }
                    }
                    if (allStopped) {
                        mSpinning = false;
                        if (mListener != null) mListener.onStop(resultNames());
                    }
                }
            });
            mAnimators.add(animator);
            animator.start();
        }
    }

    private List<String> resultNames() {
        List<String> names = new ArrayList<>();
        for (Vod vod : mResults) names.add(vod == null || TextUtils.isEmpty(vod.getName()) ? "" : vod.getName());
        return names;
    }

    private void cancelAnimators() {
        mCancelled = true;
        for (ValueAnimator animator : mAnimators) animator.cancel();
        mAnimators.clear();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h <= 0) return;
        if (mItemHeight != h) {
            mItemHeight = h;
            buildStrips();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimators();
        clearImages();
        mSpinning = false;
        super.onDetachedFromWindow();
    }
}
