package com.github.moyingshi.osc.tv.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.bean.TmdbItem;
import com.github.moyingshi.osc.tv.databinding.ActivityFollowingBinding;
import com.github.moyingshi.osc.tv.following.AlistSubscriptionImporter;
import com.github.moyingshi.osc.tv.following.Following;
import com.github.moyingshi.osc.tv.following.FollowingAgenda;
import com.github.moyingshi.osc.tv.following.FollowingNotifier;
import com.github.moyingshi.osc.tv.following.FollowingPlaybackBridge;
import com.github.moyingshi.osc.tv.following.FollowingReminderScheduler;
import com.github.moyingshi.osc.tv.following.FollowingIdentity;
import com.github.moyingshi.osc.tv.following.FollowingMetadataSnapshot;
import com.github.moyingshi.osc.tv.following.FollowingScheduler;
import com.github.moyingshi.osc.tv.following.FollowingSettings;
import com.github.moyingshi.osc.tv.following.FollowingSource;
import com.github.moyingshi.osc.tv.following.FollowingStore;
import com.github.moyingshi.osc.tv.following.FollowingUpdateCoordinator;
import com.github.moyingshi.osc.tv.following.FollowingUpdatePolicy;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.theme.ThemeController;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.adapter.AgendaDateAdapter;
import com.github.moyingshi.osc.tv.ui.adapter.FollowingAdapter;
import com.github.moyingshi.osc.tv.ui.custom.CustomWallView;
import com.github.moyingshi.osc.tv.ui.custom.SpaceItemDecoration;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.Task;
import com.github.moyingshi.osc.tv.utils.Util;
import com.google.android.material.button.MaterialButton;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FollowingActivity extends AppCompatActivity implements FollowingAdapter.Listener {

    public static final String EXTRA_IDENTITY_KEY = "following_identity_key";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_UPDATES = 1;
    private static final int FILTER_UNWATCHED = 2;
    private static final int FILTER_ENDED = 3;
    private static final int FILTER_FAILED = 4;
    private static final int FILTER_COUNT = 5;
    private static final Comparator<Following> LIST_ORDER = Comparator
            .comparing((Following item) -> !item.hasUpdate)
            .thenComparing(Comparator.comparingInt((Following item) -> item.unwatchedCount).reversed())
            .thenComparing(Comparator.comparingLong((Following item) -> item.updatedAt).reversed())
            .thenComparing(Comparator.comparingLong((Following item) -> item.metadataUpdatedAt).reversed())
            .thenComparing(Comparator.comparingLong((Following item) -> item.createdAt).reversed());
    /** 时间轴排序：播出日升序（待定排最后），同日沿用 LIST_ORDER。 */
    private static final Comparator<FollowingAdapter.Row> AGENDA_ORDER = Comparator
            .comparingLong((FollowingAdapter.Row row) -> {
                long day = FollowingAgenda.airDayStart(row.following);
                return day == 0 ? Long.MAX_VALUE : day;
            })
            .thenComparing((FollowingAdapter.Row left, FollowingAdapter.Row right)
                    -> LIST_ORDER.compare(left.following, right.following));
    private static final int AGENDA_ALL = 0;
    private static final int AGENDA_TODAY = 1;
    private static final int AGENDA_TOMORROW = 2;
    private static final int AGENDA_WEEK = 3;
    private ActivityFollowingBinding binding;
    private FollowingAdapter adapter;
    private String focusIdentity;
    private String pendingNotifyIdentity;
    private int filterIndex = FILTER_ALL;
    private int agendaTab = AGENDA_ALL;
    private LocalDate agendaDay;
    /** 日期 Tab 与日期条只在手机端布局里有；TV 布局没有，走老列表行为。 */
    private boolean agendaEnabled;
    private AgendaDateAdapter dateAdapter;
    private MaterialButton tabToday;
    private MaterialButton tabTomorrow;
    private MaterialButton tabWeek;
    private MaterialButton tabAll;
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (pendingNotifyIdentity == null) return;
                if (granted) {
                    FollowingSettings.setNotificationsEnabled(true);
                    String identityKey = pendingNotifyIdentity;
                    FollowingPlaybackBridge.setNotifyEnabledAsync(identityKey, true, error -> {
                        if (error == null) {
                            Task.execute(() -> {
                                Following item = FollowingStore.find(identityKey);
                                if (item != null) FollowingReminderScheduler.schedule(this, item);
                            });
                        }
                        load();
                    });
                    pendingNotifyIdentity = null;
                    return;
                }
                pendingNotifyIdentity = null;
                load();
            });

    public static Intent intent(Context context, String identityKey) {
        Intent intent = new Intent(context, FollowingActivity.class);
        if (!TextUtils.isEmpty(identityKey)) intent.putExtra(EXTRA_IDENTITY_KEY, identityKey);
        return intent;
    }

    public static void start(Activity activity, String identityKey) {
        activity.startActivity(intent(activity, identityKey));
    }

    public static void start(Context context) {
        context.startActivity(intent(context, ""));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
        // This page predates the shared BaseActivity, so it must opt into the same
        // appearance contract by hand: resolve the persisted tokens before the first
        // content view, then bind the tree once it exists and again after initView().
        ThemeController.applyFromPreferences(this);
        if (!FollowingSettings.isEnabled()) {
            Notify.show(R.string.following_enabled_hint);
            finish();
            return;
        }
        binding = ActivityFollowingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ThemeController.bindTheme(binding.getRoot());
        addWallpaper();
        focusIdentity = getIntent().getStringExtra(EXTRA_IDENTITY_KEY);
        FollowingNotifier.createChannel();
        FollowingNotifier.createReminderChannel();
        FollowingScheduler.ensurePeriodic(this);
        initView();
        ThemeController.bindTheme(binding.getRoot());
        FollowingScheduler.enqueueDueNow(this);
    }

    /**
     * 与其它内容页保持一致：全局壁纸作为页面背景，壁纸视图插在内容根视图之下。
     * 页面根布局保持透明，顶栏与卡片用半透明面板承载文字，动效跟随全局壁纸设置。
     */
    private void addWallpaper() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        content.addView(new CustomWallView(this, null), 0,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void initView() {
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.setAdapter(adapter = new FollowingAdapter(this));
        binding.check.setOnClickListener(view -> checkAll());
        binding.readAll.setOnClickListener(view -> markAllRead());
        binding.filter.setOnClickListener(view -> {
            filterIndex = (filterIndex + 1) % FILTER_COUNT;
            load();
        });
        binding.alistImport.setOnClickListener(view -> showServerImportDialog());
        // 日历里点某集会直接续播或打开详情，不再把身份回传到本页，所以这里只要普通启动。
        binding.calendar.setOnClickListener(view -> startActivity(FollowingCalendarActivity.intent(this)));
        // 日期 Tab 与日期条只在手机端布局里可见：TV 布局里是 gone 的同名占位，findViewById 能拿到但不启用。
        View agendaTabs = findViewById(R.id.agendaTabs);
        agendaEnabled = agendaTabs != null && agendaTabs.getVisibility() == View.VISIBLE;
        if (agendaEnabled) {
            tabToday = findViewById(R.id.tabToday);
            tabTomorrow = findViewById(R.id.tabTomorrow);
            tabWeek = findViewById(R.id.tabWeek);
            tabAll = findViewById(R.id.tabAll);
            tabToday.setOnClickListener(view -> selectTab(AGENDA_TODAY));
            tabTomorrow.setOnClickListener(view -> selectTab(AGENDA_TOMORROW));
            tabWeek.setOnClickListener(view -> selectTab(AGENDA_WEEK));
            tabAll.setOnClickListener(view -> selectTab(AGENDA_ALL));
            RecyclerView dates = findViewById(R.id.agendaDates);
            dates.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            dateAdapter = new AgendaDateAdapter(day -> {
                if (day.equals(agendaDay)) {
                    agendaDay = null;
                    agendaTab = AGENDA_ALL;
                } else {
                    agendaDay = day;
                }
                load();
            });
            dates.setAdapter(dateAdapter);
        }
        load();
    }

    private void selectTab(int tab) {
        agendaTab = tab;
        agendaDay = null;
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) load();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    private void load() {
        binding.loading.setVisibility(View.VISIBLE);
        Task.execute(() -> {
            List<Following> following = FollowingStore.list();
            List<FollowingAdapter.Row> rows = new ArrayList<>();
            for (Following item : following) {
                if (!matchesFilter(item)) continue;
                rows.add(new FollowingAdapter.Row(item, FollowingStore.preferredSource(item.identityKey)));
            }
            // 日期条的部数按 5 档筛选后的全量算，不受日期 Tab/单日选择影响。
            List<AgendaDateAdapter.Day> days = agendaEnabled ? buildStripDays(rows) : null;
            if (agendaEnabled) {
                rows.removeIf(row -> !matchesAgenda(row.following));
                rows.sort(AGENDA_ORDER);
            } else {
                rows.sort((left, right) -> LIST_ORDER.compare(left.following, right.following));
            }
            int unread = FollowingStore.unreadCount();
            App.post(() -> render(rows, unread, days));
        });
    }

    private void render(List<FollowingAdapter.Row> rows, int unread, List<AgendaDateAdapter.Day> days) {
        if (isFinishing() || binding == null) return;
        binding.loading.setVisibility(View.GONE);
        boolean agendaFiltered = agendaEnabled && (agendaDay != null || agendaTab != AGENDA_ALL);
        binding.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        binding.filter.setText(filterLabel());
        binding.emptyText.setText(agendaFiltered ? R.string.following_agenda_empty
                : filterIndex == FILTER_ALL ? R.string.following_empty : R.string.following_filter_empty);
        binding.recycler.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        binding.summary.setText(getString(R.string.following_summary, rows.size(), unread));
        adapter.setItems(agendaEnabled ? withHeaders(rows) : new ArrayList<Object>(rows));
        updateAgendaTabs(days);
        int index = TextUtils.isEmpty(focusIdentity) ? -1 : adapter.indexOf(focusIdentity);
        if (index >= 0) {
            binding.recycler.post(() -> {
                binding.recycler.scrollToPosition(index);
                focusIdentity = null;
            });
        }
    }

    /** 日期 Tab / 单日选择过滤；TV（agendaEnabled=false）不走这里。 */
    private boolean matchesAgenda(Following item) {
        if (agendaDay != null) {
            return FollowingAgenda.airDayStart(item) == FollowingAgenda.dayStartMillis(agendaDay);
        }
        FollowingAgenda.Bucket bucket = FollowingAgenda.bucketFor(item);
        return switch (agendaTab) {
            case AGENDA_TODAY -> bucket == FollowingAgenda.Bucket.TODAY;
            case AGENDA_TOMORROW -> bucket == FollowingAgenda.Bucket.TOMORROW;
            case AGENDA_WEEK -> bucket == FollowingAgenda.Bucket.TODAY
                    || bucket == FollowingAgenda.Bucket.TOMORROW
                    || bucket == FollowingAgenda.Bucket.WEEK;
            default -> true;
        };
    }

    private List<AgendaDateAdapter.Day> buildStripDays(List<FollowingAdapter.Row> rows) {
        Map<Long, Integer> counts = new HashMap<>();
        for (FollowingAdapter.Row row : rows) {
            long day = FollowingAgenda.airDayStart(row.following);
            if (day <= 0) continue;
            counts.put(day, counts.getOrDefault(day, 0) + 1);
        }
        List<AgendaDateAdapter.Day> days = new ArrayList<>();
        LocalDate today = FollowingAgenda.today();
        for (int i = 0; i < FollowingAgenda.STRIP_DAYS; i++) {
            LocalDate date = today.plusDays(i);
            days.add(new AgendaDateAdapter.Day(date,
                    counts.getOrDefault(FollowingAgenda.dayStartMillis(date), 0)));
        }
        return days;
    }

    /** 按播出日分组插时间轴头；待定组（无播出日）排最后。 */
    private List<Object> withHeaders(List<FollowingAdapter.Row> rows) {
        List<Object> display = new ArrayList<>();
        Long currentDay = null;
        List<FollowingAdapter.Row> group = new ArrayList<>();
        for (FollowingAdapter.Row row : rows) {
            long day = FollowingAgenda.airDayStart(row.following);
            if (currentDay == null || day != currentDay) {
                flushHeader(display, group, currentDay);
                currentDay = day;
                group = new ArrayList<>();
            }
            group.add(row);
        }
        flushHeader(display, group, currentDay);
        return display;
    }

    private void flushHeader(List<Object> display, List<FollowingAdapter.Row> group, Long day) {
        if (group.isEmpty() || day == null) return;
        display.add(new FollowingAdapter.Header(day, headerLabel(day), group.size()));
        display.addAll(group);
        group.clear();
    }

    private String headerLabel(long dayStart) {
        if (dayStart <= 0) return getString(R.string.following_agenda_header_unscheduled);
        String date = FollowingAgenda.shortDate(dayStart);
        LocalDate today = FollowingAgenda.today();
        if (dayStart == FollowingAgenda.dayStartMillis(today)) {
            return getString(R.string.following_agenda_header_today, date);
        }
        if (dayStart == FollowingAgenda.dayStartMillis(today.plusDays(1))) {
            return getString(R.string.following_agenda_header_tomorrow, date);
        }
        String[] weekdays = getResources().getStringArray(R.array.following_weekdays);
        int weekday = FollowingAgenda.weekdayOf(dayStart);
        String week = weekday >= 1 && weekday <= weekdays.length ? weekdays[weekday - 1] : "";
        return getString(R.string.following_agenda_header_date, date, week);
    }

    private void updateAgendaTabs(List<AgendaDateAdapter.Day> days) {
        if (!agendaEnabled) return;
        boolean dayPicked = agendaDay != null;
        tintTab(tabToday, !dayPicked && agendaTab == AGENDA_TODAY);
        tintTab(tabTomorrow, !dayPicked && agendaTab == AGENDA_TOMORROW);
        tintTab(tabWeek, !dayPicked && agendaTab == AGENDA_WEEK);
        tintTab(tabAll, !dayPicked && agendaTab == AGENDA_ALL);
        if (dateAdapter != null && days != null) dateAdapter.setDays(days, agendaDay);
    }

    private void tintTab(MaterialButton tab, boolean selected) {
        if (tab == null) return;
        tab.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                selected ? R.color.following_button_primary_bg : R.color.following_button_secondary_bg)));
        tab.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.following_button_primary_text : R.color.following_button_secondary_text));
    }

    private void markAllRead() {
        List<String> keys = new ArrayList<>();
        for (FollowingAdapter.Row row : adapter.getItems()) {
            if (row.following.hasUpdate && !TextUtils.isEmpty(row.following.identityKey)) keys.add(row.following.identityKey);
        }
        markReadNow(keys);
    }

    private void markReadNow(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        adapter.markReadLocally(keys);
        FollowingPlaybackBridge.markReadAllAsync(keys, error -> {
            if (error != null) load();
            else refreshUnreadSummary();
        });
    }

    private void refreshUnreadSummary() {
        FollowingPlaybackBridge.refreshUnreadCountAsync(unread -> {
            if (binding == null || isFinishing() || isDestroyed()) return;
            binding.summary.setText(getString(R.string.following_summary, adapter.getRowCount(), unread));
        });
    }

    private boolean matchesFilter(Following item) {
        return switch (filterIndex) {
            case FILTER_UPDATES -> item.hasUpdate || item.unwatchedCount > 0;
            case FILTER_UNWATCHED -> item.unwatchedCount > 0;
            case FILTER_ENDED -> FollowingMetadataSnapshot.ENDED.equals(item.officialStatus)
                    || FollowingMetadataSnapshot.CANCELED.equals(item.officialStatus);
            case FILTER_FAILED -> FollowingUpdateCoordinator.sourceCheckFailed(item.lastError);
            default -> true;
        };
    }

    private int filterLabel() {
        return switch (filterIndex) {
            case FILTER_UPDATES -> R.string.following_filter_updates;
            case FILTER_UNWATCHED -> R.string.following_filter_unwatched;
            case FILTER_ENDED -> R.string.following_filter_ended;
            case FILTER_FAILED -> R.string.following_filter_failed;
            default -> R.string.following_filter_all;
        };
    }

    private void checkAll() {
        binding.check.setEnabled(false);
        Notify.show(R.string.following_checking);
        Task.execute(() -> {
            List<Following> items = FollowingStore.list();
            if (items.isEmpty()) {
                App.post(() -> {
                    if (binding == null) return;
                    binding.check.setEnabled(true);
                    load();
                });
                return;
            }
            FollowingUpdateCoordinator coordinator = new FollowingUpdateCoordinator();
            boolean success = false;
            for (Following item : items) success |= coordinator.checkNow(item.identityKey, true);
            boolean finalSuccess = success;
            App.post(() -> {
                if (binding == null) return;
                binding.check.setEnabled(true);
                Notify.show(finalSuccess ? R.string.following_check_done : R.string.following_check_failed);
                load();
            });
        });
    }

    private void showServerImportDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        form.setPadding(horizontal, dp(8), horizontal, 0);
        EditText url = field(R.string.following_server_url);
        EditText token = field(R.string.following_server_token);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(url, new LinearLayout.LayoutParams(-1, -2));
        form.addView(token, new LinearLayout.LayoutParams(-1, -2));
        new WebHtvAlertDialogBuilder(this)
                .setTitle(R.string.following_server_import)
                .setView(form)
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.following_server_preview, (dialog, which) -> startServerImport(url, token, true))
                .setPositiveButton(R.string.following_server_import_action, (dialog, which) -> startServerImport(url, token, false))
                .show();
    }

    private EditText field(int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        return field;
    }

    private void startServerImport(EditText urlView, EditText tokenView, boolean preview) {
        String url = urlView.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Notify.show(R.string.following_server_invalid);
            return;
        }
        String token = tokenView.getText().toString();
        FollowingSettings.setServerImportEnabled(true);
        binding.loading.setVisibility(View.VISIBLE);
        Task.execute(() -> {
            try {
                List<AlistSubscriptionImporter.Candidate> candidates = AlistSubscriptionImporter.fetch(url, token);
                if (preview) {
                    App.post(() -> {
                        if (binding == null) return;
                        binding.loading.setVisibility(View.GONE);
                        showServerPreview(candidates);
                    });
                    return;
                }
                AlistSubscriptionImporter.ImportResult result = AlistSubscriptionImporter.importCandidates(candidates);
                App.post(() -> {
                    if (binding == null) return;
                    binding.loading.setVisibility(View.GONE);
                    Notify.show(getString(R.string.following_server_result, result.created, result.updated));
                    load();
                });
            } catch (Throwable error) {
                App.post(() -> {
                    if (binding != null) binding.loading.setVisibility(View.GONE);
                    Notify.show(error.getMessage());
                });
            }
        });
    }

    private void showServerPreview(List<AlistSubscriptionImporter.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            Notify.show(R.string.following_server_empty);
            return;
        }
        StringBuilder message = new StringBuilder();
        int limit = Math.min(20, candidates.size());
        for (int i = 0; i < limit; i++) {
            AlistSubscriptionImporter.Candidate candidate = candidates.get(i);
            if (i > 0) message.append('\n');
            message.append(candidate.title).append(" · S").append(candidate.season + 1);
            if (candidate.currentEpisodes > 0) message.append(" · E").append(candidate.currentEpisodes);
        }
        if (candidates.size() > limit) message.append("\n… +").append(candidates.size() - limit);
        new WebHtvAlertDialogBuilder(this)
                .setTitle(R.string.following_server_preview_title)
                .setMessage(message)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.following_server_import_action, (dialog, which) -> {
                    Task.execute(() -> {
                        AlistSubscriptionImporter.ImportResult result = AlistSubscriptionImporter.importCandidates(candidates);
                        App.post(() -> {
                            Notify.show(getString(R.string.following_server_result, result.created, result.updated));
                            load();
                        });
                    });
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onOpenDetail(Following item, FollowingSource source) {
        if (item == null) return;
        StringBuilder message = new StringBuilder();
        int released = FollowingUpdatePolicy.releasedEpisode(item);
        message.append(released > 0 ? getString(R.string.following_official, released) : getString(R.string.following_official_unknown));
        String airLine = FollowingAgenda.airLineText(this, item);
        if (airLine != null) message.append('\n').append(airLine);
        message.append('\n');
        message.append(source != null && source.playableEpisode > 0
                ? getString(R.string.following_source, source.playableEpisode)
                : getString(FollowingUpdateCoordinator.sourceCheckFailed(item.lastError)
                        ? R.string.following_source_failed : R.string.following_source_unknown));
        message.append('\n');
        message.append(item.watchedEpisode > 0 ? getString(R.string.following_watched, item.watchedEpisode) : getString(R.string.following_watched_unknown));
        if (item.unwatchedCount > 0) message.append('\n').append(getString(R.string.following_unwatched, item.unwatchedCount));
        else if (released > 0) message.append('\n').append(getString(R.string.following_unwatched_none));
        if (item.lastError != null && !item.lastError.isBlank()) message.append('\n').append(item.lastError);
        String[] actions = new String[]{
                getString(R.string.following_continue),
                getString(R.string.following_check),
                getString(R.string.following_read),
                getString(R.string.following_change_source),
                getString(R.string.following_cancel)
        };
        new WebHtvAlertDialogBuilder(this)
                .setTitle(item.vodName)
                .setMessage(message)
                .setNegativeButton(R.string.dialog_close, null)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) onContinue(item, source);
                    else if (which == 1) onCheck(item);
                    else if (which == 2) onRead(item);
                    else if (which == 3) onChangeSource(item);
                    else if (which == 4) onDelete(item);
                })
                .show();
    }

    @Override
    public void onContinue(Following item, FollowingSource source) {
        Task.execute(() -> {
            FollowingStore.reconcile(item);
            History history = FollowingStore.historyFor(item, source);
            App.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (history != null) {
                    VideoActivity.startFromFollowingHistory(this, history);
                    return;
                }
                FollowingSource target = preferred(item, source);
                if (target == null || TextUtils.isEmpty(target.siteKey) || TextUtils.isEmpty(target.vodId)) {
                    Notify.show(R.string.following_check_failed);
                    return;
                }
                TmdbItem tmdb = item.tmdbId > 0 ? new TmdbItem(item.tmdbId, item.mediaType, item.vodName,
                        "", "", item.vodPic, "", "", 0.0) : null;
                TmdbDetailActivity.start(this, target.siteKey, target.vodId, item.vodName, item.vodPic, "", tmdb, Setting.getDetailOpenMode());
            });
        });
    }

    @Override
    public void onFollowNextSeason(Following item) {
        if (item == null || item.latestReleasedSeason <= item.trackedSeason || TextUtils.isEmpty(item.seriesKey)) return;
        String identityKey = FollowingIdentity.identityKey(item.seriesKey, item.latestReleasedSeason);
        long now = System.currentTimeMillis();
        Task.execute(() -> {
            if (FollowingStore.find(identityKey) != null) {
                App.post(() -> FollowingActivity.start(this, identityKey));
                return;
            }
            Following next = item.copy();
            next.identityKey = identityKey;
            next.trackedSeason = item.latestReleasedSeason;
            next.trackedEpisode = 0;
            next.watchedSeason = item.latestReleasedSeason;
            next.watchedEpisode = 0;
            next.position = 0;
            next.duration = 0;
            next.latestReleasedSeason = item.latestReleasedSeason;
            next.latestReleasedEpisode = 0;
            next.seasonTotalEpisodes = 0;
            next.seasonReleasedEpisodes = 0;
            next.nextAirSeason = item.nextAirSeason == next.trackedSeason ? item.nextAirSeason : 0;
            next.nextAirEpisode = item.nextAirSeason == next.trackedSeason ? item.nextAirEpisode : 0;
            next.nextAirAt = item.nextAirSeason == next.trackedSeason ? item.nextAirAt : 0;
            next.readWatermarkEpisode = 0;
            next.lastNotifiedEpisode = 0;
            next.lastNotifiedAt = 0;
            next.lastObservedEpisode = 0;
            next.hasUpdate = false;
            next.unwatchedCount = 0;
            next.failureCount = 0;
            next.lastError = "";
            next.createdAt = now;
            next.updatedAt = now;
            next.nextCheckAt = now;
            FollowingSource source = FollowingStore.preferredSource(item.identityKey);
            if (source != null) {
                source = source.copy();
                source.followingKey = identityKey;
                source.playableSeason = next.trackedSeason;
                source.playableEpisode = 0;
                source.playableCount = 0;
                source.lastProbeAt = 0;
                source.lastError = "";
            }
            if (source == null) {
                source = new FollowingSource();
                source.followingKey = identityKey;
                source.cid = next.cid;
                source.siteKey = next.siteKey;
                source.vodId = next.vodId;
                source.vodName = next.vodName;
                source.vodPic = next.vodPic;
                source.preferred = true;
            }
            try {
                FollowingStore.saveNew(next, source);
                App.post(() -> {
                    FollowingScheduler.enqueueDueNow(this);
                    Notify.show(R.string.following_added);
                    load();
                });
            } catch (Throwable error) {
                App.post(() -> Notify.show(error.getMessage()));
            }
        });
    }

    @Override
    public void onCheck(Following item) {
        binding.loading.setVisibility(View.VISIBLE);
        Task.execute(() -> {
            boolean success = new FollowingUpdateCoordinator().checkNow(item.identityKey, true);
            App.post(() -> {
                if (binding == null) return;
                Notify.show(success ? R.string.following_check_done : R.string.following_check_failed);
                load();
            });
        });
    }

    @Override
    public void onRead(Following item) {
        if (item == null || TextUtils.isEmpty(item.identityKey)) return;
        markReadNow(List.of(item.identityKey));
    }

    @Override
    public void onToggleNotify(Following item) {
        boolean enabled = !item.notifyEnabled;
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotifyIdentity = item.identityKey;
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (enabled) FollowingSettings.setNotificationsEnabled(true);
        FollowingPlaybackBridge.setNotifyEnabledAsync(item.identityKey, enabled, error -> {
            if (error == null) {
                // 内存里的开关先翻过来再排提醒，调度器读到的是新值。
                item.notifyEnabled = enabled;
                FollowingReminderScheduler.schedule(this, item);
            }
            load();
        });
    }

    @Override
    public void onChangeSource(Following item) {
        try {
            Class<?> type = Class.forName("com.github.moyingshi.osc.tv.ui.activity.SearchActivity");
            type.getMethod("start", Activity.class, String.class).invoke(null, this, item.vodName);
        } catch (Throwable error) {
            Notify.show(error.getMessage());
        }
    }

    @Override
    public void onDelete(Following item) {
        new WebHtvAlertDialogBuilder(this, R.style.Theme_WebHTV_Dialog)
                .setTitle(R.string.following_delete_title)
                .setMessage(R.string.following_delete_message)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.following_cancel, (dialog, which) -> {
                    FollowingScheduler.cancelNext(this, item.identityKey);
                    FollowingReminderScheduler.cancel(this, item.identityKey);
                    FollowingPlaybackBridge.deleteAsync(item.identityKey, error -> load());
                })
                .show();
    }

    private FollowingSource preferred(Following item, FollowingSource source) {
        if (source != null) return source;
        FollowingSource target = new FollowingSource();
        target.followingKey = item.identityKey;
        target.cid = item.cid;
        target.siteKey = item.siteKey;
        target.vodId = item.vodId;
        target.vodName = item.vodName;
        target.vodPic = item.vodPic;
        target.preferred = true;
        return target;
    }
}
