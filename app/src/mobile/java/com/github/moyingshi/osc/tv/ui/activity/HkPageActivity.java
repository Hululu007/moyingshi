package com.github.moyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkDetailHelper;
import com.github.moyingshi.osc.tv.api.hk.HkEngine;
import com.github.moyingshi.osc.tv.api.hk.HkPageParser;
import com.github.moyingshi.osc.tv.api.hk.HkParserKit;
import com.github.moyingshi.osc.tv.api.hk.HkPlay;
import com.github.moyingshi.osc.tv.api.hk.HkRouter;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkRuleFetch;
import com.github.moyingshi.osc.tv.api.hk.HkRuleManager;
import com.github.moyingshi.osc.tv.api.hk.HkSelector;
import com.github.moyingshi.osc.tv.api.hk.HkShareHelper;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.api.hk.HkSubpage;
import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.bean.Flag;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.ActivityHkListBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.ui.dialog.PhotoViewerDialog;
import com.github.moyingshi.osc.tv.ui.hk.HkListFragment;
import com.github.moyingshi.osc.tv.ui.novel.NovelRouter;
import com.github.moyingshi.osc.tv.ui.web.CatWebActivity;
import com.github.moyingshi.osc.tv.utils.AudioUtil;
import com.github.moyingshi.osc.tv.utils.FileUtil;
import com.github.moyingshi.osc.tv.utils.Notify;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 海阔小程序子页面宿主（mobile 专属，阶段 7a）。
 * <p>
 * 星辰同名类（`app/src/mobile/java/com/fongmi/android/tv/ui/activity/HkPageActivity.java`，
 * 8476 行、315 方法）是 hk 总壳（规则列表/内容列表/详情 V4/内嵌 WebView/阅读器会话/
 * 合集浏览/导入导出全量），我方已有管理页（`HkMiniappActivity`）、列表页
 * （`HkListActivity`）、详情页（`HkDetailActivity`）与导入导出助手（6a/6b），
 * 全搬等于重复造壳并拖入十余个不存在的依赖（`ReadingRouter`/`HkArticleFragment`/
 * `HkWebViewHolder`/`HkRadioRouter` 等），故<b>不全搬</b>，只搬三件缺口：
 * <ul>
 *   <li>{@link #openPage}：hiker://page/ 子页面，经 {@link HkPageParser#getNextPage}
 *       组子规则开新页（星辰 `HkListActivity.openPageUrl` 的我方对位）；</li>
 *   <li>{@link #handleDealUrl}：pics/x5/web/image/magnet 全套分流（星辰
 *       `HkPageActivity.handleDealUrl` 的我方对位，详情面与列表面共用）；</li>
 *   <li>{@link #openCollectionBrowser}：合集浏览面（6b 遗留，合集包 added 计 0 随之修）；</li>
 * </ul>
 * 另承接已删 {@code HkNonVideo} 的非影视选集路由（{@link #routeEpisode} 一族逐字移入，
 * 行为不变，类型谓词归 {@link HkRouter}）。详见 docs/miniapp-xingchen-7a-page-activity.md。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkPageActivity extends BaseActivity {

    private static final String TAG = "HkPage";

    /** 隐式启动的 action 后缀（完整 action = applicationId + ACTION_SUFFIX，与 HkListActivity 同形）。 */
    public static final String ACTION_SUFFIX = ".hk_page";

    /** Intent extra：子页面标题（页名 → 条目标题 → 父标题的回退链，见 openPage）。 */
    public static final String EXTRA_TITLE = "title";

    /** Intent extra：子规则 JSON（HkPageParser.getNextPage 产物，HkRule.toJson）。 */
    public static final String EXTRA_RULE_JSON = "rule_json";

    /** Intent extra：页地址 query 参数 JSON（供 $.importParam，见 openPage）。 */
    public static final String EXTRA_IMPORT_PARAMS = "import_params";

    private ActivityHkListBinding binding;

    /**
     * 子页面开页（星辰 `HkListActivity.openPageUrl` 的我方对位）。
     * 组页照 8.83 toNextPage：`HkPageParser.getNextPage` 组子规则；组不出（返 null／抛
     * `ParseException`）就弹错留本页、绝不拿原规则回落开页（回落即“点二级又重开一个首页”）。
     *
     * @param extraJson 条目 extra JSON（getNextPage 的 paramsJson，供 MY_PARAMS；列表点击目前
     *                  在 Vod 转换处丢 extra，传进来的是空串，组页以 pageUrl 本身为准）
     * @return true=已开新页，false=组页失败（调用方回落 5e 的 openSubpage）
     */
    public static boolean openPage(Context context, HkRule parentRule, String pageUrl,
                                   String extraJson, String title) {
        if (context == null || parentRule == null || TextUtils.isEmpty(pageUrl)) return false;
        try {
            HkRule sub = HkPageParser.getNextPage(parentRule, pageUrl.trim(), extraJson);
            if (sub == null) {
                Notify.show("找不到对应的页面");
                return false;
            }
            // 官 toNextPage λ16：条目 pages 位覆写组出规则的 pages——HkCard 无 pages 字段，
            // 组出规则自带 pages，不覆写。
            sub.clearNav();
            String pageTitle = pageTitle(parentRule, pageUrl, title);
            Intent intent = new Intent(context, HkPageActivity.class);
            intent.putExtra(EXTRA_TITLE, pageTitle);
            intent.putExtra(EXTRA_RULE_JSON, sub.toJson());
            HkSubpage.Parsed parsed = HkSubpage.parse(pageUrl.trim());
            if (parsed != null && parsed.query != null && !parsed.query.isEmpty()) {
                intent.putExtra(EXTRA_IMPORT_PARAMS, App.gson().toJson(parsed.query));
            }
            context.startActivity(intent);
            return true;
        } catch (HkPageParser.ParseException e) {
            String msg = e.getMessage();
            Notify.show(TextUtils.isEmpty(msg) ? "找不到对应的页面" : msg);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "openPage", e);
            Notify.show("找不到对应的页面");
            return false;
        }
    }

    /** 页名只作标题显示用：在父规则 pages 里按 path 找 name，找不到走条目标题/父标题回退链（星辰 openPage 同款）。 */
    private static String pageTitle(HkRule parentRule, String pageUrl, String fallbackTitle) {
        try {
            String path = pageUrl.trim().substring("hiker://page/".length());
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int hh = path.indexOf('#');
            if (hh >= 0) path = path.substring(0, hh);
            String pagesJson = parentRule.getPages();
            if (!TextUtils.isEmpty(pagesJson)) {
                org.json.JSONArray pages = new org.json.JSONArray(pagesJson);
                for (int i = 0; i < pages.length(); i++) {
                    org.json.JSONObject p = pages.optJSONObject(i);
                    if (p != null && path.trim().equals(p.optString("path"))) {
                        String name = p.optString("name");
                        if (!TextUtils.isEmpty(name)) return name;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!TextUtils.isEmpty(fallbackTitle)) return fallbackTitle;
        String title = parentRule.getTitle();
        return title == null ? "" : title;
    }

    @Override
    protected ViewBinding getBinding() {
        if (binding == null) {
            binding = ActivityHkListBinding.inflate(getLayoutInflater());
        }
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "";
        String ruleJson = getIntent().getStringExtra(EXTRA_RULE_JSON);
        HkRule rule = HkRule.parse(ruleJson);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title);
        }
        if (rule == null) {
            // 子规则 JSON 坏了：报一句就走，不进空白页（HkListFragment 的“规则缺失”错误态同款）。
            Notify.show(R.string.hk_list_rule_missing);
            finish();
            return;
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.hk_list_container,
                            HkListFragment.newInstanceWithRuleJson(ruleJson, title,
                                    getIntent().getStringExtra(EXTRA_IMPORT_PARAMS)))
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * dealKind 分流（星辰 `HkPageActivity.handleDealUrl` 的我方对位，详情面与列表面共用）。
     * <p>
     * 星辰五种 pics/x5/web/image/magnet；default 回退 V4——我方无 V4 之外的回退页，
     * 未知 kind 回 false，调用方走原路。video 不在本方法内（详情面 `handleDealKind` 的
     * video 支仍走 `playDirect`）。
     *
     * @param vod 图集/直播直用 Vod（详情面传详情 Vod；为空时按详情面 minimalVod 同款现拼）
     * @return true=已消费，false=未知 kind（调用方走原路）
     */
    public static boolean handleDealUrl(Activity activity, HkRule rule, String kind, String url,
                                        String title, String pic, Vod vod) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (rule == null || TextUtils.isEmpty(kind) || TextUtils.isEmpty(url)) return false;
        try {
            switch (kind) {
                case "pics": {
                    // 星辰进漫画阅读器（ReadingSession kind=COMIC + 进度续读）；我方无 Reading
                    // 会话栈，对位是详情面 5d 已定的 NovelRouter.openPics。无图拦截 toast
                    // 保留 5d 口径（星辰“入口无图不许拦”是因其阅读器自带回退解析，我方无该回退）。
                    List<String> pics = HkRouter.parsePics(url);
                    if (!HkRouter.hasLoadablePic(pics)) {
                        Notify.show(R.string.hk_action_pics_unsupported);
                        return true;
                    }
                    Flag flag = new Flag("图集");
                    Episode ep = Episode.create("图集", url);
                    flag.getEpisodes().add(ep);
                    NovelRouter.openPics(activity, HkStore.siteOf(rule).getKey(), flag,
                            vod != null ? vod : minimalVod(rule, title, pic, url), ep,
                            "pics://" + TextUtils.join("&&", pics));
                    return true;
                }
                case "x5": {
                    // 星辰无 X5 引擎，走系统 WebView 顶替（5c 已定）；WebView 载不了 x5://
                    // scheme，先剥前缀（5e 顺手修）。
                    String target = url.replaceFirst("(?i)^x5://", "").trim();
                    if (target.isEmpty()) return true;
                    activity.startActivity(CatWebActivity.intent(activity, target));
                    return true;
                }
                case "web": {
                    // 星辰 web 支首段的电台问询（HkRadioRouter.claimSignal）不搬（电台面不在移植范围）；
                    // 其余直开站内 WebView（5c 已定）。
                    activity.startActivity(CatWebActivity.intent(activity, url));
                    return true;
                }
                case "image": {
                    PhotoViewerDialog.show(activity, Collections.singletonList(url), 0, null);
                    return true;
                }
                case "magnet": {
                    // 星辰首段 .torrent 种子走 startParseTorrent、P2P 链走自家播放器、其余走系统选应用；
                    // 我方瘦版 HkDetailBridge 无 startParseTorrent，三段统一收敛为详情面同款
                    // playDirect（VideoActivity.startHk 最小 Vod，迅雷链由播放器侧承接——5d 已定
                    // “海阔磁力走星辰播放器，不维持原版、不走系统选应用”）；.torrent 同路。
                    VideoActivity.startHk(activity,
                            vod != null ? vod : minimalVod(rule, title, pic, url),
                            "", title == null ? "" : title, url);
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable e) {
            Log.d(TAG, "handleDealUrl failed: " + e.getMessage());
            return false;
        }
    }

    /** 无详情 Vod 时的最小 Vod（dealKind 分流用；站 key 与 HkDetailBridge.toVod 同款 hk://）。 */
    private static Vod minimalVod(HkRule rule, String title, String pic, String url) {
        Vod vod = new Vod();
        vod.setSite(HkStore.siteOf(rule));
        vod.setId(url == null ? "" : url);
        vod.setName(title == null ? "" : title);
        vod.setPic(pic == null ? "" : pic);
        return vod;
    }

    // ---- 合集浏览面（星辰 HkPageActivity.openCollectionBrowser/handleCollectionEntry，见 §3.4） ----

    /** 合集目录浏览面：目录内条目按“目录优先、名忽略大小写”排序列出，目录递归进、文件进 handleCollectionEntry。 */
    public static void openCollectionBrowser(Activity activity, File dir) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (dir == null) return;
        File[] kids = dir.listFiles();
        List<File> items = new ArrayList<>();
        if (kids != null) {
            for (File k : kids) items.add(k);
        }
        items.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        if (items.isEmpty()) {
            Notify.show("合集目录是空的");
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).getName() + (items.get(i).isDirectory() ? "/" : "");
        }
        final Activity act = activity;
        new AlertDialog.Builder(act)
                .setTitle(dir.getName())
                .setItems(labels, (d, w) -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    File picked = items.get(w);
                    if (picked.isDirectory()) openCollectionBrowser(act, picked);
                    else handleCollectionEntry(act, picked);
                })
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    /**
     * 合集目录内条目点选分发（星辰 handleCollectionEntry 分发表 + 我方适配，见文档 §3.4）：
     * .mht/.mhtml 走网页；.hkshell 不执行（toast）；合集内 txt/json/hiker 走文本导入；
     * 合集内 hkzip/含 .hk小程序 的 zip 走包导入；合集内 hkpkg/含 .hk合集 的 zip 递归解压再开浏览面；
     * 其余 hiker/txt 文本导入；zip/hkzip/7z/rar 包导入；媒体走播放器；图片/未知走系统选择器。
     */
    private static void handleCollectionEntry(Activity activity, File f) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || f == null) return;
        String name = f.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        String path = f.getAbsolutePath();
        boolean inCollection = path.contains(".hk合集");
        String ext = "";
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) ext = lower.substring(dot + 1);
        if (ext.equals("mht") || ext.equals("mhtml")) {
            // 星辰 HkDetailBridge.goWeb（我方瘦版无此方法）→ CatWebActivity 直开 file://。
            activity.startActivity(CatWebActivity.intent(activity, "file://" + path));
            return;
        }
        if (lower.endsWith(".hkshell")) {
            // .hkshell 脚本执行面（读全文进输入框、确认后执行）不搬：执行任意脚本文件超出
            // hk 引擎移植范围；本支只 toast，不执行。
            Notify.show("暂不支持执行脚本文件");
            return;
        }
        if (inCollection && (ext.equals("txt") || ext.equals("json") || ext.equals("hiker"))) {
            importTextFile(activity, f);
            return;
        }
        if (inCollection && (ext.equals("hkzip") || (ext.equals("zip") && path.contains(".hk小程序")))) {
            importCollectedFile(activity, f);
            return;
        }
        if (inCollection && (ext.equals("hkpkg") || (ext.equals("zip") && path.contains(".hk合集")))) {
            final Activity act = activity;
            final File file = f;
            new Thread(() -> {
                try {
                    File dir = HkRuleManager.get().extractCollectionPackage(file);
                    App.post(() -> {
                        if (dir == null || act.isFinishing() || act.isDestroyed()) return;
                        Notify.show("请选择要导入的合集内容");
                        openCollectionBrowser(act, dir);
                    });
                } catch (Exception e) {
                    App.post(() -> Notify.show(e.getMessage()));
                }
            }).start();
            return;
        }
        if (ext.equals("hiker") || ext.equals("txt")) {
            importTextFile(activity, f);
            return;
        }
        if (ext.equals("zip") || ext.equals("hkzip") || ext.equals("7z") || ext.equals("rar")) {
            importCollectedFile(activity, f);
            return;
        }
        if (isHkMediaName(lower)) {
            // 星辰 VideoActivity.startHkPlay（我方 mobile 源集无此方法）→ 最小 Vod 直传
            // file 地址进播放器（与 HkNonVideo.minimalVod 的直传口径一致；site 置空站，
            // 不走站源解析）。
            Vod vod = new Vod();
            Site site = new Site();
            site.setKey("");
            vod.setSite(site);
            vod.setId("file://" + path);
            vod.setName(name);
            VideoActivity.startHk(activity, vod, "", name, "file://" + path);
            return;
        }
        openWithSystem(activity, f, isHkImageName(lower) ? "image/*" : "*/*");
    }

    /** 文本形条目导入（星辰 importTextFile 同位：读文本走既有文本导入链）。 */
    private static void importTextFile(Activity activity, File f) {
        final Activity act = activity;
        final File file = f;
        new Thread(() -> {
            try {
                String text;
                try (InputStream in = new FileInputStream(file)) {
                    text = readAll(in);
                }
                HkRule imported = HkShareHelper.importFromText(text);
                App.post(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (imported != null) {
                        Notify.show(act.getString(R.string.hk_miniapp_imported, 1));
                    }
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 包形条目导入：合集包先解压开浏览面（实计 added 在逐条导入时产生）；其余包走既有导入链并报数。 */
    private static void importCollectedFile(Activity activity, File f) {
        final Activity act = activity;
        final File file = f;
        new Thread(() -> {
            try {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (HkRuleManager.isCollectionPackage(file, lower)) {
                    File dir = HkRuleManager.get().extractCollectionPackage(file);
                    App.post(() -> {
                        if (dir == null || act.isFinishing() || act.isDestroyed()) return;
                        Notify.show("请选择要导入的合集内容");
                        openCollectionBrowser(act, dir);
                    });
                    return;
                }
                List<HkRule> rules = HkShareHelper.importFromFile(file);
                App.post(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (rules != null && !rules.isEmpty()) {
                        Notify.show(act.getString(R.string.hk_miniapp_imported, rules.size()));
                    } else {
                        Notify.show(R.string.hk_miniapp_no_rule);
                    }
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 媒体后缀判（星辰 isHkMediaName 逐字搬）。 */
    private static boolean isHkMediaName(String lower) {
        String[] suffixes = {".mp4", ".m3u8", ".flv", ".avi", ".3gp", ".mpeg", ".wmv",
                ".mov", ".rmvb", ".dat", ".mkv", ".ts", ".mp3", ".wav", ".ogg",
                ".flac", ".m4a"};
        for (String s : suffixes) if (lower.endsWith(s)) return true;
        return false;
    }

    /** 图片后缀判（星辰 isHkImageName 逐字搬，8.83 FileBrowser isImage 同位）。 */
    private static boolean isHkImageName(String lower) {
        String[] suffixes = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico",
                ".svg", ".avif"};
        for (String s : suffixes) if (lower.endsWith(s)) return true;
        return false;
    }

    /** 系统选择器外发（星辰 openWithSystem 同位：未知后缀与 .epub 形）。 */
    private static void openWithSystem(Activity activity, File f, String mime) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(FileUtil.getShareUri(f), mime);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(it, "选择应用打开"));
        } catch (Throwable e) {
            Notify.show("无应用可打开");
        }
    }

    /** 读全文（UTF-8）。 */
    private static String readAll(InputStream in) throws Exception {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
    // ---- 非影视选集路由（自 HkNonVideo 逐字移入，7a；行为不变，类型谓词归 HkRouter） ----

    /** 阅读器内容：kind 1=小说正文，2=图集（与 WebReaderActivity.EXTRA_KIND 同款）。 */
    private static final class Content {
        int kind;
        String payload = "";
    }

    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"};

    /**
     * 非影视类点选集分流（详情页调用，替代 HkPlayer.play）。
     * 解析在后台线程跑，阅读器/音频页/提示都 post 回主线程进；解析抛异常时弹
     * 异常信息，不回落进播放器（回落会把同一失败在取流时再跑一次）。
     * <p>
     * 5d：懒播放改走 {@link HkRouter#play(String)}（{@link HkPlay}）；Vod 由调用方
     * （详情页的 {@code HkDetailBridge.toVod} 结果）传入，阅读器与视频回落共用，
     * 不再经已删除的 {@code HkPlayer.buildVod} 现拼。`sdetail` 已随 HkExecutor 删除
     * 搬入本类（5e，私有静态方法，见下）。
     */
    public static void routeEpisode(Activity activity, HkRule rule, HkDetailHelper.Detail detail,
                                    HkDetailHelper.Line line, HkCard episode, String detailUrl, Vod vod) {
        if (activity == null || rule == null || detail == null || line == null || episode == null) return;
        final Activity act = activity;
        final HkRule r = rule;
        final HkDetailHelper.Detail d = detail;
        final HkDetailHelper.Line l = line;
        final HkCard ep = episode;
        final String durl = detailUrl;
        final Vod v = vod;
        new Thread(() -> {
            try {
                // 1. 小说/漫画：先跑 sdetail 二级详情取章节内容。
                if (HkRouter.isReadRule(r)) {
                    Content content = sdetailContent(r, ep.getUrl(), ep.getTitle());
                    if (content != null) {
                        postOpen(act, r, d, l, ep, durl, v, content);
                        return;
                    }
                }
                // 2. 懒解析（5d：改走新引擎 HkRouter.play）。
                HkPlay play = new HkRouter(r).play(ep.getUrl());
                // 图集 payload：HkPlay.pics 是图片列表，拼回 pics:// 原串进图集阅读器。
                String pics = play.getPics().isEmpty() ? ""
                        : "pics://" + TextUtils.join("&&", play.getPics());
                if (!TextUtils.isEmpty(pics)) {
                    Content content = new Content();
                    content.kind = 2;
                    content.payload = pics;
                    postOpen(act, r, d, l, ep, durl, v, content);
                    return;
                }
                String url = play.getUrl() == null ? "" : play.getUrl().trim();
                if (!TextUtils.isEmpty(url) && AudioUtil.isAudioUrl(url)) {
                    // 音频地址：走现有音频页，歌词用第 8 步的 lyric（.lrc 地址或内嵌文本）。
                    final String audioUrl = url;
                    final HkPlay out = play;
                    App.post(() -> AudioActivity.start(act, HkStore.siteOf(r).getKey(), audioUrl,
                            d.info.title, ep.getTitle(), d.info.pic, out.getHeaders(), out.getLyric()));
                    return;
                }
                // 5d：动作地址（toast:// 等）新引擎在 play() 内直接抛错，外层 catch 弹文案，
                // 与旧 PlayOutcome.notice 分支等价，此处不再单列。
                if (!TextUtils.isEmpty(url) && !isHttpUrl(url)) {
                    // 非 URL 的解析结果：当章节正文（小说/漫画）；音乐/图片类不认，当回落处理。
                    if (HkRouter.isReadRule(r)) {
                        Content content = new Content();
                        content.kind = 1;
                        content.payload = novelPayload(ep.getTitle(), url);
                        postOpen(act, r, d, l, ep, durl, v, content);
                        return;
                    }
                }
                if (!TextUtils.isEmpty(url) && isImageUrl(url) && (HkRouter.isPictureRule(r) || HkRouter.isReadRule(r))) {
                    // 图片直链：进图集阅读器（单张也按一页图集打开）。
                    Content content = new Content();
                    content.kind = 2;
                    content.payload = "pics://" + url;
                    postOpen(act, r, d, l, ep, durl, v, content);
                    return;
                }
                if (HkRouter.isMusicRule(r)) {
                    // 音乐回落：URL 无音频后缀时靠视频播放器兜底（它也能播音频流）。
                    App.post(() -> playVideo(act, r, d, l, ep, durl, v));
                    return;
                }
                if (HkRouter.isPictureRule(r)) {
                    // 图片解析不出可用内容：报明确提示，不进空白页。
                    App.post(() -> Notify.show(R.string.hk_action_pics_unsupported));
                    return;
                }
                if (HkRouter.isReadRule(r)) {
                    // 小说/漫画：懒结果只是章节页地址且 sdetail 已配置时，对它再跑一次 sdetail。
                    if (isHttpUrl(url) && !TextUtils.isEmpty(r.getSdetailFindRule())) {
                        Content content = sdetailContent(r, url, ep.getTitle());
                        if (content != null) {
                            postOpen(act, r, d, l, ep, durl, v, content);
                            return;
                        }
                    }
                    App.post(() -> Notify.show(R.string.hk_nonvideo_no_content));
                    return;
                }
                App.post(() -> playVideo(act, r, d, l, ep, durl, v));
            } catch (Exception e) {
                String msg = e.getMessage();
                App.post(() -> Notify.show(TextUtils.isEmpty(msg) ? "解析失败" : msg));
            }
        }).start();
    }

    /** 视频回落：用详情页的 Vod（无则最小 Vod）进播放器，集址直传、播放器内再解析。 */
    private static void playVideo(Activity act, HkRule r, HkDetailHelper.Detail d,
                                  HkDetailHelper.Line l, HkCard ep, String durl, Vod vod) {
        VideoActivity.startHk(act, vod != null ? vod : minimalVod(r, d, durl),
                l.title, ep.getTitle(), ep.getUrl());
    }

    /** 无详情 Vod 时的最小 Vod（站 key hk://，id=详情地址；无 flags，播放器内只按集址解析）。 */
    private static Vod minimalVod(HkRule rule, HkDetailHelper.Detail detail, String detailUrl) {
        Vod vod = new Vod();
        vod.setSite(HkStore.siteOf(rule));
        vod.setId(detailUrl == null ? "" : detailUrl);
        vod.setName(detail == null ? "" : detail.info.title);
        vod.setPic(detail == null ? "" : detail.info.pic);
        vod.setContent(detail == null ? "" : detail.info.desc);
        return vod;
    }

    /** 是不是 http(s) 地址（旧 HkExecutor.isHttpUrl 同款判定，5d 起本地化）。 */
    private static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /**
     * 二级详情（5e：自 HkExecutor 逐字移入，见 docs/miniapp-xingchen-5e-click-dispatch-cleanup.md §2.11）。
     * 对目标 URL（通常是章节页）再跑一次详情规则。字段为空回空列表；`*` 继承主规则（主规则为空也不抛错，
     * 回空列表）；非 `*` 跑二级规则；子页面地址走展开；http(s) 才抓页。
     * 调用方必须在后台线程跑。返回原始卡片，内容拼装由本类做。
     * 改动：DialogHost 形参删除（HkEngine.setDialogHost 已随适配段瘦身删除；新运行时
     * confirm/select/input 走 authGate/AuthBridgeEvent 授权链）；请求改走 HkRuleFetch。
     */
    private static List<HkCard> sdetail(HkRule rule, String url, Map<String, Object> initialVars)
            throws HkEngine.HkException {
        if (rule == null) throw new HkEngine.HkException("规则为空");
        String err = rule.validate();
        if (err != null) throw new HkEngine.HkException(err);
        String field = rule.getSdetailFindRule();
        if (TextUtils.isEmpty(field)) return new ArrayList<>();
        String raw = url == null ? "" : url.trim();
        if (TextUtils.isEmpty(raw)) throw new HkEngine.HkException("二级详情地址为空");
        String clean = HkParserKit.resolveUrl(rule.getUrl(), stripLazyMarkers(raw));
        if (HkSubpage.isSubpageUrl(clean)) return HkSubpage.expand(rule, clean);
        if (raw.contains("@lazyRule=") || raw.contains("@input=")) {
            throw new HkEngine.HkException("懒加载动作链接请从卡片点击执行（详情页动作行）");
        }
        String target = field.trim();
        if ("*".equals(target)) target = rule.getDetailFindRule();
        if (TextUtils.isEmpty(target)) return new ArrayList<>();
        if (TextUtils.isEmpty(clean)) throw new HkEngine.HkException("二级详情地址为空");
        HkRuleFetch.RequestSpec spec = HkRuleFetch.buildDetailRequest(rule, clean);
        String pageText = HkRuleFetch.isHttpUrl(spec.url) ? HkRuleFetch.fetch(spec) : "";
        return evalDetailRule(rule, target, spec.url, pageText, initialVars);
    }

    /** 对一页详情正文跑一条详情规则（JS 或选择器）；与 home() 的执行顺序一致（5e：自 HkExecutor 逐字移入，改动同 sdetail）。 */
    private static List<HkCard> evalDetailRule(HkRule rule, String detailRule, String pageUrl, String pageText,
                                               Map<String, Object> initialVars) throws HkEngine.HkException {
        HkEngine engine = new HkEngine();
        engine.prepare(rule, pageUrl, 1, "detail", pageText, initialVars);
        engine.evalPreRule();
        if (HkSelector.isJsRule(detailRule)) {
            return engine.evalFindRule(detailRule);
        }
        return HkCard.fromDetailItems(engine.getSelector().parseDetail(pageText, detailRule, pageUrl));
    }

    /** 去掉详情地址里的二级解析标记（@lazyRule= / @rule= 后的部分只在第 5 步求值，取数时只要干净的页面地址）。5e：自 HkExecutor 逐字移入，只本类用。 */
    private static String stripLazyMarkers(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        int at = u.indexOf("@lazyRule=");
        if (at >= 0) u = u.substring(0, at);
        at = u.indexOf("@rule=");
        if (at >= 0) u = u.substring(0, at);
        return u.trim();
    }

    /**
     * 对章节地址跑 sdetail 二级详情，拼出阅读器内容；无 sdetail / 无可用内容 /
     * 求值失败回 null（sdetail 只是“先试一次”，失败就走后面的懒解析回落，
     * 不能因为章节地址带 @lazyRule= 标记就把整次点击 abort 掉）。
     */
    private static Content sdetailContent(HkRule rule, String url, String chapterTitle) {
        if (TextUtils.isEmpty(rule.getSdetailFindRule())) return null;
        try {
            List<HkCard> cards = sdetail(rule, url, null);
            return contentFromCards(cards, chapterTitle);
        } catch (Exception e) {
            Log.w(TAG, "sdetail 求值失败，走懒解析回落 rule=" + rule.getTitle(), e);
            return null;
        }
    }

    /**
     * sdetail 卡片 → 阅读器内容：任一卡片 url 是 pics:// 直接当图集；
     * 否则收图片地址与正文（desc 优先、标题回落，去 HTML 标签）。
     * 启发式：正文很短（<100 字）且有图 → 图集（漫画页常只有页码标题）；
     * 否则有正文 → 小说；只有图 → 图集。
     */
    private static Content contentFromCards(List<HkCard> cards, String chapterTitle) {
        if (cards == null || cards.isEmpty()) return null;
        List<String> images = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (HkCard card : cards) {
            if (card == null) continue;
            String curl = card.getUrl() == null ? "" : card.getUrl().trim();
            if (curl.toLowerCase(Locale.ROOT).startsWith("pics://")) {
                Content content = new Content();
                content.kind = 2;
                content.payload = curl;
                return content;
            }
            if (isImageUrl(card.getPic())) images.add(card.getPic().trim());
            if (isImageUrl(curl)) images.add(curl);
            String part = !TextUtils.isEmpty(card.getDesc()) ? card.getDesc() : card.getTitle();
            part = HkDetailHelper.stripHtml(part);
            if (!TextUtils.isEmpty(part)) {
                if (text.length() > 0) text.append("\n\n");
                text.append(part);
            }
        }
        if (!images.isEmpty() && text.length() < 100) {
            Content content = new Content();
            content.kind = 2;
            content.payload = "pics://" + TextUtils.join("&&", images);
            return content;
        }
        if (text.length() > 0) {
            Content content = new Content();
            content.kind = 1;
            content.payload = novelPayload(chapterTitle, text.toString());
            return content;
        }
        if (!images.isEmpty()) {
            Content content = new Content();
            content.kind = 2;
            content.payload = "pics://" + TextUtils.join("&&", images);
            return content;
        }
        return null;
    }

    /** 在主线程打开阅读器（小说 kind=1 / 图集 kind=2，走现有 NovelRouter）。 */
    private static void postOpen(Activity activity, HkRule rule, HkDetailHelper.Detail detail,
                                 HkDetailHelper.Line line, HkCard episode, String detailUrl, Vod vod, Content content) {
        App.post(() -> {
            Flag flag = buildFlag(line);
            int index = line.episodes.indexOf(episode);
            Episode ep = index >= 0 && index < flag.getEpisodes().size()
                    ? flag.getEpisodes().get(index) : null;
            if (ep == null) {
                Log.w(TAG, "章节不在当前线路里，无法打开阅读器");
                Notify.show(R.string.hk_nonvideo_no_content);
                return;
            }
            String siteKey = HkStore.siteOf(rule).getKey();
            Vod v = vod != null ? vod : minimalVod(rule, detail, detailUrl);
            if (content.kind == 2) {
                NovelRouter.openPics(activity, siteKey, flag, v, ep, content.payload);
            } else {
                NovelRouter.openReader(activity, siteKey, flag, v, ep, content.payload);
            }
        });
    }

    /** 按当前线路拼阅读器章节表（Flag + Episode，与 toVod 的 Vod 组装同源）。 */
    private static Flag buildFlag(HkDetailHelper.Line line) {
        Flag flag = new Flag(line.title == null ? "" : line.title);
        for (HkCard card : line.episodes) {
            if (card == null) continue;
            String name = card.getTitle() == null ? "" : card.getTitle();
            String url = card.getUrl() == null ? "" : card.getUrl();
            flag.getEpisodes().add(Episode.create(name, url));
        }
        return flag;
    }

    /** 小说 payload：novel://{title, content}（WebReaderActivity 口径；JSON 失败时退化成纯文本正文）。 */
    private static String novelPayload(String title, String content) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("title", title == null ? "" : title);
            obj.put("content", content == null ? "" : content);
            return "novel://" + obj.toString();
        } catch (Exception e) {
            Log.w(TAG, "novel payload 组装失败，退化成纯文本", e);
            return "novel://" + (content == null ? "" : content);
        }
    }

    /** 是不是图片直链（去 query/fragment 后按后缀判断）。 */
    private static boolean isImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = url.trim().toLowerCase(Locale.ROOT);
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        int fragment = text.indexOf('#');
        if (fragment >= 0) text = text.substring(0, fragment);
        for (String ext : IMAGE_EXTS) if (text.endsWith(ext)) return true;
        return false;
    }
}
