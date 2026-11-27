package com.github.aiyingshi.osc.tv.module;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.aiyingshi.osc.tv.App;
import com.github.aiyingshi.osc.tv.R;
import com.github.aiyingshi.osc.tv.databinding.ActivityModuleBinding;
import com.github.aiyingshi.osc.tv.databinding.ItemModuleBinding;
import com.github.aiyingshi.osc.tv.player.lut.LutStore;
import com.github.aiyingshi.osc.tv.setting.Setting;
import com.github.aiyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.aiyingshi.osc.tv.utils.Notify;
import com.github.aiyingshi.osc.tv.utils.Task;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设置里的“模块”页：列出 6 个模块（名称、大小、版本、状态），操作是在线下载 / 本地导入 /
 * 删除 / 重新下载；顶部有“下载全部（总包）”。
 *
 * <p>在线下载走 GitHub 代理（见 ModuleDownloader），移动网络下先弹确认；下载中有进度、可取消，
 * 失败可重试。本地导入照实验室导入抄：SAF 选 zip → 拷缓存 → 同一套校验/安装，分包与总包都能导。
 * 手机端与 TV 端共用同一页（TV 上由“请到模块里面下载”提示的按钮进来；TV 设置页本身
 * 是固定三列瓷砖网格，加不进这一行，见任务文档）。
 */
public class ModuleActivity extends AppCompatActivity {

    public static final String EXTRA_MODULE_ID = "module_id";

    public static void start(Context context) {
        start(context, null);
    }

    /** 打开模块页；moduleId 非空时尽量滚动到该模块那一行。 */
    public static void start(Context context, String moduleId) {
        Intent intent = new Intent(context, ModuleActivity.class);
        if (moduleId != null) intent.putExtra(EXTRA_MODULE_ID, moduleId);
        context.startActivity(intent);
    }

    private ActivityModuleBinding binding;
    private ModuleManager modules;
    private ModuleAdapter adapter;

    /** 下载中：模块 id → 进度百分比（-1 表示未知）；同时作为“有下载在跑”的 guard。 */
    private final Map<String, Integer> downloading = new HashMap<>();
    private final Map<String, ModuleProgress.CancellationToken> tokens = new HashMap<>();

    /** 本地导入：SAF 文件选择器选 zip（照 LabActivity 的 launcher 抄）。 */
    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getData() == null || result.getData().getData() == null) return;
                importArchive(result.getData().getData());
            });

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Setting.wrapDisplay(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityModuleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.setting_module);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        modules = ModuleManager.getInstance(this);
        adapter = new ModuleAdapter();
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);
        binding.downloadAll.setOnClickListener(v -> onDownloadAll());
        refresh();
        String targetId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        if (targetId != null) {
            int position = indexOf(targetId);
            if (position >= 0) {
                // post 到列表布局完成后滚动，不在布局前调 scroll（LabActivity 的教训）。
                binding.list.post(() -> binding.list.scrollToPosition(position));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_module, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_import) {
            if (!downloading.isEmpty()) {
                Notify.show(R.string.module_busy);
                return true;
            }
            startLocalImport();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int indexOf(String moduleId) {
        for (int i = 0; i < ModuleDefinition.ALL.size(); i++) {
            if (ModuleDefinition.ALL.get(i).id.equals(moduleId)) return i;
        }
        return -1;
    }

    private void refresh() {
        binding.downloadAll.setEnabled(downloading.isEmpty());
        adapter.setRows(buildRows());
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        for (ModuleDefinition definition : ModuleDefinition.ALL) {
            ModuleStatus status = modules.getStatus(definition.id);
            ModuleManifest manifest = status == ModuleStatus.NOT_INSTALLED
                    ? null : modules.installedManifest(definition.id);
            long size = status == ModuleStatus.NOT_INSTALLED
                    ? definition.approxSizeBytes : modules.installedSizeBytes(definition.id);
            Integer percent = downloading.get(definition.id);
            // 总包下载时进度记在 "all" 键上，各行都显示同一进度。
            if (percent == null) percent = downloading.get("all");
            rows.add(new Row(definition, status, manifest, size, percent));
        }
        return rows;
    }

    private static final class Row {
        final ModuleDefinition definition;
        final ModuleStatus status;
        final ModuleManifest manifest;
        final long sizeBytes;
        final Integer downloadingPercent;

        Row(ModuleDefinition definition, ModuleStatus status, ModuleManifest manifest,
            long sizeBytes, Integer downloadingPercent) {
            this.definition = definition;
            this.status = status;
            this.manifest = manifest;
            this.sizeBytes = sizeBytes;
            this.downloadingPercent = downloadingPercent;
        }
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private String statusText(ModuleStatus status) {
        return switch (status) {
            case INSTALLED -> getString(R.string.module_status_installed);
            case OUTDATED -> getString(R.string.module_status_outdated);
            case CORRUPT -> getString(R.string.module_status_corrupt);
            default -> getString(R.string.module_status_not_installed);
        };
    }

    // ---------- 行操作 ----------

    private void onRowClick(Row row) {
        List<String> actions = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        if (row.downloadingPercent != null) {
            actions.add(getString(android.R.string.cancel));
            handlers.add(() -> cancelDownload(row.definition));
        } else {
            boolean installed = row.status == ModuleStatus.INSTALLED;
            actions.add(getString(installed
                    ? R.string.module_redownload : R.string.module_download));
            handlers.add(() -> startOnlineDownload(row.definition));
            actions.add(getString(R.string.module_import));
            handlers.add(this::startLocalImport);
            if (row.status != ModuleStatus.NOT_INSTALLED) {
                actions.add(getString(R.string.module_delete));
                handlers.add(() -> confirmDelete(row.definition));
            }
        }
        String[] items = actions.toArray(new String[0]);
        new WebHtvAlertDialogBuilder(this)
                .setTitle(row.definition.displayName)
                .setItems(items, (dialog, which) -> handlers.get(which).run())
                .show();
    }

    private void confirmDelete(ModuleDefinition definition) {
        new WebHtvAlertDialogBuilder(this)
                .setMessage(getString(R.string.module_delete) + " " + definition.displayName + "?")
                .setPositiveButton(R.string.module_delete, (dialog, which) -> {
                    if (modules.deleteModule(definition.id)) {
                        afterModuleChanged(definition.id);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void cancelDownload(ModuleDefinition definition) {
        ModuleProgress.CancellationToken token = tokens.get(definition.id);
        // 总包下载时令牌记在 "all" 键上。
        if (token == null) token = tokens.get("all");
        if (token != null) token.cancel();
    }

    // ---------- 在线下载 ----------

    private void onDownloadAll() {
        if (!downloading.isEmpty()) {
            Notify.show(R.string.module_busy);
            return;
        }
        long total = 0L;
        for (ModuleDefinition definition : ModuleDefinition.ALL) total += definition.approxSizeBytes;
        final long totalBytes = total;
        confirmMobile(totalBytes, () -> Task.execute(() -> {
            ModuleProgress.CancellationToken token = new ModuleProgress.CancellationToken();
            registerDownload("all", token);
            modules.downloadAndInstallAll(token, new ModuleManager.DownloadCallback() {
                @Override
                public void onProgress(long done, long total) {
                    postProgress("all", done, total);
                }

                @Override
                public void onFinished(List<ModuleInstaller.InstallResult> results,
                                       String downloadError) {
                    unregisterDownload("all");
                    App.post(() -> onDownloadFinished(results, downloadError));
                }
            });
        }));
    }

    private void startOnlineDownload(ModuleDefinition definition) {
        if (!downloading.isEmpty()) {
            Notify.show(R.string.module_busy);
            return;
        }
        confirmMobile(definition.approxSizeBytes,
                () -> startOnlineDownloadInternal(definition));
    }

    private void startOnlineDownloadInternal(ModuleDefinition definition) {
        Task.execute(() -> {
            ModuleProgress.CancellationToken token = new ModuleProgress.CancellationToken();
            registerDownload(definition.id, token);
            modules.downloadAndInstall(definition, token, new ModuleManager.DownloadCallback() {
                @Override
                public void onProgress(long done, long total) {
                    postProgress(definition.id, done, total);
                }

                @Override
                public void onFinished(List<ModuleInstaller.InstallResult> results,
                                       String downloadError) {
                    unregisterDownload(definition.id);
                    App.post(() -> onDownloadFinished(results, downloadError));
                }
            });
        });
    }

    /** 移动网络下先弹确认；Wi-Fi 或无网判断失败时直接下（下载失败再报）。 */
    private void confirmMobile(long bytes, Runnable start) {
        if (modules.isWifi()) {
            start.run();
            return;
        }
        new WebHtvAlertDialogBuilder(this)
                .setMessage(getString(R.string.module_mobile_confirm, formatSize(bytes)))
                .setPositiveButton(R.string.module_download, (dialog, which) -> start.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void registerDownload(String key, ModuleProgress.CancellationToken token) {
        synchronized (downloading) {
            downloading.put(key, 0);
            tokens.put(key, token);
        }
        App.post(this::refresh);
    }

    private void unregisterDownload(String key) {
        synchronized (downloading) {
            downloading.remove(key);
            tokens.remove(key);
        }
    }

    private void postProgress(String key, long done, long total) {
        int percent = total > 0 ? (int) (done * 100L / total) : -1;
        synchronized (downloading) {
            if (downloading.containsKey(key)) downloading.put(key, percent);
        }
        App.post(this::refresh);
    }

    private void onDownloadFinished(List<ModuleInstaller.InstallResult> results,
                                    String downloadError) {
        if (downloadError != null) {
            Notify.show(getString(R.string.module_download_failed, downloadError));
            refresh();
            return;
        }
        int ok = 0;
        String firstError = null;
        for (ModuleInstaller.InstallResult result : results) {
            if (result.success) {
                ok++;
                afterModuleChanged(result.moduleId);
            } else if (firstError == null) {
                firstError = result.error;
            }
        }
        if (ok > 0) {
            Notify.show(getString(R.string.module_install_success));
        } else if (firstError != null) {
            Notify.show(getString(R.string.module_install_failed, firstError));
        }
        refresh();
    }

    // ---------- 本地导入 ----------

    private void startLocalImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        importLauncher.launch(intent);
    }

    private void importArchive(Uri uri) {
        Notify.show(R.string.module_importing);
        Task.execute(() -> {
            File cache = new File(getCacheDir(), "module_" + System.currentTimeMillis() + ".zip");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(cache)) {
                if (in == null) throw new IOException("无法读取文件");
                byte[] buffer = new byte[16384];
                int length;
                while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            } catch (Exception e) {
                cache.delete();
                String message = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> Notify.show(getString(R.string.module_install_failed, message)));
                return;
            }
            List<ModuleInstaller.InstallResult> results = modules.installZip(cache);
            cache.delete();
            App.post(() -> onDownloadFinished(results, null));
        });
    }

    /** LUT 模块装/删后清预设缓存，否则列表不更新（见 LutStore.clearCache）。 */
    private void afterModuleChanged(String moduleId) {
        if (ModuleDefinition.LUT.id.equals(moduleId)) LutStore.clearCache();
    }

    // ---------- 列表 ----------

    private class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.Holder> {

        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemModuleBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            holder.binding.title.setText(row.definition.displayName);
            String subtitle = formatSize(row.sizeBytes);
            if (row.manifest != null && row.manifest.moduleVersion != null) {
                subtitle += " · " + row.manifest.moduleVersion;
            }
            if (row.downloadingPercent != null) {
                subtitle = getString(R.string.module_downloading,
                        Math.max(0, row.downloadingPercent));
            }
            holder.binding.subtitle.setText(subtitle);
            holder.binding.status.setText(statusText(row.status));
            holder.binding.getRoot().setOnClickListener(v -> onRowClick(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemModuleBinding binding;

            Holder(ItemModuleBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
