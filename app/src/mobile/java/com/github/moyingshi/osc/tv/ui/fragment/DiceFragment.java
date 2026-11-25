package com.github.moyingshi.osc.tv.ui.fragment;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.DiceEngine;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Filter;
import com.github.moyingshi.osc.tv.bean.Keep;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Value;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.bean.Want;
import com.github.moyingshi.osc.tv.databinding.FragmentDiceBinding;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.setting.DiceSetting;
import com.github.moyingshi.osc.tv.ui.activity.SearchActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.adapter.DiceHistoryAdapter;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 喜好选片页：老虎机摇片 + 4 个池 + 心情 / 类型 / 时长筛选 + 不重复 / 避雷 / 题材屏蔽
 * + 摇片历史 + 我的预设 + 背景换色。状态读写全部走 DiceSetting，想看走 Want 表。
 *
 * <p>第 7 轮版式：老虎机机身 150dp（第 6 轮 220dp）；池子行只剩 4 个池药丸
 * （横向滚动），摇一摇挪到池子行下方做通栏圆角按钮（帽檐紫实心，摇动中显示
 * “摇片中…”并禁用，摇完恢复“再摇一次”）；全页 chips 未选中态改深色半透明底
 * + 白字 + 浅色描边（旧透明底会被 ChipDrawable 与主题浅色 surface 合成浅灰底，
 * 白字压上去看不清）。其余版式与第 6 轮一致。
 * 想看入口：长按历史卡片海报加入想看（原结果行想看按钮随纵向结果列表一起去掉）。
 *
 * <p>影片类型 chips 取自当前站源的筛选数据（见 loadSiteTypes）：SiteApi.homeContent
 * 回来的 Result 里各 Class（type）挂的 filters（SiteApi.setTypes 按 typeId 挂接，
 * 首页筛选弹窗 TypeFilterDialog 用的同一份数据），取其中表“类型/题材”的那一组
 * （key == class，或 name 含“类型/分类/题材”）的 value 名（Value.getN()，空则 getV()），
 * 去重去“全部”后展示；切站后跟着换；某站源没有这类筛选数据时回落到写死列表，
 * 绝不出现空行。类型 chips 仍走 DiceSetting dice_types，进 DiceEngine.filterMoodType
 * 那条筛选路，逻辑与之前一致。
 *
 * <p>约定：dice_duration ""=不限、"long"=长片、"short"=短片；dice_bg 0=跟随主题
 * （默认深色底），其余为 BG_COLORS 下标；摇片历史存 Vod 数组 JSON（DiceSetting history）。
 * 豆瓣 Vod 无站源：点详情走站内搜索片名（SearchActivity），不硬编站源字段。
 */
public class DiceFragment extends BaseFragment implements DiceHistoryAdapter.OnHistoryActionListener {

    private static final String[] BUILTIN_MOODS = {"开心", "治愈", "热血", "悬疑", "浪漫", "搞笑", "烧脑", "轻松"};
    /** 影片类型回落列表：站源无“类型/题材”筛选数据时用，绝不出现空行。 */
    private static final String[] BUILTIN_TYPES = {"喜剧", "爱情", "动作", "科幻", "悬疑", "剧情", "动画", "恐怖"};
    private static final int[] BG_COLORS = {0xFF0B0F16, 0xFF101826, 0xFF1A1025, 0xFF0F1F16, 0xFF241016};
    private static final int HISTORY_LIMIT = 30;
    /** 选中态配色（贴参考图）：池子粉紫实心、心情鼠尾草绿实心、时长/类型/类别紫色实心；未选中深色半透明底 + 白字 + 浅色描边（第 7 轮：旧透明底会被 ChipDrawable 与主题浅色 surface 合成浅灰底，浅字压上去几乎看不见，见 styleSelectableChip）。 */
    private static final int CHIP_POOL_CHECKED_BG = 0xFFAC6F9C;
    private static final int CHIP_MOOD_CHECKED_BG = 0xFF7F9E7F;
    private static final int CHIP_PURPLE_CHECKED_BG = 0xFF8A7CC8;
    /** 未选中底：深 slate、90% 不透明——盖住 Chip 默认的浅色 surface，任何背景色下都是深底白字；描边 50% 白。 */
    private static final int CHIP_UNCHECKED_BG = 0xE61E2430;
    private static final int CHIP_UNCHECKED_STROKE = 0x80FFFFFF;
    /** 摇一摇通栏按钮：启用态帽檐紫实心，禁用态（摇片中）暗紫实心，文字始终白字（禁用态 70% 白）。 */
    private static final int SHAKE_BG = 0xFF8A5CF6;
    private static final int SHAKE_BG_DISABLED = 0xFF4E4368;

    private static final Type VOD_LIST_TYPE = TypeToken.getParameterized(List.class, Vod.class).getType();
    private static final Type PRESET_LIST_TYPE = TypeToken.getParameterized(List.class, DicePreset.class).getType();

    /** 我的预设：当前整套筛选的快照，存 DiceSetting presets（对象数组 JSON）。 */
    static class DicePreset {
        @SerializedName("name")
        String name;
        @SerializedName("pool")
        String pool;
        @SerializedName("category")
        String category;
        @SerializedName("moods")
        List<String> moods;
        @SerializedName("types")
        List<String> types;
        @SerializedName("duration")
        String duration;
        @SerializedName("noRepeat")
        boolean noRepeat;
        @SerializedName("blockFilm")
        boolean blockFilm;
        @SerializedName("blockGenre")
        boolean blockGenre;
    }

    private FragmentDiceBinding mBinding;
    private DiceHistoryAdapter mHistoryAdapter;
    private boolean mBuilding;
    private boolean mShaking;
    /** 预取代次：摇片或更新的预取让进行中的旧预取作废，旧回调到时不再碰老虎机。 */
    private int mPrefetchGen;
    /** 预取失败提示一页只 toast 一次，不刷屏。 */
    private boolean mPrefetchEmptyToastShown;
    /** 本轮摇出的片：onSuccess 存下，停转回调时展示并追加历史。 */
    private List<Vod> mPendingResults;
    /** 类型取数代次：迟到的 homeContent 回调不许盖掉新站源的结果。 */
    private int mTypeGen;
    /** 已加载类型 chips 的站源 key；切站后重取。 */
    private String mSiteTypeKey;
    /** 当前站源“类型/题材”筛选值；null 表示还没取到（先用回落列表展示）。 */
    private List<String> mSiteTypes;

    public static DiceFragment newInstance() {
        return new DiceFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentDiceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mHistoryAdapter = new DiceHistoryAdapter(this);
        mBinding.historyList.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        mBinding.historyList.setAdapter(mHistoryAdapter);
        mBinding.slotMachine.setOnStopListener(names -> onSlotStopped());
        mBinding.slotMachine.setOnPosterClickListener(this::playDirect);
        mBinding.slotMachine.setOnPosterLongClickListener(this::showSlotItemMenu);
        // 首次摇之前也不要“?”：三列先用中性素色占位填充，再预取一波候选海报填进去。
        mBinding.slotMachine.setItems(Collections.<Vod>emptyList(), Collections.<Vod>emptyList());
        updateSlotNames();
        prefetchPrefill();
        applyBg();
        styleShakeButton();
        styleSavePresetChip();
        rebuildPoolChips();
        rebuildCategoryChips();
        rebuildMoodChips();
        rebuildDurationChips();
        rebuildTypeChips();
        rebuildPresets();
        refreshHistory();
        loadSiteTypes();
        mBinding.switchNoRepeat.setChecked(DiceSetting.isNoRepeat());
        mBinding.switchBlockFilm.setChecked(DiceSetting.isBlockFilm());
        mBinding.switchBlockGenre.setChecked(DiceSetting.isBlockGenre());
    }

    @Override
    protected void initEvent() {
        mBinding.shakeButton.setOnClickListener(v -> onShake());
        mBinding.bgButton.setOnClickListener(v -> cycleBg());
        mBinding.addMoodButton.setOnClickListener(v -> showAddMoodDialog());
        mBinding.settingsHeader.setOnClickListener(v -> toggleSettings());
        mBinding.clearHistoryButton.setOnClickListener(v -> clearHistory());
        mBinding.savePresetChip.setOnClickListener(v -> showSavePresetDialog());
        mBinding.switchNoRepeat.setOnCheckedChangeListener((button, checked) -> DiceSetting.putNoRepeat(checked));
        mBinding.switchBlockFilm.setOnCheckedChangeListener((button, checked) -> DiceSetting.putBlockFilm(checked));
        mBinding.switchBlockGenre.setOnCheckedChangeListener((button, checked) -> DiceSetting.putBlockGenre(checked));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mBinding == null) return;
        applyBg();
        refreshHistory();
        // 回到本页时站源可能已换：key 变了才重取类型 chips，不重复拉 homeContent。
        Site home = VodConfig.get().getHome();
        String key = home == null ? "" : home.getKey();
        if (!key.equals(mSiteTypeKey == null ? "" : mSiteTypeKey)) loadSiteTypes();
    }

    // ---------- 摇片 ----------

    private void onShake() {
        if (mShaking || mBinding.slotMachine.isSpinning()) return;
        // 进行中的预取作废：迟到的预填回调不许盖掉本轮摇出的结果。
        mPrefetchGen++;
        mShaking = true;
        mBinding.shakeButton.setEnabled(false);
        mBinding.shakeButton.setText(R.string.dice_shaking);
        DiceEngine.roll(new DiceEngine.Callback() {
            @Override
            public void onSuccess(List<Vod> items, boolean fallback, boolean moodFallback, boolean siteFallback, boolean categoryFallback, List<Vod> fillers) {
                if (!isAdded() || mBinding == null) return;
                if (fallback) Notify.show(R.string.dice_douban_failed);
                if (siteFallback) Notify.show(R.string.dice_site_empty_douban);
                else if (categoryFallback) Notify.show(R.string.dice_category_fallback);
                else if (moodFallback) Notify.show(R.string.dice_mood_fallback);
                mPendingResults = new ArrayList<>(items);
                mBinding.slotMachine.setItems(items, fillers);
                mBinding.slotMachine.spin();
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || mBinding == null) return;
                mShaking = false;
                mBinding.shakeButton.setEnabled(true);
                mBinding.shakeButton.setText(R.string.dice_reshake);
                Notify.show(message);
            }
        });
    }

    /** 老虎机三列依次停下后：下方片名行刷新、追加摇片历史。结果就由三列海报 + 片名展示，不再另列纵向结果列表。 */
    private void onSlotStopped() {
        if (!isAdded() || mBinding == null) return;
        mShaking = false;
        mBinding.shakeButton.setEnabled(true);
        mBinding.shakeButton.setText(R.string.dice_reshake);
        updateSlotNames();
        List<Vod> results = mPendingResults == null ? Collections.emptyList() : new ArrayList<>(mPendingResults);
        appendHistory(results);
        refreshHistory();
    }

    /** 老虎机下方每列片名：取当前每列正中可见格的片名（停下后是结果片，进页未摇时是预取候选片）。 */
    private void updateSlotNames() {
        if (mBinding == null) return;
        List<String> names = mBinding.slotMachine.visibleNames();
        mBinding.slotName0.setText(names.size() > 0 ? names.get(0) : "");
        mBinding.slotName1.setText(names.size() > 1 ? names.get(1) : "");
        mBinding.slotName2.setText(names.size() > 2 ? names.get(2) : "");
    }

    /** 进页 / 切池后预拉一波候选海报填进老虎机；失败只留素色占位并提示一次。 */
    private void prefetchPrefill() {
        final int gen = ++mPrefetchGen;
        DiceEngine.prefetch(candidates -> {
            if (gen != mPrefetchGen || !isAdded() || mBinding == null) return;
            if (mShaking || mBinding.slotMachine.isSpinning()) return;
            if (candidates == null || candidates.isEmpty()) {
                if (!mPrefetchEmptyToastShown) {
                    mPrefetchEmptyToastShown = true;
                    Notify.show(R.string.dice_prefetch_empty);
                }
                return;
            }
            mBinding.slotMachine.setItems(Collections.<Vod>emptyList(), candidates);
            updateSlotNames();
        });
    }

    // ---------- 结果操作 ----------

    @Override
    public void onItemClick(Vod item) {
        openDetail(item);
    }

    /** 历史卡片“再摇一次”：按当前设置重新摇一次。 */
    @Override
    public void onReshake(Vod item) {
        onShake();
    }

    /** 长按老虎机海报：弹避雷 / 收藏二选一（与历史卡片同一套 onBlock/onKeep；避雷进屏蔽名单，下次摇不再出，默认开着“避雷影片”开关时生效）。 */
    private void showSlotItemMenu(Vod item) {
        if (item == null || !isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(item.getName())
                .setItems(new CharSequence[]{
                        getString(R.string.dice_block),
                        getString(R.string.dice_pool_keep)}, (dialog, which) -> {
                    if (which == 0) onBlock(item);
                    else onKeep(item);
                }).show();
    }

    /** 点老虎机海报直接播那部片：有站源调 startDirect 从头播第一集；豆瓣片无站源时后台按片名搜一次，取第一个有站源的结果直接播，搜不到回落搜索页并提示。 */
    private void playDirect(Vod item) {
        if (item == null || !isAdded()) return;
        String siteKey = item.getSiteKey();
        if (!TextUtils.isEmpty(siteKey) && !TextUtils.isEmpty(item.getId())) {
            VideoActivity.startDirect(requireActivity(), siteKey, item.getId(), item.getName(), item.getPic(), item.getRemarks());
            return;
        }
        final String name = item.getName();
        new Thread(() -> {
            Vod found = searchFirstByName(name);
            App.post(() -> {
                if (!isAdded()) return;
                if (found != null) {
                    VideoActivity.startDirect(requireActivity(), found.getSiteKey(), found.getId(), found.getName(), found.getPic(), found.getRemarks());
                } else {
                    Notify.show(R.string.dice_direct_not_found);
                    SearchActivity.start(requireActivity(), name);
                }
            });
        }).start();
    }

    /** 后台按片名在当前站搜一次（SearchActivity 用的同一条 SiteApi.searchContent），取第一个有 siteKey + id 的结果；无站源或搜不到返回 null。 */
    private Vod searchFirstByName(String name) {
        try {
            Site home = VodConfig.get().getHome();
            if (home == null || TextUtils.isEmpty(home.getKey()) || TextUtils.isEmpty(name)) return null;
            Result result = SiteApi.searchContent(home, name, false, "1");
            if (result == null || result.getList() == null) return null;
            for (Vod vod : result.getList()) {
                if (vod != null && !TextUtils.isEmpty(vod.getSiteKey()) && !TextUtils.isEmpty(vod.getId())) return vod;
            }
        } catch (Exception ignored) {
            // 搜不到/搜失败都走回落搜索页，不抛错。
        }
        return null;
    }

    /** 点片进详情：有站源走详情页；豆瓣 Vod 无站源，走站内搜索片名，不硬编站源字段。 */
    private void openDetail(Vod item) {
        if (item == null || !isAdded()) return;
        String siteKey = item.getSiteKey();
        if (!TextUtils.isEmpty(siteKey) && !TextUtils.isEmpty(item.getId())) {
            VideoActivity.start(requireActivity(), siteKey, item.getId(), item.getName(), item.getPic(), null);
        } else {
            SearchActivity.start(requireActivity(), item.getName());
        }
    }

    @Override
    public void onKeep(Vod item) {
        String siteKey = item.getSiteKey();
        if (TextUtils.isEmpty(siteKey)) siteKey = VodConfig.get().getHome().getKey();
        if (TextUtils.isEmpty(siteKey)) {
            Notify.show(R.string.dice_no_site);
            return;
        }
        Site site = VodConfig.get().getSite(siteKey);
        Keep keep = new Keep();
        keep.setKey(siteKey + AppDatabase.SYMBOL + item.getId() + AppDatabase.SYMBOL + VodConfig.getCid());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(item.getPic());
        keep.setVodName(item.getName());
        keep.setSiteName(site == null ? "" : site.getDisplayName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
        Notify.show(R.string.dice_add_keep);
    }

    /** 长按历史卡片海报加入想看（第 6 轮想看入口；原结果行想看按钮随纵向结果列表一起去掉）。 */
    @Override
    public void onWant(Vod item) {
        String siteKey = item.getSiteKey();
        Site site = TextUtils.isEmpty(siteKey) ? null : VodConfig.get().getSite(siteKey);
        Want want = new Want();
        want.setVodName(item.getName());
        want.setVodPic(item.getPic());
        want.setSiteName(site == null ? "" : site.getDisplayName());
        want.setCreateTime(System.currentTimeMillis());
        want.save(siteKey == null ? "" : siteKey, item.getId(), VodConfig.getCid());
        Notify.show(R.string.dice_add_want);
    }

    /** 历史卡片“永久避雷”：进屏蔽名单（与现有避雷同一机制），并从历史记录里删掉这部片。 */
    @Override
    public void onBlock(Vod item) {
        List<String> blocked = new ArrayList<>(DiceSetting.getBlockedNames());
        if (!blocked.contains(item.getName())) {
            blocked.add(item.getName());
            DiceSetting.putBlockedNames(blocked);
        }
        List<Vod> history = new ArrayList<>(getHistory());
        history.removeIf(old -> old.getName().equals(item.getName()));
        DiceSetting.putHistory(App.gson().toJson(history, VOD_LIST_TYPE));
        mHistoryAdapter.remove(item);
        refreshHistory();
        Notify.show(R.string.dice_blocked_toast);
    }

    // ---------- 影片类型：取自当前站源的筛选数据 ----------

    /**
     * 后台拉当前站源 homeContent，取其中表“类型/题材”的筛选值做影片类型 chips。
     * 数据源与首页筛选弹窗 TypeFilterDialog 同款：SiteApi.homeContent 回来的 Result，
     * SiteApi.setTypes 已按 typeId 把 Result.getFilters() 挂到各 Class.getFilters() 上。
     * 取数期间先用回落列表展示，取到空或失败也用回落列表，绝不出现空行。
     */
    private void loadSiteTypes() {
        Site home = VodConfig.get().getHome();
        final String key = home == null ? "" : home.getKey();
        final int gen = ++mTypeGen;
        new Thread(() -> {
            List<String> types = Collections.emptyList();
            try {
                if (home != null && !TextUtils.isEmpty(home.getKey())) {
                    Result result = SiteApi.homeContent(home);
                    if (result != null) types = extractSiteTypes(result);
                }
            } catch (Exception ignored) {
                // 取不到筛选数据就用回落列表，不抛错。
            }
            final List<String> loaded = types;
            App.post(() -> {
                if (gen != mTypeGen || !isAdded() || mBinding == null) return;
                mSiteTypeKey = key;
                mSiteTypes = loaded.isEmpty() ? null : loaded;
                rebuildTypeChips();
            });
        }).start();
    }

    /**
     * 从 homeContent 的 Result 里抽“类型/题材”筛选值：遍历各 Class 的 filters，
     * 取 key == "class" 或 name 含“类型/分类/题材”的那一组（可能多个分类各有一组，
     * 全收），value 名用 Value.getN()（空则 getV()），去重、去掉“全部”后返回。
     */
    private static List<String> extractSiteTypes(Result result) {
        List<String> types = new ArrayList<>();
        if (result.getTypes() == null) return types;
        for (com.github.moyingshi.osc.tv.bean.Class type : result.getTypes()) {
            if (type == null || type.getFilters() == null) continue;
            for (Filter filter : type.getFilters()) {
                if (filter == null || !isTypeFilter(filter)) continue;
                for (Value value : filter.getValue()) {
                    if (value == null) continue;
                    String name = value.getN();
                    if (TextUtils.isEmpty(name)) name = value.getV();
                    if (TextUtils.isEmpty(name)) continue;
                    name = name.trim();
                    if ("全部".equals(name) || "全部类型".equals(name)) continue;
                    if (!types.contains(name)) types.add(name);
                }
            }
        }
        return types;
    }

    /** 表“类型/题材”的筛选组：key 一般是 class，或 name 含“类型/分类/题材”。 */
    private static boolean isTypeFilter(Filter filter) {
        String key = filter.getKey();
        if ("class".equalsIgnoreCase(key)) return true;
        String name = filter.getName();
        return name.contains("类型") || name.contains("分类") || name.contains("题材");
    }

    // ---------- 池 / 心情 / 时长 / 类型 chips ----------

    private void rebuildPoolChips() {
        mBuilding = true;
        mBinding.poolGroup.removeAllViews();
        mBinding.poolGroup.setChipSpacingHorizontal(dp(8));
        String current = DiceSetting.getPool();
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_douban), DiceEngine.POOL_DOUBAN, DiceEngine.POOL_DOUBAN.equals(current), CHIP_POOL_CHECKED_BG, checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_DOUBAN);
        }).setMinWidth(dp(96));
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_site), DiceEngine.POOL_SITE, DiceEngine.POOL_SITE.equals(current), CHIP_POOL_CHECKED_BG, checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_SITE);
        }).setMinWidth(dp(96));
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_keep), DiceEngine.POOL_KEEP, DiceEngine.POOL_KEEP.equals(current), CHIP_POOL_CHECKED_BG, checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_KEEP);
        }).setMinWidth(dp(96));
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_want), DiceEngine.POOL_WANT, DiceEngine.POOL_WANT.equals(current), CHIP_POOL_CHECKED_BG, checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_WANT);
        }).setMinWidth(dp(96));
        mBuilding = false;
    }

    /** 切池：记下新池并重拉一波候选海报填进老虎机（重建 chips 时 mBuilding 拦住，不会误触）。 */
    private void onPoolSelected(String pool) {
        DiceSetting.putPool(pool);
        prefetchPrefill();
    }

    private void rebuildCategoryChips() {
        mBuilding = true;
        mBinding.categoryGroup.removeAllViews();
        mBinding.categoryGroup.setChipSpacingHorizontal(dp(8));
        String current = DiceSetting.getCategory();
        addSingleChip(mBinding.categoryGroup, getString(R.string.dice_category_all), DiceSetting.CATEGORY_ALL, DiceSetting.CATEGORY_ALL.equals(current), CHIP_PURPLE_CHECKED_BG, checked -> {
            if (checked) onCategorySelected(DiceSetting.CATEGORY_ALL);
        });
        addSingleChip(mBinding.categoryGroup, getString(R.string.dice_category_movie), DiceSetting.CATEGORY_MOVIE, DiceSetting.CATEGORY_MOVIE.equals(current), CHIP_PURPLE_CHECKED_BG, checked -> {
            if (checked) onCategorySelected(DiceSetting.CATEGORY_MOVIE);
        });
        addSingleChip(mBinding.categoryGroup, getString(R.string.dice_category_tv), DiceSetting.CATEGORY_TV, DiceSetting.CATEGORY_TV.equals(current), CHIP_PURPLE_CHECKED_BG, checked -> {
            if (checked) onCategorySelected(DiceSetting.CATEGORY_TV);
        });
        mBuilding = false;
    }

    /** 切类别：记下新档并重拉一波候选海报填进老虎机；与切池各记各的键，不互相串。 */
    private void onCategorySelected(String category) {
        DiceSetting.putCategory(category);
        prefetchPrefill();
    }

    private void rebuildDurationChips() {
        mBuilding = true;
        mBinding.durationGroup.removeAllViews();
        mBinding.durationGroup.setChipSpacingHorizontal(dp(8));
        String current = DiceSetting.getDuration();
        addSingleChip(mBinding.durationGroup, getString(R.string.dice_duration_any), "", TextUtils.isEmpty(current), CHIP_PURPLE_CHECKED_BG, checked -> {
            if (checked) DiceSetting.putDuration("");
        });
        addSingleChip(mBinding.durationGroup, getString(R.string.dice_duration_long), "long", "long".equals(current), CHIP_PURPLE_CHECKED_BG, checked -> {
            if (checked) DiceSetting.putDuration("long");
        });
        addSingleChip(mBinding.durationGroup, getString(R.string.dice_duration_short), "short", "short".equals(current), CHIP_PURPLE_CHECKED_BG, checked -> {
            if (checked) DiceSetting.putDuration("short");
        });
        mBuilding = false;
    }

    private void rebuildMoodChips() {
        mBuilding = true;
        mBinding.moodGroupRow1.removeAllViews();
        mBinding.moodGroupRow2.removeAllViews();
        mBinding.moodGroupRow1.setChipSpacingHorizontal(dp(8));
        mBinding.moodGroupRow2.setChipSpacingHorizontal(dp(8));
        List<String> selected = DiceSetting.getMoods();
        List<String> custom = DiceSetting.getCustomMoods();
        List<String> labels = new ArrayList<>(Arrays.asList(BUILTIN_MOODS));
        labels.addAll(custom);
        for (int i = 0; i < labels.size(); i++) {
            String mood = labels.get(i);
            boolean isCustom = i >= BUILTIN_MOODS.length;
            addMultiChip(i % 2 == 0 ? mBinding.moodGroupRow1 : mBinding.moodGroupRow2, mood, selected.contains(mood), isCustom, true, CHIP_MOOD_CHECKED_BG);
        }
        mBuilding = false;
    }

    /** 影片类型 chips：站源筛选值优先（loadSiteTypes 取到后重建），没取到用回落列表；已选但不在新列表里的值追加在末尾，免得选中态悄悄丢了。 */
    private void rebuildTypeChips() {
        mBuilding = true;
        mBinding.typeGroupRow1.removeAllViews();
        mBinding.typeGroupRow2.removeAllViews();
        mBinding.typeGroupRow1.setChipSpacingHorizontal(dp(8));
        mBinding.typeGroupRow2.setChipSpacingHorizontal(dp(8));
        List<String> selected = DiceSetting.getTypes();
        List<String> source = mSiteTypes == null ? new ArrayList<>(Arrays.asList(BUILTIN_TYPES)) : new ArrayList<>(mSiteTypes);
        for (String type : selected) {
            if (!source.contains(type)) source.add(type);
        }
        for (int i = 0; i < source.size(); i++) {
            addMultiChip(i % 2 == 0 ? mBinding.typeGroupRow1 : mBinding.typeGroupRow2, source.get(i), selected.contains(source.get(i)), false, false, CHIP_PURPLE_CHECKED_BG);
        }
        mBuilding = false;
    }

    private interface ChipCheckedListener {
        void onChecked(boolean checked);
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private Chip addSingleChip(ChipGroup group, String text, String tag, boolean checked, int checkedBg, ChipCheckedListener listener) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setTag(tag);
        chip.setCheckable(true);
        chip.setChecked(checked);
        styleSelectableChip(chip, checkedBg);
        final ChipCheckedListener finalListener = listener;
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (mBuilding) return;
            finalListener.onChecked(isChecked);
        });
        group.addView(chip);
        return chip;
    }

    private void addMultiChip(ChipGroup group, String text, boolean checked, boolean custom, boolean mood, int checkedBg) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        styleSelectableChip(chip, checkedBg);
        chip.setTag(custom ? "custom" : "builtin");
        final String label = text;
        final boolean isCustom = custom;
        final boolean isMood = mood;
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (mBuilding) return;
            saveMultiSelection(isMood);
        });
        if (isCustom) {
            chip.setOnLongClickListener(v -> {
                removeCustomMood(label);
                return true;
            });
        }
        group.addView(chip);
    }

    /** 可选 chip 共用：选中实心彩色 + 白字 + 对勾，未选中深色半透明底 + 纯白字 + 浅色描边（贴参考图药丸样式）。未选中底不用 TRANSPARENT：ChipDrawable 会把透明底与主题默认的浅色 surface 合成，实测整片变浅灰底、浅字压上去看不清（第 7 轮用户截图）。 */
    private void styleSelectableChip(Chip chip, int checkedBg) {
        chip.setChipBackgroundColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{checkedBg, CHIP_UNCHECKED_BG}));
        chip.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, Color.WHITE}));
        chip.setCheckedIconVisible(true);
        chip.setCheckedIconTint(ColorStateList.valueOf(Color.WHITE));
        chip.setChipStrokeColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{checkedBg, CHIP_UNCHECKED_STROKE}));
        chip.setChipStrokeWidth(requireContext().getResources().getDisplayMetrics().density);
        chip.setChipCornerRadius(dp(18));
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(40));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    }

    /** 不可选中药丸（+ 保存 / 预设）：深色半透明底 + 白字 + 浅色描边，与可选 chip 的未选中态同款（同款透明底变浅灰问题见 styleSelectableChip）。 */
    private void styleOutlineChip(Chip chip) {
        chip.setChipBackgroundColor(ColorStateList.valueOf(CHIP_UNCHECKED_BG));
        chip.setTextColor(ColorStateList.valueOf(Color.WHITE));
        chip.setCheckedIconVisible(false);
        chip.setChipStrokeColor(ColorStateList.valueOf(CHIP_UNCHECKED_STROKE));
        chip.setChipStrokeWidth(requireContext().getResources().getDisplayMetrics().density);
        chip.setChipCornerRadius(dp(18));
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(40));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    }

    /** 摇一摇通栏按钮（第 7 轮，旧池子行药丸已删）：帽檐紫实心 + 白字，禁用态（摇片中）暗紫实心 + 70% 白字，摇动中也看得清；圆角 24dp 与 48dp 高配成药丸形。 */
    private void styleShakeButton() {
        mBinding.shakeButton.setBackgroundTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{SHAKE_BG_DISABLED, SHAKE_BG}));
        mBinding.shakeButton.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xB3FFFFFF, Color.WHITE}));
        mBinding.shakeButton.setCornerRadius(dp(24));
        mBinding.shakeButton.setTypeface(null, Typeface.BOLD);
    }

    private void styleSavePresetChip() {
        styleOutlineChip(mBinding.savePresetChip);
    }

    /** 两行 chips（心情 / 类型）各扫两行，拼出整组选中态存回去。 */
    private void saveMultiSelection(boolean mood) {
        List<String> selected = new ArrayList<>();
        ChipGroup[] groups = mood
                ? new ChipGroup[]{mBinding.moodGroupRow1, mBinding.moodGroupRow2}
                : new ChipGroup[]{mBinding.typeGroupRow1, mBinding.typeGroupRow2};
        for (ChipGroup group : groups) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof Chip chip && chip.isChecked()) selected.add(String.valueOf(chip.getText()));
            }
        }
        if (mood) DiceSetting.putMoods(selected);
        else DiceSetting.putTypes(selected);
    }

    private void showAddMoodDialog() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.dice_mood_hint);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dice_add_mood)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String mood = String.valueOf(input.getText()).trim();
                    if (TextUtils.isEmpty(mood)) return;
                    List<String> custom = new ArrayList<>(DiceSetting.getCustomMoods());
                    if (!custom.contains(mood) && !Arrays.asList(BUILTIN_MOODS).contains(mood)) {
                        custom.add(mood);
                        DiceSetting.putCustomMoods(custom);
                    }
                    List<String> selected = new ArrayList<>(DiceSetting.getMoods());
                    if (!selected.contains(mood)) {
                        selected.add(mood);
                        DiceSetting.putMoods(selected);
                    }
                    rebuildMoodChips();
                }).show();
    }

    private void removeCustomMood(String mood) {
        List<String> custom = new ArrayList<>(DiceSetting.getCustomMoods());
        custom.remove(mood);
        DiceSetting.putCustomMoods(custom);
        List<String> selected = new ArrayList<>(DiceSetting.getMoods());
        selected.remove(mood);
        DiceSetting.putMoods(selected);
        rebuildMoodChips();
    }

    // ---------- 随机设置折叠 / 背景 ----------

    private void toggleSettings() {
        boolean expanded = mBinding.settingsGroup.getVisibility() == View.VISIBLE;
        mBinding.settingsGroup.setVisibility(expanded ? View.GONE : View.VISIBLE);
        mBinding.settingsArrow.setText(expanded ? "∨" : "∧");
    }

    private void applyBg() {
        if (mBinding == null) return;
        int bg = DiceSetting.getBg();
        if (bg < 0 || bg >= BG_COLORS.length) bg = 0;
        mBinding.diceRoot.setBackgroundColor(BG_COLORS[bg]);
    }

    private void cycleBg() {
        int next = (DiceSetting.getBg() + 1) % BG_COLORS.length;
        DiceSetting.putBg(next);
        applyBg();
    }

    // ---------- 历史 ----------

    private List<Vod> getHistory() {
        return Vod.arrayFrom(DiceSetting.getHistory());
    }

    private void appendHistory(List<Vod> items) {
        if (items == null || items.isEmpty()) return;
        List<Vod> history = new ArrayList<>(getHistory());
        for (Vod item : items) {
            boolean exists = false;
            for (Vod old : history) {
                if (old.getName().equals(item.getName())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) history.add(0, item);
        }
        while (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        DiceSetting.putHistory(App.gson().toJson(history, VOD_LIST_TYPE));
    }

    private void refreshHistory() {
        if (mBinding == null) return;
        List<Vod> history = getHistory();
        mHistoryAdapter.setItems(history);
        mBinding.historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void clearHistory() {
        DiceSetting.putHistory("[]");
        refreshHistory();
    }

    // ---------- 预设 ----------

    private List<DicePreset> getPresets() {
        try {
            List<DicePreset> presets = App.gson().fromJson(DiceSetting.getPresets(), PRESET_LIST_TYPE);
            return presets == null ? Collections.emptyList() : presets;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void rebuildPresets() {
        mBuilding = true;
        mBinding.presetGroup.removeAllViews();
        mBinding.presetGroup.setChipSpacingHorizontal(dp(8));
        for (DicePreset preset : getPresets()) {
            Chip chip = new Chip(requireContext());
            chip.setText(preset.name);
            chip.setCheckable(false);
            styleOutlineChip(chip);
            final DicePreset finalPreset = preset;
            chip.setOnClickListener(v -> applyPreset(finalPreset));
            chip.setOnLongClickListener(v -> {
                deletePreset(finalPreset);
                return true;
            });
            mBinding.presetGroup.addView(chip);
        }
        mBuilding = false;
    }

    private void showSavePresetDialog() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.dice_preset_hint);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dice_preset_save)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String name = String.valueOf(input.getText()).trim();
                    if (TextUtils.isEmpty(name)) return;
                    List<DicePreset> presets = new ArrayList<>(getPresets());
                    DicePreset preset = new DicePreset();
                    preset.name = name;
                    preset.pool = DiceSetting.getPool();
                    preset.category = DiceSetting.getCategory();
                    preset.moods = new ArrayList<>(DiceSetting.getMoods());
                    preset.types = new ArrayList<>(DiceSetting.getTypes());
                    preset.duration = DiceSetting.getDuration();
                    preset.noRepeat = DiceSetting.isNoRepeat();
                    preset.blockFilm = DiceSetting.isBlockFilm();
                    preset.blockGenre = DiceSetting.isBlockGenre();
                    presets.removeIf(item -> name.equals(item.name));
                    presets.add(preset);
                    DiceSetting.putPresets(App.gson().toJson(presets, PRESET_LIST_TYPE));
                    rebuildPresets();
                }).show();
    }

    private void applyPreset(DicePreset preset) {
        if (preset == null) return;
        DiceSetting.putPool(preset.pool == null ? DiceEngine.POOL_DOUBAN : preset.pool);
        DiceSetting.putCategory(preset.category == null ? DiceSetting.CATEGORY_ALL : preset.category);
        DiceSetting.putMoods(preset.moods == null ? Collections.emptyList() : preset.moods);
        DiceSetting.putTypes(preset.types == null ? Collections.emptyList() : preset.types);
        DiceSetting.putDuration(preset.duration == null ? "" : preset.duration);
        DiceSetting.putNoRepeat(preset.noRepeat);
        DiceSetting.putBlockFilm(preset.blockFilm);
        DiceSetting.putBlockGenre(preset.blockGenre);
        rebuildPoolChips();
        rebuildCategoryChips();
        rebuildMoodChips();
        rebuildDurationChips();
        rebuildTypeChips();
        mBinding.switchNoRepeat.setChecked(preset.noRepeat);
        mBinding.switchBlockFilm.setChecked(preset.blockFilm);
        mBinding.switchBlockGenre.setChecked(preset.blockGenre);
        prefetchPrefill();
    }

    private void deletePreset(DicePreset preset) {
        new AlertDialog.Builder(requireContext())
                .setMessage(preset.name)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    List<DicePreset> presets = new ArrayList<>(getPresets());
                    presets.removeIf(item -> preset.name != null && preset.name.equals(item.name));
                    DiceSetting.putPresets(App.gson().toJson(presets, PRESET_LIST_TYPE));
                    rebuildPresets();
                }).show();
    }
}
