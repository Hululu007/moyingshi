package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogLocalSourceBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.ui.adapter.LocalFileAdapter;
import com.fongmi.android.tv.ui.adapter.LocalSourceAdapter;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.LocalSource;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Path;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地源弹窗（魔影式）：选文件夹/文件扫描成本地源，列表里可搜索、按分类过滤、
 * 全选/反选/删除/清空，点一个源切过去，密源开关控制 [密] 的源显不显示。
 */
public class LocalSourceDialog extends BaseAlertDialog implements LocalSourceAdapter.OnClickListener, LocalFileAdapter.OnClickListener {

    private DialogLocalSourceBinding binding;
    private LocalSourceAdapter adapter;
    private LocalFileAdapter fileAdapter;
    private List<Site> all = new ArrayList<>();
    private String category = "全部";
    private String keyword = "";
    private boolean scanning;
    private int pickMode;
    /** 文件浏览模式：在弹窗里看文件夹内容，点一个文件就用那一个，不整文件夹倒进列表。 */
    private boolean browsing;
    private File browseDir;
    /** 点文件后待使用的文件：扫完切到它对应的源。 */
    private File pendingUseFile;

    private static final int PICK_DIR = 0;
    private static final int PICK_FILE = 1;
    private static final int PICK_PHP_ROOT = 2;
    private static final int PICK_DIR_REPLACE = 3;

    public static LocalSourceDialog create() {
        return new LocalSourceDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), "LocalSourceDialog");
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), "LocalSourceDialog");
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getData() == null || result.getData().getData() == null) return;
        String path = FileChooser.getPersistentPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) return;
        if (pickMode == PICK_DIR) {
            LocalSource.setScanDir(path);
            refreshScanDir();
            // 选文件夹后进文件浏览：让用户在里面找到单个文件点一下就用，不整文件夹倒进列表。
            enterBrowse(new File(path));
        } else if (pickMode == PICK_DIR_REPLACE) {
            // 更换文件夹：旧文件夹扫出来的源不再适用，先清空再进新文件夹浏览，免得旧源一直留在列表里。
            LocalSource.setScanDir(path);
            LocalSource.clear();
            refreshScanDir();
            refreshList();
            enterBrowse(new File(path));
        } else if (pickMode == PICK_FILE) {
            File file = new File(path);
            if (!LocalSource.isSupported(file.getName())) {
                Notify.show(R.string.local_source_bad_ext);
                return;
            }
            // 系统选文件点完也直接用它，和弹窗里浏览点文件一致。
            pendingUseFile = file;
            scanFiles(List.of(file));
        } else if (pickMode == PICK_PHP_ROOT) {
            LocalSource.setPhpRoot(path);
            refreshPhpRoot();
            Notify.show(R.string.local_source_php_hint);
        }
    });

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLocalSourceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog).setTitle(R.string.local_source).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new LocalSourceAdapter(this);
        binding.sites.setAdapter(adapter);
        fileAdapter = new LocalFileAdapter(this);
        binding.files.setAdapter(fileAdapter);
        binding.browseUp.setOnClickListener(v -> browseUp());
        binding.browseExit.setOnClickListener(v -> exitBrowse());
        binding.search.addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                keyword = s == null ? "" : s.toString();
                refreshList();
            }
        });
        binding.pick.setOnClickListener(v -> {
            pickMode = PICK_DIR;
            FileChooser.from(launcher).showDirectory();
        });
        binding.changeDir.setOnClickListener(v -> {
            pickMode = PICK_DIR_REPLACE;
            FileChooser.from(launcher).showDirectory();
        });
        binding.pickFile.setOnClickListener(v -> {
            pickMode = PICK_FILE;
            FileChooser.from(launcher).show();
        });
        binding.scanAll.setOnClickListener(v -> scanAll());
        binding.selectAll.setOnClickListener(v -> adapter.selectAll());
        binding.invert.setOnClickListener(v -> adapter.invert());
        binding.remove.setOnClickListener(v -> removeSelected());
        binding.clear.setOnClickListener(v -> clearAll());
        binding.pickPhpRoot.setOnClickListener(v -> {
            pickMode = PICK_PHP_ROOT;
            FileChooser.from(launcher).showDirectory();
        });
        binding.secret.setOnClickListener(v -> toggleSecret());
        binding.scrollTop.setOnClickListener(v -> {
            if (browsing) binding.files.scrollToPosition(0);
            else binding.sites.scrollToPosition(0);
        });
        binding.scrollBottom.setOnClickListener(v -> {
            int count = browsing ? fileAdapter.getItemCount() : adapter.getItemCount();
            if (count > 0) {
                if (browsing) binding.files.scrollToPosition(count - 1);
                else binding.sites.scrollToPosition(count - 1);
            }
        });
        refreshSecret();
        refreshPhpRoot();
        refreshScanDir();
        refreshList();
    }

    private void refreshScanDir() {
        String dir = LocalSource.getScanDir();
        binding.scanDir.setText(TextUtils.isEmpty(dir) ? getString(R.string.local_source_scan_dir_empty) : dir);
    }

    private void refreshList() {
        // 浏览模式下站点列表是藏起来的，等退出浏览再刷新，免得把浏览的空状态文案冲掉。
        if (browsing) return;
        all = LocalSource.getSites();
        List<String> categories = LocalSource.categoriesOf(all);
        if (!categories.contains(category)) category = "全部";
        buildTabs(categories);
        List<Site> filtered = LocalSource.filter(all, category, keyword);
        adapter.setItems(filtered);
        binding.empty.setText(R.string.local_source_empty);
        binding.empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        binding.status.setText(getString(R.string.local_source_count, all.size()));
    }

    /** 进文件浏览模式：显示这个文件夹里的子目录和源文件，点一个文件就用那一个。 */
    private void enterBrowse(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        // 文件太多就别进这个文件夹，提示用户选子文件夹，免得卡死。
        if (LocalSource.fileCount(dir) > LocalSource.MAX_FILES_PER_DIR) {
            Notify.show(R.string.local_source_too_many_files);
            return;
        }
        browsing = true;
        browseDir = dir;
        binding.browserBar.setVisibility(View.VISIBLE);
        binding.browsePath.setText(dir.getAbsolutePath());
        binding.tabsScroll.setVisibility(View.GONE);
        binding.sites.setVisibility(View.GONE);
        binding.status.setVisibility(View.GONE);
        binding.phpRoot.setVisibility(View.GONE);
        binding.files.setVisibility(View.VISIBLE);
        refreshFiles();
    }

    private void refreshFiles() {
        List<File> items = LocalSource.listBrowseable(browseDir);
        fileAdapter.setItems(items);
        binding.empty.setText(R.string.local_source_browse_empty);
        binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void browseUp() {
        File parent = browseDir == null ? null : browseDir.getParentFile();
        if (parent == null) return;
        enterBrowse(parent);
    }

    private void exitBrowse() {
        browsing = false;
        browseDir = null;
        binding.browserBar.setVisibility(View.GONE);
        binding.files.setVisibility(View.GONE);
        binding.tabsScroll.setVisibility(View.VISIBLE);
        binding.sites.setVisibility(View.VISIBLE);
        binding.status.setVisibility(View.VISIBLE);
        binding.phpRoot.setVisibility(View.VISIBLE);
        refreshList();
    }

    @Override
    public void onFileClick(File file) {
        if (file.isDirectory()) {
            enterBrowse(file);
            return;
        }
        // 点一个文件就用那一个：只加这一个文件，不扫整个文件夹。
        pendingUseFile = file;
        scanFiles(List.of(file));
    }

    /** 按文件名在已入库的本地源里找对应的站点（一个文件通常对应一个站点；配置文件可能对应多个，找不到就返回 null）。 */
    private Site findSiteForFile(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String key = dot > 0 ? name.substring(0, dot) : name;
        for (Site site : LocalSource.getSites()) {
            if (key.equals(site.getKey()) || site.getKey().startsWith(key + "_")) return site;
        }
        return null;
    }

    private void buildTabs(List<String> categories) {
        binding.tabs.removeAllViews();
        for (String name : categories) {
            MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(name);
            button.setSelected(name.equals(category));
            button.setOnClickListener(v -> {
                category = name;
                refreshList();
            });
            binding.tabs.addView(button);
        }
    }

    private void refreshSecret() {
        boolean show = LocalSource.isShowSecret();
        binding.secret.setText(getString(R.string.local_source_secret) + (show ? "：开" : "：关"));
    }

    private void refreshPhpRoot() {
        String root = LocalSource.getPhpRoot();
        binding.phpRoot.setText(getString(R.string.local_source_php_root_label, TextUtils.isEmpty(root) ? getString(R.string.local_source_php_root_empty) : root));
    }

    private void toggleSecret() {
        boolean show = !LocalSource.isShowSecret();
        LocalSource.setShowSecret(show);
        LocalSource.invalidate();
        refreshSecret();
        refreshList();
        reloadConfig();
    }

    private void scanAll() {
        if (scanning) return;
        String dir = LocalSource.getScanDir();
        File root = TextUtils.isEmpty(dir) ? Path.root() : new File(dir);
        scanDir(root);
    }

    private void scanDir(File dir) {
        if (scanning || dir == null) return;
        scanning = true;
        binding.status.setText(R.string.local_source_scanning);
        new Thread(() -> {
            int count = LocalSource.scan(dir);
            onScanDone(count);
        }).start();
    }

    private void scanFiles(List<File> files) {
        if (scanning) return;
        scanning = true;
        binding.status.setText(R.string.local_source_scanning);
        new Thread(() -> {
            int count = LocalSource.scanFiles(files);
            onScanDone(count);
        }).start();
    }

    private void onScanDone(int count) {
        App.post(() -> {
            scanning = false;
            LocalSource.invalidate();
            boolean skippedLarge = LocalSource.takeSkippedLargeDir();
            if (pendingUseFile != null) {
                File file = pendingUseFile;
                pendingUseFile = null;
                // 先重载配置让新加的源进站点列表，再切到这个文件对应的源。
                reloadConfig(() -> {
                    if (!isAdded()) return;
                    Site site = findSiteForFile(file);
                    if (site != null) {
                        VodConfig.get().setHome(site);
                        ConfigEvent.vod();
                        dismiss();
                    } else {
                        refreshList();
                        if (skippedLarge) Notify.show(R.string.local_source_too_many_files);
                        else Notify.show(getString(R.string.local_source_imported, count));
                    }
                });
                return;
            }
            refreshList();
            // 有文件夹因文件过多被跳过：只提示这句，不再报导入数。
            if (skippedLarge) Notify.show(R.string.local_source_too_many_files);
            else Notify.show(getString(R.string.local_source_imported, count));
            if (count > 0) reloadConfig();
        });
    }

    /** 重新加载当前点播配置，让新扫到的本地源出现在首页站点列表里。 */
    private void reloadConfig() {
        reloadConfig(null);
    }

    private void reloadConfig(Runnable onSuccess) {
        VodConfig.load(Config.vod(), new Callback() {
            @Override
            public void success() {
                ConfigEvent.vod();
                if (onSuccess != null) onSuccess.run();
            }
        });
    }

    private void removeSelected() {
        List<String> keys = new ArrayList<>();
        for (Site site : adapter.getItems()) if (adapter.isSelected(site)) keys.add(site.getKey());
        if (keys.isEmpty()) return;
        int count = keys.size();
        // 直接删原始条目：拿已转换的 getSites() 再 saveSites() 会把 http://127.0.0.1:端口/file/... 写回盘里，端口一变就全空。
        LocalSource.removeByKeys(keys);
        adapter.clearSelection();
        refreshList();
        Notify.show(getString(R.string.local_source_deleted, count));
        reloadConfig();
    }

    private void clearAll() {
        if (LocalSource.getSites().isEmpty()) return;
        LocalSource.clear();
        refreshList();
        Notify.show(getString(R.string.local_source_deleted, 0));
        reloadConfig();
    }

    @Override
    public void onItemClick(Site item) {
        VodConfig.get().setHome(item);
        ConfigEvent.vod();
        dismiss();
    }

    @Override
    public void onSelectionChanged() {
    }
}
