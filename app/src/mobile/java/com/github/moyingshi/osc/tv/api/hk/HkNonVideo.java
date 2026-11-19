package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.bean.Flag;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.ui.activity.AudioActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.novel.NovelRouter;
import com.github.moyingshi.osc.tv.utils.AudioUtil;
import com.github.moyingshi.osc.tv.utils.Notify;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 非影视类规则分流（第 7 步之九，见 docs/miniapp-nonvideo.md）。
 * <p>
 * 详情页点选集时按规则 type 分流，不进视频播放器的视频轨道：
 * <ul>
 *   <li>小说/漫画（read/cartoon）：先跑 {@code sdetail_find_rule} 二级详情取
 *       章节内容（正文进 {@link NovelRouter#openReader}，图集进
 *       {@link NovelRouter#openPics}），没有再跑懒解析；</li>
 *   <li>音乐（music）：懒解析出音频地址后走现有 {@link AudioActivity}（歌词
 *       用第 8 步已接通的 {@code PlayOutcome.lyric}），非音频地址回落视频播放器；</li>
 *   <li>图片（picture）：{@code pics://} 或图片直链进图集阅读器，解析不出报
 *       明确提示，不崩、不进空白页。</li>
 * </ul>
 * 阅读主题不新造：正文只拼 {@code novel://} payload 交给现有
 * {@code WebReaderActivity}（模板自带主题/翻页/目录/自动滚动）。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkNonVideo {

    private static final String TAG = "HkNonVideo";

    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"};

    private HkNonVideo() {
    }

    /** 阅读器内容：kind 1=小说正文，2=图集（与 WebReaderActivity.EXTRA_KIND 同款）。 */
    private static final class Content {
        int kind;
        String payload = "";
    }

    /** 规则 type 是不是非影视类（read/cartoon/music/picture）。all/video/tool 等走原视频路。 */
    public static boolean isNonVideoRule(HkRule rule) {
        String t = typeOf(rule);
        return "read".equals(t) || "cartoon".equals(t) || "music".equals(t) || "picture".equals(t);
    }

    /** 小说/漫画类（章节形态相同，共用 sdetail + 正文/图集两路出口）。 */
    public static boolean isReadRule(HkRule rule) {
        String t = typeOf(rule);
        return "read".equals(t) || "cartoon".equals(t);
    }

    /** 音乐类。 */
    public static boolean isMusicRule(HkRule rule) {
        return "music".equals(typeOf(rule));
    }

    /** 图片类。 */
    public static boolean isPictureRule(HkRule rule) {
        return "picture".equals(typeOf(rule));
    }

    private static String typeOf(HkRule rule) {
        if (rule == null) return "";
        return rule.getType().trim().toLowerCase(Locale.ROOT);
    }

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
                if (isReadRule(r)) {
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
                    if (isReadRule(r)) {
                        Content content = new Content();
                        content.kind = 1;
                        content.payload = novelPayload(ep.getTitle(), url);
                        postOpen(act, r, d, l, ep, durl, v, content);
                        return;
                    }
                }
                if (!TextUtils.isEmpty(url) && isImageUrl(url) && (isPictureRule(r) || isReadRule(r))) {
                    // 图片直链：进图集阅读器（单张也按一页图集打开）。
                    Content content = new Content();
                    content.kind = 2;
                    content.payload = "pics://" + url;
                    postOpen(act, r, d, l, ep, durl, v, content);
                    return;
                }
                if (isMusicRule(r)) {
                    // 音乐回落：URL 无音频后缀时靠视频播放器兜底（它也能播音频流）。
                    App.post(() -> playVideo(act, r, d, l, ep, durl, v));
                    return;
                }
                if (isPictureRule(r)) {
                    // 图片解析不出可用内容：报明确提示，不进空白页。
                    App.post(() -> Notify.show(R.string.hk_action_pics_unsupported));
                    return;
                }
                if (isReadRule(r)) {
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
