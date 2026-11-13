package com.github.moyingshi.osc.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.Updater;
import com.github.moyingshi.osc.tv.api.config.LiveConfig;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.api.config.WallConfig;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.Live;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.cache.CacheCenter;
import com.github.moyingshi.osc.tv.cache.CachePolicyStore;
import com.github.moyingshi.osc.tv.databinding.FragmentSettingBinding;
import com.github.moyingshi.osc.tv.databinding.ItemDownloadDramaBinding;
import com.github.moyingshi.osc.tv.databinding.ItemDownloadEpisodeBinding;
import com.github.moyingshi.osc.tv.databinding.ItemSettingCardRowBinding;
import com.github.moyingshi.osc.tv.databinding.ItemSettingCardRowConfigBinding;
import com.github.moyingshi.osc.tv.databinding.ItemSettingGroupTitleBinding;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.download.DownloadPathUtil;
import com.github.moyingshi.osc.tv.download.DownloadTask;
import com.github.moyingshi.osc.tv.download.VideoDownloadManager;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.event.RefreshEvent;
import com.github.moyingshi.osc.tv.gitcloud.GitCloudAccountStore;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.impl.ConfigListener;
import com.github.moyingshi.osc.tv.impl.LiveListener;
import com.github.moyingshi.osc.tv.impl.SiteListener;
import com.github.moyingshi.osc.tv.lab.LabActivity;
import com.github.moyingshi.osc.tv.setting.ConfigSyncPolicy;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.setting.SiteHealthStore;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.activity.HomeActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.ui.dialog.AboutDialog;
import com.github.moyingshi.osc.tv.ui.dialog.AppearanceDialog;
import com.github.moyingshi.osc.tv.ui.dialog.BackupProgressDialog;
import com.github.moyingshi.osc.tv.ui.dialog.CacheManagementDialog;
import com.github.moyingshi.osc.tv.ui.dialog.ChoiceDialog;
import com.github.moyingshi.osc.tv.ui.dialog.GitCloudDialog;
import com.github.moyingshi.osc.tv.ui.dialog.HistoryDialog;
import com.github.moyingshi.osc.tv.ui.dialog.LiveDialog;
import com.github.moyingshi.osc.tv.ui.dialog.LocalSourceDialog;
import com.github.moyingshi.osc.tv.ui.dialog.OneKeySyncDialog;
import com.github.moyingshi.osc.tv.ui.dialog.RestoreDialog;
import com.github.moyingshi.osc.tv.ui.dialog.SiteDialog;
import com.github.moyingshi.osc.tv.ui.dialog.SiteHealthDialog;
import com.github.moyingshi.osc.tv.utils.AppVersion;
import com.github.moyingshi.osc.tv.utils.FileUtil;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.android.material.card.MaterialCardView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BieCuiLe-style grouped settings page: big title + search icon, one rounded card per
 * group, each row a colored circle icon + title + subtitle + value + chevron.
 * Groups and rows are built dynamically (see buildGroups); every pre-restyle row keeps
 * its destination — see docs/ui-restyle-biecuile.md §3.1 row map.
 */
public class SettingFragment extends BaseFragment implements ConfigListener, SiteListener, LiveListener {

    // Row ids for live values and for settings-search deep links (SettingSearchIndex).
    public static final String ROW_VOD = "vod";
    public static final String ROW_LIVE = "live";
    public static final String ROW_WALL = "wall";
    public static final String ROW_APPEARANCE = "appearance";
    public static final String ROW_WIDGET = "widget";
    public static final String ROW_PLAYER = "player";
    public static final String ROW_DANMAKU = "danmaku";
    public static final String ROW_SUBTITLE = "subtitle";
    public static final String ROW_AI = "ai";
    public static final String ROW_AD = "ad";
    public static final String ROW_ACCOUNT = "account";
    public static final String ROW_PERSONAL = "personal";
    public static final String ROW_LOCAL_SOURCE = "local_source";
    public static final String ROW_LAB = "lab";
    public static final String ROW_GITCLOUD = "gitcloud";
    public static final String ROW_ONEKEY = "onekey";
    public static final String ROW_DRIVE = "drive";
    public static final String ROW_SITE_HEALTH = "site_health";
    public static final String ROW_CACHE = "cache";
    public static final String ROW_BACKUP = "backup";
    public static final String ROW_RESTORE = "restore";
    public static final String ROW_DOH = "doh";
    public static final String ROW_INCOGNITO = "incognito";
    public static final String ROW_ABOUT = "about";
    public static final String ROW_DOWNLOAD = "download";

    /** Row requested by settings search; consumed (posted) when this page becomes visible. */
    private static String sPendingRow;

    public static void requestRow(String rowId) {
        sPendingRow = rowId;
    }

    private FragmentSettingBinding mBinding;
    private final Map<String, TextView> mValueViews = new HashMap<>();

    /** 隐藏分组开关快照：与当前偏好不一致时回到前台重建分组。 */
    private boolean mHiddenEnabled;
    /** 隐藏分组里下载开关下方的下载列表容器（隐藏分组未建时为 null）。 */
    private LinearLayout mDownloadListContainer;
    /** 下载任务变化时只重建下载列表，不动整个设置页。 */
    private final VideoDownloadManager.Listener mDownloadListener = this::refreshDownloadList;
    /** 用户手动展开的下载剧名组：进度刷新重建列表时保持展开，不自动收起。 */
    private final Set<String> mExpandedDownloadDramas = new HashSet<>();
    /** 长按剧名后显示删除按钮的剧名组：进度刷新重建列表时保持显示，不闪一下就没。 */
    private final Set<String> mDeleteShownDownloadDramas = new HashSet<>();

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        buildGroups();
        setText();
    }

    @Override
    protected void initEvent() {
        mBinding.searchBtn.setOnClickListener(v -> getRoot().change(10));
    }

    // ------------------------------------------------------------------ groups

    private void buildGroups() {
        mHiddenEnabled = Setting.isHiddenSettingsEnabled();
        mValueViews.clear();
        LinearLayout container = mBinding.groupsContainer;
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        // 站点：点播 / 直播 / 壁纸三行配置入口（带首页/历史小按钮，行为与改版前一致）
        ViewGroup site = addGroup(container, inflater, R.string.setting_group_site, true);
        addConfigRow(site, inflater, R.drawable.ic_setting_vod, R.drawable.shape_setting_circle_blue,
                R.string.setting_vod, ROW_VOD, this::onVod, this::onVodHome, this::onVodHistory);
        addDivider(site);
        addConfigRow(site, inflater, R.drawable.ic_setting_live, R.drawable.shape_setting_circle_red,
                R.string.setting_live, ROW_LIVE, this::onLive, this::onLiveHome, this::onLiveHistory);
        addDivider(site);
        View wallRow = addConfigRow(site, inflater, R.drawable.ic_setting_wall, R.drawable.shape_setting_circle_violet,
                R.string.setting_wall, ROW_WALL, this::onWall, this::setWallDefault, this::setWallRefresh);
        wallRow.findViewById(R.id.historyBtn).setOnLongClickListener(v -> onWallHistory());

        // 通用：外观 / 增强功能 / 播放设置 / 弹幕 / 字幕 / AI / 去广告
        ViewGroup general = addGroup(container, inflater, R.string.setting_group_general, false);
        addRow(general, inflater, R.drawable.ic_setting_appearance, R.drawable.shape_setting_circle_purple,
                R.string.setting_appearance, R.string.setting_appearance_sub, ROW_APPEARANCE, this::onAppearance);
        addDivider(general);
        addRow(general, inflater, R.drawable.ic_action_setting, R.drawable.shape_setting_circle_orange,
                R.string.setting_enhance, R.string.setting_enhance_sub, ROW_WIDGET, () -> getRoot().change(3));
        addDivider(general);
        addRow(general, inflater, R.drawable.ic_setting_play, R.drawable.shape_setting_circle_red,
                R.string.setting_player, R.string.setting_player_sub, ROW_PLAYER, () -> getRoot().change(2));
        addDivider(general);
        addRow(general, inflater, R.drawable.ic_setting_danmaku, R.drawable.shape_setting_circle_blue,
                R.string.setting_danmaku, R.string.setting_danmaku_sub, ROW_DANMAKU, () -> getRoot().change(4));
        addDivider(general);
        addRow(general, inflater, R.drawable.ic_setting_subtitle, R.drawable.shape_setting_circle_green,
                R.string.setting_subtitle, R.string.setting_subtitle_sub, ROW_SUBTITLE, () -> getRoot().change(6));
        addDivider(general);
        addRow(general, inflater, R.drawable.ic_setting_ai, R.drawable.shape_setting_circle_purple,
                R.string.setting_ai, R.string.setting_ai_sub, ROW_AI, () -> getRoot().change(8));
        addDivider(general);
        addRow(general, inflater, R.drawable.ic_setting_ad, R.drawable.shape_setting_circle_red,
                R.string.setting_ad, R.string.setting_ad_sub, ROW_AD, () -> getRoot().change(9));

        // 账号与服务：TMDB / 个性设置
        ViewGroup account = addGroup(container, inflater, R.string.setting_group_account, false);
        addRow(account, inflater, R.drawable.ic_setting_account, R.drawable.shape_setting_circle_blue,
                R.string.setting_tmdb, R.string.setting_tmdb_sub, ROW_ACCOUNT, () -> getRoot().change(7));
        addDivider(account);
        addRow(account, inflater, R.drawable.ic_setting_personal, R.drawable.shape_setting_circle_green,
                R.string.setting_personal, R.string.setting_personal_sub, ROW_PERSONAL, () -> getRoot().change(5));

        // 特色功能：墨影视独有行（增强设置页里仍保留入口，这里是主设置页的集中入口）
        ViewGroup feature = addGroup(container, inflater, R.string.setting_group_feature, false);
        addRow(feature, inflater, R.drawable.ic_setting_folder, R.drawable.shape_setting_circle_blue,
                R.string.setting_local_source, R.string.setting_local_source_sub, ROW_LOCAL_SOURCE, this::onLocalSource);
        addDivider(feature);
        addRow(feature, inflater, R.drawable.ic_action_lab, R.drawable.shape_setting_circle_orange,
                R.string.setting_lab, R.string.setting_lab_sub, ROW_LAB, this::onLab);
        addDivider(feature);
        addRow(feature, inflater, R.drawable.ic_git_cloud_upload, R.drawable.shape_setting_circle_purple,
                R.string.setting_git_cloud, R.string.setting_gitcloud_sub, ROW_GITCLOUD, this::onGitCloud);
        addDivider(feature);
        addRow(feature, inflater, R.drawable.ic_setting_sync, R.drawable.shape_setting_circle_green,
                R.string.setting_one_key_sync, R.string.setting_onekey_sub, ROW_ONEKEY, this::onOneKeySync);
        addDivider(feature);
        addRow(feature, inflater, R.drawable.ic_setting_drive, R.drawable.shape_setting_circle_blue,
                R.string.setting_drive_check, R.string.setting_drive_sub, ROW_DRIVE, this::onDriveCheck);
        addDivider(feature);
        View siteHealthRow = addRow(feature, inflater, R.drawable.ic_setting_health, R.drawable.shape_setting_circle_green,
                R.string.setting_site_health, R.string.setting_site_health_sub, ROW_SITE_HEALTH, this::onSiteHealth);
        siteHealthRow.setOnLongClickListener(v -> clearSiteHealth());

        // 数据与存储：缓存 / 备份 / 恢复 / DoH / 无痕模式
        ViewGroup storage = addGroup(container, inflater, R.string.setting_group_storage, false);
        View cacheRow = addRow(storage, inflater, R.drawable.ic_action_delete, R.drawable.shape_setting_circle_orange,
                R.string.setting_cache, R.string.setting_cache_sub, ROW_CACHE, this::onCacheClear);
        cacheRow.setOnLongClickListener(v -> onCacheManage());
        addDivider(storage);
        addRow(storage, inflater, R.drawable.ic_git_cloud_backup, R.drawable.shape_setting_circle_blue,
                R.string.setting_backup, R.string.setting_backup_sub, ROW_BACKUP, this::onBackup);
        addDivider(storage);
        addRow(storage, inflater, R.drawable.ic_git_cloud_restore, R.drawable.shape_setting_circle_green,
                R.string.setting_restore, R.string.setting_restore_sub, ROW_RESTORE, this::onRestore);
        addDivider(storage);
        addRow(storage, inflater, R.drawable.ic_setting_doh, R.drawable.shape_setting_circle_violet,
                R.string.setting_doh, R.string.setting_doh_sub, ROW_DOH, this::setDoh);
        addDivider(storage);
        addRow(storage, inflater, R.drawable.ic_setting_incognito, R.drawable.shape_setting_circle_blue,
                R.string.setting_incognito, R.string.setting_incognito_sub, ROW_INCOGNITO, this::setIncognito);

        // 关于
        ViewGroup about = addGroup(container, inflater, R.string.setting_group_about, false);
        addRow(about, inflater, R.drawable.ic_control_info, R.drawable.shape_setting_circle_blue,
                R.string.setting_about, R.string.setting_about_sub, ROW_ABOUT, this::onVersion);

        // 隐藏设置：默认关闭时整个分组不存在；关于页版本号连点 5 次后出现。
        if (mHiddenEnabled) {
            ViewGroup hidden = addGroup(container, inflater, R.string.setting_group_hidden, false);
            addRow(hidden, inflater, R.drawable.ic_setting_download, R.drawable.shape_setting_circle_blue,
                    R.string.setting_download, R.string.setting_download_sub, ROW_DOWNLOAD, this::setDownloadEnabled);
            mDownloadListContainer = new LinearLayout(requireContext());
            mDownloadListContainer.setOrientation(LinearLayout.VERTICAL);
            mDownloadListContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hidden.addView(mDownloadListContainer);
            refreshDownloadList();
        } else {
            mDownloadListContainer = null;
        }
    }

    /** One group: title above a white rounded card; returns the card to add rows to. */
    private ViewGroup addGroup(LinearLayout container, LayoutInflater inflater, int titleRes, boolean first) {
        ItemSettingGroupTitleBinding titleBinding = ItemSettingGroupTitleBinding.inflate(inflater, container, false);
        titleBinding.getRoot().setText(titleRes);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(first ? 8 : 24);
        container.addView(titleBinding.getRoot(), titleParams);
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setRadius(dp(24));
        card.setCardElevation(0);
        card.setCardBackgroundColor(getResources().getColor(R.color.setting_card_bg, requireContext().getTheme()));
        card.setUseCompatPadding(false);
        LinearLayout rows = new LinearLayout(requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(rows);
        container.addView(card);
        return rows;
    }

    private void addDivider(ViewGroup card) {
        View divider = new View(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.leftMargin = dp(80);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(getResources().getColor(R.color.setting_card_divider, requireContext().getTheme()));
        card.addView(divider);
    }

    private View addRow(ViewGroup card, LayoutInflater inflater, int iconRes, int circleRes,
                        int titleRes, int subtitleRes, String rowId, Runnable onClick) {
        ItemSettingCardRowBinding binding = ItemSettingCardRowBinding.inflate(inflater, card, true);
        binding.iconBg.setBackgroundResource(circleRes);
        binding.icon.setImageResource(iconRes);
        binding.title.setText(titleRes);
        if (subtitleRes != 0) {
            binding.subtitle.setText(subtitleRes);
        } else {
            binding.subtitle.setVisibility(View.GONE);
        }
        binding.value.setVisibility(View.GONE);
        if (rowId != null) mValueViews.put(rowId, binding.value);
        binding.getRoot().setOnClickListener(v -> onClick.run());
        return binding.getRoot();
    }

    private View addConfigRow(ViewGroup card, LayoutInflater inflater, int iconRes, int circleRes,
                              int titleRes, String rowId, Runnable onRowClick, Runnable onHome, Runnable onHistory) {
        ItemSettingCardRowConfigBinding binding = ItemSettingCardRowConfigBinding.inflate(inflater, card, true);
        binding.iconBg.setBackgroundResource(circleRes);
        binding.icon.setImageResource(iconRes);
        binding.title.setText(titleRes);
        if (rowId != null) mValueViews.put(rowId, binding.value);
        binding.getRoot().setOnClickListener(v -> onRowClick.run());
        binding.homeBtn.setOnClickListener(v -> onHome.run());
        binding.historyBtn.setOnClickListener(v -> onHistory.run());
        return binding.getRoot();
    }

    private void setValue(String rowId, String text) {
        TextView view = mValueViews.get(rowId);
        if (view == null) return;
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ values

    private void setText() {
        if (mBinding == null) return;
        setValue(ROW_VOD, VodConfig.getDesc());
        setValue(ROW_LIVE, LiveConfig.getDesc());
        setValue(ROW_WALL, Setting.getWallDesc(WallConfig.getDesc()));
        setValue(ROW_APPEARANCE, getThemeModeText());
        setValue(ROW_ACCOUNT, getString(Setting.isTmdbReady() ? R.string.setting_configured : R.string.setting_unconfigured));
        setValue(ROW_GITCLOUD, getString(R.string.git_cloud_account_count, GitCloudAccountStore.list().size()));
        setValue(ROW_DRIVE, getSwitch(Setting.isDriveCheck()));
        setValue(ROW_SITE_HEALTH, getSiteHealthText());
        setValue(ROW_DOH, getDohList()[getDohIndex()]);
        setValue(ROW_INCOGNITO, getSwitch(Setting.isIncognito()));
        setValue(ROW_DOWNLOAD, getSwitch(Setting.isDownloadEnabled()));
        setValue(ROW_ABOUT, AppVersion.fullName());
        setCacheText();
    }

    private String getThemeModeText() {
        return switch (Setting.getThemeMode()) {
            case 0 -> getString(R.string.setting_theme_mode_light);
            case 1 -> getString(R.string.setting_theme_mode_dark);
            default -> getString(R.string.setting_theme_mode_system);
        };
    }

    private String getSiteHealthText() {
        SiteHealthStore.Summary summary = SiteHealthStore.summary();
        String state = getSwitch(Setting.isSiteHealthSort());
        if (summary.siteCount <= 0) return state;
        return state + " · " + getString(R.string.site_health_report_setting_summary, summary.siteCount, summary.sampleCount);
    }

    private void setCacheText() {
        if (mBinding == null) return;
        if (!CachePolicyStore.isManagementEnabled()) {
            FileUtil.getCacheSize(new Callback() {
                @Override
                public void success(String result) {
                    if (mBinding != null && isAdded()) setValue(ROW_CACHE, result);
                }
            });
            return;
        }
        setValue(ROW_CACHE, getString(R.string.cache_management_scanning));
        CacheCenter.get().requestSnapshot(false, snapshot -> {
            if (mBinding != null && isAdded()) {
                String total = FileUtil.byteCountToDisplaySize(snapshot.totalBytes());
                long quota = snapshot.systemQuotaBytes();
                setValue(ROW_CACHE, quota > 0
                        ? getString(R.string.cache_management_inline, total, FileUtil.byteCountToDisplaySize(quota))
                        : total);
            }
        });
    }

    private void setWallText() {
        setValue(ROW_WALL, Setting.getWallDesc(WallConfig.getDesc()));
    }

    // ------------------------------------------------------------------ row actions

    /** Settings-search deep link: run the same handler as tapping the row. */
    public void performRow(String rowId) {
        if (rowId == null) return;
        switch (rowId) {
            case ROW_VOD -> onVod();
            case ROW_LIVE -> onLive();
            case ROW_WALL -> onWall();
            case ROW_APPEARANCE -> onAppearance();
            case ROW_PERSONAL -> getRoot().change(5);
            case ROW_WIDGET -> getRoot().change(3);
            case ROW_PLAYER -> getRoot().change(2);
            case ROW_DANMAKU -> getRoot().change(4);
            case ROW_SUBTITLE -> getRoot().change(6);
            case ROW_AI -> getRoot().change(8);
            case ROW_AD -> getRoot().change(9);
            case ROW_ACCOUNT -> getRoot().change(7);
            case ROW_LOCAL_SOURCE -> onLocalSource();
            case ROW_LAB -> onLab();
            case ROW_GITCLOUD -> onGitCloud();
            case ROW_ONEKEY -> onOneKeySync();
            case ROW_DRIVE -> onDriveCheck();
            case ROW_SITE_HEALTH -> onSiteHealth();
            case ROW_CACHE -> onCacheClear();
            case ROW_BACKUP -> onBackup();
            case ROW_RESTORE -> onRestore();
            case ROW_DOH -> setDoh();
            case ROW_INCOGNITO -> setIncognito();
            case ROW_ABOUT -> onVersion();
            case ROW_DOWNLOAD -> setDownloadEnabled();
            default -> { /* unknown row: ignore */ }
        }
    }

    private void consumePendingRow() {
        if (sPendingRow == null || mBinding == null || !isAdded()) return;
        String rowId = sPendingRow;
        sPendingRow = null;
        // Post so dialogs open after the fragment transaction that showed this page.
        requireView().post(() -> {
            if (isAdded() && mBinding != null) performRow(rowId);
        });
    }

    @Override
    public void setConfig(Config config) {
        if (config == null) return;
        String url = config.getUrl();
        if (!TextUtils.isEmpty(url) && url.startsWith("file")) {
            requireView().post(() -> PermissionUtil.requestFile(this, allGranted -> load(config)));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                String previousVodUrl = VodConfig.getUrl();
                VodConfig.load(config, getCallback());
                if (ConfigSyncPolicy.shouldSyncLive(previousVodUrl, LiveConfig.getUrl())) {
                    Config liveConfig = AppDatabase.get().getConfigDao().find(config.getUrl(), 1);
                    if (liveConfig != null) LiveConfig.load(liveConfig, new Callback());
                }
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(requireActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod() {
        HistoryDialog.create().vod().manage().show(this);
    }

    private void onLive() {
        HistoryDialog.create().live().manage().show(this);
    }

    private void onWall() {
        HistoryDialog.create().wall().manage().show(this);
    }

    private void onVodHome() {
        SiteDialog.create().search().change().show(this);
    }

    private void onLiveHome() {
        LiveDialog.show(this);
    }

    private void onVodHistory() {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory() {
        HistoryDialog.create().live().show(this);
    }

    private void onAppearance() {
        AppearanceDialog.show(this);
    }

    private void onLocalSource() {
        LocalSourceDialog.create().show(this);
    }

    private void onLab() {
        LabActivity.start(requireContext());
    }

    private void onGitCloud() {
        GitCloudDialog.show(this, this::setText);
    }

    private void onOneKeySync() {
        OneKeySyncDialog.create().show(requireActivity());
    }

    private void onDriveCheck() {
        Setting.putDriveCheck(!Setting.isDriveCheck());
        setValue(ROW_DRIVE, getSwitch(Setting.isDriveCheck()));
    }

    private void onSiteHealth() {
        SiteHealthDialog.show(this, this::setText);
    }

    private boolean clearSiteHealth() {
        SiteHealthStore.clear();
        Notify.show(R.string.site_health_clear_done);
        setText();
        return true;
    }

    private void onVersion() {
        AboutDialog.show(requireActivity(), () -> Updater.create().force().start(requireActivity()));
    }

    private void setWallDefault() {
        Setting.putWall(Setting.nextDefaultWall());
        Setting.putWallType(0);
        setWallText();
        ConfigEvent.wall();
    }

    private void setWallRefresh() {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory() {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito() {
        Setting.putIncognito(!Setting.isIncognito());
        setValue(ROW_INCOGNITO, getSwitch(Setting.isIncognito()));
    }

    private void setDownloadEnabled() {
        Setting.putDownloadEnabled(!Setting.isDownloadEnabled());
        if (!Setting.isDownloadEnabled()) {
            // 关掉下载开关：隐藏设置重新锁定，分组消失；想再开就再点 5 次版本号。
            Setting.putHiddenSettingsEnabled(false);
            Notify.show(R.string.hidden_settings_disabled);
            refreshHiddenGroupIfNeeded();
            return;
        }
        setValue(ROW_DOWNLOAD, getSwitch(Setting.isDownloadEnabled()));
        refreshDownloadList();
    }

    /** 隐藏开关变化后回到前台时重建分组（只在状态变了时重建，不丢滚动位置）。 */
    private void refreshHiddenGroupIfNeeded() {
        if (mBinding == null || !isAdded()) return;
        if (Setting.isHiddenSettingsEnabled() == mHiddenEnabled) return;
        buildGroups();
        setText();
    }

    // ------------------------------------------------------------------ 下载列表

    /** 只重建隐藏分组里的下载列表；开关关闭或分组未建时直接清空。 */
    private void refreshDownloadList() {
        if (mBinding == null || !isAdded() || mDownloadListContainer == null) return;
        mDownloadListContainer.removeAllViews();
        if (!Setting.isDownloadEnabled()) return;
        List<DownloadTask> tasks = VideoDownloadManager.get().getTasks();
        if (tasks.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.setting_download_list_empty);
            empty.setTextColor(getResources().getColor(R.color.setting_card_subtitle, requireContext().getTheme()));
            empty.setTextSize(13);
            empty.setPadding(dp(80), dp(12), dp(16), dp(12));
            mDownloadListContainer.addView(empty);
            return;
        }
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        for (DownloadTask task : tasks) {
            List<DownloadTask> group = groups.get(task.dramaName);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(task.dramaName, group);
            }
            group.add(task);
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Map.Entry<String, List<DownloadTask>> entry : groups.entrySet()) {
            addDramaGroup(inflater, entry.getKey(), entry.getValue());
        }
    }

    /** 一部剧一行：点展开看各集，长按出现删除按钮。 */
    private void addDramaGroup(LayoutInflater inflater, String dramaName, List<DownloadTask> tasks) {
        ItemDownloadDramaBinding binding = ItemDownloadDramaBinding.inflate(inflater, mDownloadListContainer, false);
        binding.iconBg.setBackgroundResource(R.drawable.shape_setting_circle_blue);
        binding.icon.setImageResource(R.drawable.ic_setting_folder);
        binding.title.setText(dramaName);
        binding.subtitle.setText(downloadSummary(tasks));
        binding.value.setVisibility(View.GONE);
        // 删除按钮显隐是持久状态：重建时从集合恢复，否则下载中每 500ms 刷新会把它清掉。
        binding.deleteBtn.setVisibility(mDeleteShownDownloadDramas.contains(dramaName) ? View.VISIBLE : View.GONE);
        LinearLayout episodes = new LinearLayout(requireContext());
        episodes.setOrientation(LinearLayout.VERTICAL);
        episodes.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        episodes.setVisibility(mExpandedDownloadDramas.contains(dramaName) ? View.VISIBLE : View.GONE);
        for (DownloadTask task : tasks) addEpisodeRow(inflater, episodes, task);
        binding.getRoot().setOnClickListener(v -> {
            mDeleteShownDownloadDramas.remove(dramaName);
            binding.deleteBtn.setVisibility(View.GONE);
            boolean expand = episodes.getVisibility() != View.VISIBLE;
            episodes.setVisibility(expand ? View.VISIBLE : View.GONE);
            if (expand) mExpandedDownloadDramas.add(dramaName);
            else mExpandedDownloadDramas.remove(dramaName);
        });
        binding.getRoot().setOnLongClickListener(v -> {
            mDeleteShownDownloadDramas.add(dramaName);
            binding.deleteBtn.setVisibility(View.VISIBLE);
            return true;
        });
        binding.deleteBtn.setOnClickListener(v -> confirmDeleteDrama(dramaName));
        mDownloadListContainer.addView(binding.getRoot());
        mDownloadListContainer.addView(episodes);
    }

    private String downloadSummary(List<DownloadTask> tasks) {
        int done = 0;
        for (DownloadTask task : tasks) {
            if (task.isDone()) done++;
        }
        return getString(R.string.download_drama_summary, tasks.size(), done);
    }

    private void addEpisodeRow(LayoutInflater inflater, LinearLayout parent, DownloadTask task) {
        ItemDownloadEpisodeBinding binding = ItemDownloadEpisodeBinding.inflate(inflater, parent, false);
        binding.name.setText(task.episodeName);
        binding.status.setText(downloadStatusText(task));
        bindDownloadError(binding, task);
        bindEpisodeProgress(binding, task);
        binding.getRoot().setOnClickListener(v -> onEpisodeRowClick(task));
        parent.addView(binding.getRoot());
    }

    /** 点集行：已完成 → 播放；失败 → 重试；已暂停 → 恢复；下载中/解析中/排队中 → 暂停。 */
    private void onEpisodeRowClick(DownloadTask task) {
        if (task == null) return;
        switch (task.status) {
            case DONE:
                playDownloaded(task);
                break;
            case FAILED:
                // 重试走正常入队：失败任务重置后重新排队，解析重新跑一遍拿新鲜地址
                // （旧的 resolvedUrl 已清掉，不会拿着解析失败时的旧地址重试）。
                if (VideoDownloadManager.get().enqueue(task.dramaName, task.siteKey, task.flag, task.episodeName, task.episodeUrl)) {
                    Notify.show(R.string.download_retry_started);
                }
                break;
            case PAUSED:
                if (VideoDownloadManager.get().resumeTask(task.id)) {
                    Notify.show(R.string.download_resumed);
                }
                break;
            case QUEUED:
            case RESOLVING:
            case DOWNLOADING:
                if (VideoDownloadManager.get().pauseTask(task.id)) {
                    Notify.show(R.string.download_paused);
                }
                break;
        }
    }

    /** 失败原因单独一行换行显示（最多 3 行）：之前截前 30 字只剩半截路径，真正原因看不见。 */
    private void bindDownloadError(ItemDownloadEpisodeBinding binding, DownloadTask task) {
        if (task.status != DownloadTask.Status.FAILED || TextUtils.isEmpty(task.error)) {
            binding.error.setVisibility(View.GONE);
            return;
        }
        binding.error.setText(shortenDownloadError(task.error));
        binding.error.setVisibility(View.VISIBLE);
    }

    /** 去掉内置存储根路径前缀，只留 Download/…，原因本身一字不动。 */
    private String shortenDownloadError(String error) {
        String root = DownloadPathUtil.getDownloadRoot().getParent();
        if (!TextUtils.isEmpty(root) && error.startsWith(root + "/")) {
            return error.substring(root.length() + 1);
        }
        return error;
    }

    /** 集行进度条：下载中按真实进度定量显示，总数未知时转圈；解析中转圈；其他状态隐藏。 */
    private void bindEpisodeProgress(ItemDownloadEpisodeBinding binding, DownloadTask task) {
        if (task.status == DownloadTask.Status.PAUSED) {
            // 已暂停：进度条停在当前进度不动（总数未知时不转圈，免得像还在下）。
            int progress = task.progress();
            if (progress >= 0) {
                binding.progress.setIndeterminate(false);
                binding.progress.setProgress(progress);
                binding.progress.setVisibility(View.VISIBLE);
            } else {
                binding.progress.setVisibility(View.GONE);
            }
            return;
        }
        if (task.status == DownloadTask.Status.DOWNLOADING) {
            int progress = task.progress();
            if (progress >= 0) {
                binding.progress.setIndeterminate(false);
                binding.progress.setProgress(progress);
            } else {
                binding.progress.setIndeterminate(true);
            }
            binding.progress.setVisibility(View.VISIBLE);
            return;
        }
        if (task.status == DownloadTask.Status.RESOLVING) {
            binding.progress.setIndeterminate(true);
            binding.progress.setVisibility(View.VISIBLE);
            return;
        }
        binding.progress.setVisibility(View.GONE);
    }

    private String downloadStatusText(DownloadTask task) {
        if (task == null || task.status == null) return "";
        switch (task.status) {
            case DONE:
                return getString(R.string.download_state_done);
            case FAILED: {
                // 原因不再塞进状态行，单独一行完整显示（见 bindDownloadError）。
                return getString(R.string.download_state_failed_retry);
            }
            case PAUSED:
                return getString(R.string.download_state_paused);
            case RESOLVING:
                return getString(R.string.download_state_resolving);
            case DOWNLOADING: {
                int progress = task.progress();
                String state = getString(R.string.download_state_downloading);
                return progress < 0 ? state : state + " " + progress + "%";
            }
            case QUEUED:
            default:
                return getString(R.string.download_state_queued);
        }
    }

    /** 点已完成的一集：播 Download 文件夹里的本地文件（走现有 push 本地文件通路）。 */
    private void playDownloaded(DownloadTask task) {
        if (task == null || !task.isDone()) return;
        if (task.filePath == null || !new File(task.filePath).exists()) {
            Notify.show(R.string.download_file_missing);
            return;
        }
        VideoActivity.start(requireActivity(), "file://" + task.filePath);
    }

    /** 长按剧名后点的删除：先弹确认框，确认后真删文件夹 + 记录 + 列表行。 */
    private void confirmDeleteDrama(String dramaName) {
        new WebHtvAlertDialogBuilder(requireContext(), R.style.Theme_WebHTV_Dialog)
                .setTitle(R.string.download_delete_title)
                .setMessage(getString(R.string.download_delete_message, dramaName))
                .setPositiveButton(R.string.download_delete, (dialog, which) -> VideoDownloadManager.get().deleteDrama(dramaName, ok -> {
                    mDeleteShownDownloadDramas.remove(dramaName);
                    Notify.show(ok ? R.string.download_deleted : R.string.download_delete_failed);
                    refreshDownloadList();
                }))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void setDoh() {
        ChoiceDialog.showSingle(this, R.string.setting_doh, getDohList(), getDohIndex(), which -> {
            setDoh(VodConfig.get().getDoh().get(which));
        });
    }

    private void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        setValue(ROW_DOH, doh.getName());
    }

    /**
     * The settings row's plain click: clear every cache at once, the way this row behaved before it
     * opened the cache management panel.
     */
    private void onCacheClear() {
        if (!CachePolicyStore.isManagementEnabled()) {
            FileUtil.clearCache(new Callback() {
                @Override
                public void success() {
                    setCacheText();
                }
            });
            return;
        }
        CacheManagementDialog.cleanEverything();
    }

    /**
     * The settings row's long press: open the cache management panel.
     */
    private boolean onCacheManage() {
        if (!CachePolicyStore.isManagementEnabled()) {
            onCacheClear();
            return true;
        }
        CacheManagementDialog.show(this);
        return true;
    }

    private void onBackup() {
        PermissionUtil.requestFile(this, allGranted -> {
            if (!allGranted) {
                Notify.show(R.string.backup_permission_denied);
                return;
            }
            BackupProgressDialog progress = BackupProgressDialog.open(getParentFragmentManager(), "备份应用数据");
            AppDatabase.backup(new Callback() {
                @Override
                public void success() {
                    progress.finish();
                    Notify.show(R.string.backup_success);
                }

                @Override
                public void error() {
                    progress.finish();
                    Notify.show(R.string.backup_fail);
                }
            }, progress::update);
        });
    }

    private void onRestore() {
        PermissionUtil.requestFile(this, allGranted -> {
            if (!allGranted) {
                Notify.show(R.string.backup_permission_denied);
                return;
            }
            RestoreDialog.create().show(requireActivity(), new Callback() {
                @Override
                public void success() {
                    Notify.show(R.string.restore_success);
                    setText();
                }

                @Override
                public void error() {
                    Notify.show(R.string.restore_fail);
                }
            });
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) {
            setWallText();
            return;
        }
        if (event.type() != ConfigEvent.Type.COMMON) return;
        setText();
    }

    /**
     * Re-reads the cache inventory the moment a cleanup (or any other cache mutation) finishes.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() != RefreshEvent.Type.CACHE) return;
        if (mBinding == null || !isAdded()) return;
        setCacheText();
    }

    @Override
    public void onResume() {
        super.onResume();
        VideoDownloadManager.get().addListener(mDownloadListener);
        refreshHiddenGroupIfNeeded();
        setText();
        refreshDownloadList();
        consumePendingRow();
    }

    @Override
    public void onPause() {
        VideoDownloadManager.get().removeListener(mDownloadListener);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) return;
        refreshHiddenGroupIfNeeded();
        setText();
        refreshDownloadList();
        consumePendingRow();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        VideoDownloadManager.get().removeListener(mDownloadListener);
        EventBus.getDefault().unregister(this);
        mValueViews.clear();
        mBinding = null;
    }
}
