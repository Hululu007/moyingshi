package com.github.moyingshi.osc.tv.ui.fragment;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
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
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Keep;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.bean.Want;
import com.github.moyingshi.osc.tv.databinding.FragmentDiceBinding;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.setting.DiceSetting;
import com.github.moyingshi.osc.tv.ui.activity.SearchActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.adapter.DiceResultAdapter;
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
 * 外观仿 Rove fragment_rove_dice 自画（深色底、标题 + 背景按钮、老虎机、胶囊行、
 * 提示语、结果区、心情 chips、随机设置折叠区、历史列表），不搬对方美术资源与代码。
 *
 * <p>约定：dice_duration ""=不限、"long"=长片、"short"=短片；dice_bg 0=跟随主题
 * （默认深色底），其余为 BG_COLORS 下标；摇片历史存 Vod 数组 JSON（DiceSetting history）。
 * 豆瓣 Vod 无站源：点详情走站内搜索片名（SearchActivity），不硬编站源字段。
 */
public class DiceFragment extends BaseFragment implements DiceResultAdapter.OnActionListener {

    private static final String[] BUILTIN_MOODS = {"开心", "治愈", "热血", "悬疑", "浪漫", "搞笑", "烧脑", "轻松"};
    private static final String[] BUILTIN_TYPES = {"喜剧", "爱情", "动作", "科幻", "悬疑", "剧情", "动画", "恐怖"};
    private static final int[] BG_COLORS = {0xFF0B0F16, 0xFF101826, 0xFF1A1025, 0xFF0F1F16, 0xFF241016};
    private static final int HISTORY_LIMIT = 30;
    /** chip 选中态：实心主色填充 + 白字 + 对勾，深色底上一眼可见；未选中为深灰底 + 浅字。 */
    private static final int CHIP_CHECKED_BG = 0xFF0B57D0;
    private static final int CHIP_UNCHECKED_BG = 0xFF232D3D;

    private static final Type VOD_LIST_TYPE = TypeToken.getParameterized(List.class, Vod.class).getType();
    private static final Type PRESET_LIST_TYPE = TypeToken.getParameterized(List.class, DicePreset.class).getType();

    /** 我的预设：当前整套筛选的快照，存 DiceSetting presets（对象数组 JSON）。 */
    static class DicePreset {
        @SerializedName("name")
        String name;
        @SerializedName("pool")
        String pool;
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
    private DiceResultAdapter mResultAdapter;
    private DiceResultAdapter mHistoryAdapter;
    private boolean mBuilding;
    private boolean mShaking;
    /** 预取代次：摇片或更新的预取让进行中的旧预取作废，旧回调到时不再碰老虎机。 */
    private int mPrefetchGen;
    /** 预取失败提示一页只 toast 一次，不刷屏。 */
    private boolean mPrefetchEmptyToastShown;
    /** 本轮摇出的片：onSuccess 存下，停转回调时展示并追加历史。 */
    private List<Vod> mPendingResults;

    public static DiceFragment newInstance() {
        return new DiceFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentDiceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mResultAdapter = new DiceResultAdapter(this);
        mHistoryAdapter = new DiceResultAdapter(this);
        mBinding.resultList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.resultList.setAdapter(mResultAdapter);
        mBinding.historyList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.historyList.setAdapter(mHistoryAdapter);
        mBinding.slotMachine.setOnStopListener(names -> onSlotStopped());
        // 首次摇之前也不要“?”：三列先用中性素色占位填充，再预取一波候选海报填进去。
        mBinding.slotMachine.setItems(Collections.<Vod>emptyList(), Collections.<Vod>emptyList());
        prefetchPrefill();
        applyBg();
        rebuildPoolChips();
        rebuildMoodChips();
        rebuildDurationChips();
        rebuildTypeChips();
        rebuildPresets();
        refreshHistory();
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
        mBinding.savePresetButton.setOnClickListener(v -> showSavePresetDialog());
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
            public void onSuccess(List<Vod> items, boolean fallback, boolean moodFallback, boolean siteFallback, List<Vod> fillers) {
                if (!isAdded() || mBinding == null) return;
                if (fallback) Notify.show(R.string.dice_douban_failed);
                if (siteFallback) Notify.show(R.string.dice_site_empty_douban);
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

    /** 老虎机三列依次停下后：展示结果、追加摇片历史。 */
    private void onSlotStopped() {
        if (!isAdded() || mBinding == null) return;
        mShaking = false;
        mBinding.shakeButton.setEnabled(true);
        mBinding.shakeButton.setText(R.string.dice_reshake);
        List<Vod> results = mPendingResults == null ? Collections.emptyList() : new ArrayList<>(mPendingResults);
        mResultAdapter.setItems(results);
        appendHistory(results);
        refreshHistory();
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
        });
    }

    // ---------- 结果操作 ----------

    @Override
    public void onItemClick(Vod item) {
        openDetail(item);
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

    @Override
    public void onBlock(Vod item) {
        List<String> blocked = new ArrayList<>(DiceSetting.getBlockedNames());
        if (!blocked.contains(item.getName())) {
            blocked.add(item.getName());
            DiceSetting.putBlockedNames(blocked);
        }
        Notify.show(R.string.dice_blocked_toast);
        mResultAdapter.remove(item);
    }

    // ---------- 池 / 心情 / 时长 / 类型 chips ----------

    private void rebuildPoolChips() {
        mBuilding = true;
        mBinding.poolGroup.removeAllViews();
        String current = DiceSetting.getPool();
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_douban), DiceEngine.POOL_DOUBAN, DiceEngine.POOL_DOUBAN.equals(current), checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_DOUBAN);
        });
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_site), DiceEngine.POOL_SITE, DiceEngine.POOL_SITE.equals(current), checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_SITE);
        });
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_keep), DiceEngine.POOL_KEEP, DiceEngine.POOL_KEEP.equals(current), checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_KEEP);
        });
        addSingleChip(mBinding.poolGroup, getString(R.string.dice_pool_want), DiceEngine.POOL_WANT, DiceEngine.POOL_WANT.equals(current), checked -> {
            if (checked) onPoolSelected(DiceEngine.POOL_WANT);
        });
        mBuilding = false;
    }

    /** 切池：记下新池并重拉一波候选海报填进老虎机（重建 chips 时 mBuilding 拦住，不会误触）。 */
    private void onPoolSelected(String pool) {
        DiceSetting.putPool(pool);
        prefetchPrefill();
    }

    private void rebuildDurationChips() {
        mBuilding = true;
        mBinding.durationGroup.removeAllViews();
        String current = DiceSetting.getDuration();
        addSingleChip(mBinding.durationGroup, getString(R.string.dice_duration_any), "", TextUtils.isEmpty(current), checked -> {
            if (checked) DiceSetting.putDuration("");
        });
        addSingleChip(mBinding.durationGroup, getString(R.string.dice_duration_long), "long", "long".equals(current), checked -> {
            if (checked) DiceSetting.putDuration("long");
        });
        addSingleChip(mBinding.durationGroup, getString(R.string.dice_duration_short), "short", "short".equals(current), checked -> {
            if (checked) DiceSetting.putDuration("short");
        });
        mBuilding = false;
    }

    private void rebuildMoodChips() {
        mBuilding = true;
        mBinding.moodGroup.removeAllViews();
        List<String> selected = DiceSetting.getMoods();
        List<String> custom = DiceSetting.getCustomMoods();
        for (String mood : BUILTIN_MOODS) {
            addMultiChip(mBinding.moodGroup, mood, selected.contains(mood), false);
        }
        for (String mood : custom) {
            addMultiChip(mBinding.moodGroup, mood, selected.contains(mood), true);
        }
        mBuilding = false;
    }

    private void rebuildTypeChips() {
        mBuilding = true;
        mBinding.typeGroup.removeAllViews();
        List<String> selected = DiceSetting.getTypes();
        for (String type : BUILTIN_TYPES) {
            addMultiChip(mBinding.typeGroup, type, selected.contains(type), false);
        }
        mBuilding = false;
    }

    private interface ChipCheckedListener {
        void onChecked(boolean checked);
    }

    private void addSingleChip(ChipGroup group, String text, String tag, boolean checked, ChipCheckedListener listener) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setTag(tag);
        chip.setCheckable(true);
        chip.setChecked(checked);
        styleSelectableChip(chip);
        final ChipCheckedListener finalListener = listener;
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (mBuilding) return;
            finalListener.onChecked(isChecked);
        });
        group.addView(chip);
    }

    private void addMultiChip(ChipGroup group, String text, boolean checked, boolean custom) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        styleSelectableChip(chip);
        chip.setTag(custom ? "custom" : "builtin");
        final String label = text;
        final boolean isCustom = custom;
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (mBuilding) return;
            saveMultiSelection(group, group == mBinding.moodGroup);
        });
        if (isCustom) {
            chip.setOnLongClickListener(v -> {
                removeCustomMood(label);
                return true;
            });
        }
        group.addView(chip);
    }

    /** 全部可选 chip 共用：选中实心主色 + 白字 + 对勾，未选中深灰底 + 浅字。 */
    private void styleSelectableChip(Chip chip) {
        chip.setChipBackgroundColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{CHIP_CHECKED_BG, CHIP_UNCHECKED_BG}));
        chip.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, 0xFFE6EAF2}));
        chip.setCheckedIconVisible(true);
        chip.setCheckedIconTint(ColorStateList.valueOf(Color.WHITE));
        chip.setChipStrokeColor(ColorStateList.valueOf(0x55FFFFFF));
        chip.setChipStrokeWidth(requireContext().getResources().getDisplayMetrics().density);
    }

    private void saveMultiSelection(ChipGroup group, boolean mood) {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Chip chip && chip.isChecked()) selected.add(String.valueOf(chip.getText()));
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
        mBinding.settingsArrow.setText(expanded ? "▸" : "▾");
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
        for (DicePreset preset : getPresets()) {
            Chip chip = new Chip(requireContext());
            chip.setText(preset.name);
            chip.setCheckable(false);
            chip.setChipBackgroundColor(ColorStateList.valueOf(CHIP_UNCHECKED_BG));
            chip.setTextColor(Color.WHITE);
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
        DiceSetting.putMoods(preset.moods == null ? Collections.emptyList() : preset.moods);
        DiceSetting.putTypes(preset.types == null ? Collections.emptyList() : preset.types);
        DiceSetting.putDuration(preset.duration == null ? "" : preset.duration);
        DiceSetting.putNoRepeat(preset.noRepeat);
        DiceSetting.putBlockFilm(preset.blockFilm);
        DiceSetting.putBlockGenre(preset.blockGenre);
        rebuildPoolChips();
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
