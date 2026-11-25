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
 *
 * <p>建带策略（dice-fix-4）：机身高固定 150dp（见 fragment_dice.xml），格高
 * 已知，不等 measure/onSizeChanged——init() 里先按 150dp 建好 36 格占位，
 * 子 View 在第一次 measure 之前就存在；竖带用精确高度（12 格 × 格高）
 * 而不用 WRAP_CONTENT，子 View 永远落在带子自身边界内，不靠事后重测；
 * onLayout() 再按实测高纠正（高度变了或数据代次变了才重建）。
 * 旧写法只在 onSizeChanged（layout 阶段、measure 已过）里建带，实测
 * 三状态全程一片底色：带子 measure 时还是空的，子 View 从没进可见区。
 */
public class SlotMachineView extends LinearLayout {

    public interface OnStopListener {
        void onStop(List<String> names);
    }

    /** 点列上海报：回调该列正中可见格的 Vod（停下后是结果片，进页未摇时是预取候选片）。 */
    public interface OnPosterClickListener {
        void onPosterClick(Vod vod);
    }

    /** 长按列上海报：与点按同款取正中可见格的 Vod（返回 true 表示已处理，点按不再触发）。 */
    public interface OnPosterLongClickListener {
        void onPosterLongClick(Vod vod);
    }

    private static final int COLUMN_COUNT = 3;
    private static final int STRIP_SIZE = 12;
    /** 机身固定高（fragment_dice.xml 里 150dp）：格高先按它建带，布局后再按实测高纠正。 */
    private static final int MACHINE_HEIGHT_DP = 150;

    private final FrameLayout[] mColumns = new FrameLayout[COLUMN_COUNT];
    private final LinearLayout[] mStrips = new LinearLayout[COLUMN_COUNT];
    private final List<List<Vod>> mStripLists = new ArrayList<>();
    private final List<ImageView> mImages = new ArrayList<>();
    private final List<ValueAnimator> mAnimators = new ArrayList<>();
    private final boolean[] mStopped = new boolean[COLUMN_COUNT];

    private final List<Vod> mResults = new ArrayList<>();
    private final List<Vod> mFillers = new ArrayList<>();

    private OnStopListener mListener;
    private OnPosterClickListener mPosterClickListener;
    private OnPosterLongClickListener mPosterLongClickListener;
    private boolean mSpinning;
    /** cancel 触发的 onAnimationEnd 不算正常停转，拦住它误发 onStop。 */
    private boolean mCancelled;
    private int mItemHeight;
    /** 带子是否已建成；上次建带用的格高与数据代次（onLayout 只在变了时重建）。 */
    private boolean mStripsBuilt;
    private int mBuiltItemHeight;
    private int mDataGen;
    private int mBuiltDataGen = -1;

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
        // 格高先按已知的固定机身高取，带子在构造时就能建，不等第一次布局。
        mItemHeight = dp(MACHINE_HEIGHT_DP);
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
            final int columnIndex = i;
            column.setOnClickListener(v -> onColumnClick(columnIndex));
            column.setOnLongClickListener(v -> onColumnLongClick(columnIndex));
            LinearLayout strip = new LinearLayout(getContext());
            strip.setOrientation(LinearLayout.VERTICAL);
            strip.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            column.addView(strip);
            mColumns[i] = column;
            mStrips[i] = strip;
            addView(column);
        }
        // 进页第一帧就有东西：先按空数据建 12 格占位，预取/摇片回来再换海报。
        buildStripLists();
        buildStrips();
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

    public void setOnPosterClickListener(OnPosterClickListener listener) {
        mPosterClickListener = listener;
    }

    public void setOnPosterLongClickListener(OnPosterLongClickListener listener) {
        mPosterLongClickListener = listener;
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
        mDataGen++;
        if (mItemHeight > 0) buildStrips();
        else post(this::ensureStrips);
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
            strip.add(column < mResults.size() ? mResults.get(column) : null);
            mStripLists.set(column, strip);
        }
    }

    private void buildStrips() {
        if (mItemHeight <= 0) return;
        clearImages();
        for (int column = 0; column < COLUMN_COUNT; column++) {
            LinearLayout strip = mStrips[column];
            strip.removeAllViews();
            strip.setTranslationY(0f);
            // 带子定高 12 格 × 格高（精确值，不用 WRAP_CONTENT）：12 格永远落在
            // 带子自身边界内，measure 不再依赖建带时序；列框只裁出正中一格。
            strip.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, STRIP_SIZE * mItemHeight));
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
        mStripsBuilt = true;
        mBuiltItemHeight = mItemHeight;
        mBuiltDataGen = mDataGen;
        requestLayout();
    }

    /** 高度已知且带子未建（或高度/数据变了）时建带；onLayout/onAttachedToWindow 的兜底入口。 */
    private void ensureStrips() {
        if (mItemHeight <= 0) return;
        if (!mStripsBuilt || mBuiltItemHeight != mItemHeight || mBuiltDataGen != mDataGen) buildStrips();
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

    /** 当前每列正中可见格的片名（第 6 轮：老虎机下方每列片名行用它展示；停下后是结果片，进页未摇时是预取候选片）。 */
    public List<String> visibleNames() {
        List<String> names = new ArrayList<>();
        for (int column = 0; column < COLUMN_COUNT; column++) {
            int index = 0;
            if (mItemHeight > 0) {
                index = Math.round(-mStrips[column].getTranslationY() / (float) mItemHeight);
                if (index < 0) index = 0;
                if (index >= STRIP_SIZE) index = STRIP_SIZE - 1;
            }
            List<Vod> strip = mStripLists.get(column);
            Vod vod = index < strip.size() ? strip.get(index) : null;
            names.add(vod == null || TextUtils.isEmpty(vod.getName()) ? "" : vod.getName());
        }
        return names;
    }

    private List<String> resultNames() {
        List<String> names = new ArrayList<>();
        for (Vod vod : mResults) names.add(vod == null || TextUtils.isEmpty(vod.getName()) ? "" : vod.getName());
        return names;
    }

    /** 点列上海报：正在滚动直接忽略；否则按当前竖带位移算正中可见格下标，回调该格的 Vod。 */
    private void onColumnClick(int column) {
        if (mSpinning || mPosterClickListener == null || mItemHeight <= 0) return;
        int index = Math.round(-mStrips[column].getTranslationY() / (float) mItemHeight);
        if (index < 0) index = 0;
        if (index >= STRIP_SIZE) index = STRIP_SIZE - 1;
        List<Vod> strip = mStripLists.get(column);
        Vod vod = index < strip.size() ? strip.get(index) : null;
        if (vod != null && !TextUtils.isEmpty(vod.getName())) mPosterClickListener.onPosterClick(vod);
    }

    /** 长按列上海报：与点按同款取正中可见格的 Vod；滚动中、空位、无监听时返回 false（不消费事件）。 */
    private boolean onColumnLongClick(int column) {
        if (mSpinning || mPosterLongClickListener == null || mItemHeight <= 0) return false;
        int index = Math.round(-mStrips[column].getTranslationY() / (float) mItemHeight);
        if (index < 0) index = 0;
        if (index >= STRIP_SIZE) index = STRIP_SIZE - 1;
        List<Vod> strip = mStripLists.get(column);
        Vod vod = index < strip.size() ? strip.get(index) : null;
        if (vod == null || TextUtils.isEmpty(vod.getName())) return false;
        mPosterLongClickListener.onPosterLongClick(vod);
        return true;
    }

    private void cancelAnimators() {
        mCancelled = true;
        for (ValueAnimator animator : mAnimators) animator.cancel();
        mAnimators.clear();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::ensureStrips);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int h = bottom - top;
        if (h <= 0) return;
        // 实测高与先取的 150dp 不一致时按实测重建；高度/数据没变时 ensureStrips 是空操作。
        if (mItemHeight != h) mItemHeight = h;
        ensureStrips();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimators();
        clearImages();
        mSpinning = false;
        // Glide clear 会把已加载海报的 drawable 置空：重挂时按 ensureStrips 重建带子补回占位/海报。
        mStripsBuilt = false;
        super.onDetachedFromWindow();
    }
}
