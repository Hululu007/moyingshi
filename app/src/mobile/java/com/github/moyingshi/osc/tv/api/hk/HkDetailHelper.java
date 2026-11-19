package com.github.moyingshi.osc.tv.api.hk;

import android.text.Html;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 详情卡片切分，对应魔影小程序 HkSiteAdapter 里的 pickEpisodes（见逆向报告
 * §7.1，全文可读，直译）+ 官方文档“连续选集/章节标识”（连续的同一 col_type
 * 即连续选集/章节）。
 * <p>
 * 详情规则输出的原始卡片按序切成三类：信息卡（pic_1 / movie_1_vertical_pic，
 * 标题/海报/简介）+ 线路头（text_1/2/3/text_center_1 且 URL 不可播）+ 选集
 *（pickEpisodes 判定）。选集按线路头分组；无线路头时归入默认线路“选集”。
 * 臻品fix 的 tab 卡片（hiker://empty 的切换指令）是动作 URL，不会进选集，
 * 只当空线路头被丢掉；多线路切换（putVar + refreshPage）是第 5–7 步的事。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkDetailHelper {

    private HkDetailHelper() {
    }

    /** 详情信息头：标题/海报/简介。 */
    public static class Info {
        public String title = "";
        public String pic = "";
        public String desc = "";
    }

    /** 一条线路：线路名 + 该线路的选集卡片（按原顺序）。 */
    public static class Line {
        public String title = "";
        public List<HkCard> episodes = new ArrayList<>();
    }

    /** 切分结果：信息头 + 若干线路（0 集的空线路已丢掉）+ 可点动作行。 */
    public static class Detail {
        public Info info = new Info();
        public List<Line> lines = new ArrayList<>();
        /**
         * 可点动作行（第 7 步之七）：点开要执行动作的卡片（动作 URL，或含
         * {@code @lazyRule=}/{@code @input=}/{@code @rule=} 的懒链接），按原顺序。
         * 臻品fix 的线路 tab 与排序切换卡片都在这里；它们同时仍按原样参与
         * 线路切分（见 split），行结构与行标题不变。
         */
        public List<HkCard> actions = new ArrayList<>();
    }

    private static final String DEFAULT_LINE_TITLE = "选集";

    /**
     * 把详情规则输出的原始卡片切成信息头 + 分线路选集。
     * fallback（列表卡片的标题/海报/简介）在没有信息卡、或信息卡字段为空时补位。
     */
    public static Detail split(List<HkCard> cards, String fbTitle, String fbPic, String fbDesc) {
        Detail detail = new Detail();
        detail.info.title = fbTitle == null ? "" : fbTitle;
        detail.info.pic = fbPic == null ? "" : fbPic;
        detail.info.desc = fbDesc == null ? "" : fbDesc;
        if (cards == null || cards.isEmpty()) return detail;
        List<HkCard> rest = new ArrayList<>(cards);
        // 1. 信息卡：第一张 pic_1 / movie_1_vertical_pic，取走后不再参与选集判定。
        for (int i = 0; i < rest.size(); i++) {
            HkCard card = rest.get(i);
            if (card == null) continue;
            String t = card.getColType().trim().toLowerCase(Locale.ROOT);
            if ("pic_1".equals(t) || "movie_1_vertical_pic".equals(t)) {
                if (!TextUtils.isEmpty(card.getTitle())) detail.info.title = stripHtml(card.getTitle());
                if (!TextUtils.isEmpty(card.getPic())) detail.info.pic = card.getPic();
                if (!TextUtils.isEmpty(card.getDesc())) detail.info.desc = stripHtml(card.getDesc());
                rest.remove(i);
                break;
            }
        }
        // 2. 逐卡走：线路头开新线路，选集归入当前线路，其余丢弃。
        boolean hasText5 = false;
        for (HkCard card : rest) {
            if (card != null && "text_5".equalsIgnoreCase(card.getColType().trim())) {
                hasText5 = true;
                break;
            }
        }
        Line current = null;
        for (HkCard card : rest) {
            if (card == null) continue;
            if (isLineHeader(card)) {
                // 可动作的线路头（臻品fix 的 tab/排序切换卡片）收进动作行点开执行，
                // 同时仍按原样开线路：选集挂在哪条线路、行标题是什么，都与原来一致
                // （排序卡片开的那条线仍是选集的归属；tab 卡片开的空线照常在第 3 步丢掉）。
                if (isActionable(card)) detail.actions.add(card);
                current = new Line();
                current.title = stripHtml(card.getTitle());
                if (TextUtils.isEmpty(current.title)) current.title = DEFAULT_LINE_TITLE;
                detail.lines.add(current);
                continue;
            }
            if (isEpisodeCard(card, hasText5)) {
                if (current == null) {
                    current = new Line();
                    current.title = DEFAULT_LINE_TITLE;
                    detail.lines.add(current);
                }
                current.episodes.add(card);
                continue;
            }
            // 非线路头、非选集、但 URL 可动作的卡片（如 col_type 不在 text 族的
            // 动作卡片），也收进动作行（不参与线路切分）。
            if (isActionable(card)) detail.actions.add(card);
        }
        // 3. 丢掉 0 集的空线路。
        List<Line> kept = new ArrayList<>();
        for (Line line : detail.lines) {
            if (!line.episodes.isEmpty()) kept.add(line);
        }
        detail.lines = kept;
        return detail;
    }

    /** 这张卡片点开是不是要执行动作（动作 URL，或含 @lazyRule=/@input=/@rule= 的懒链接）。 */
    static boolean isActionable(HkCard card) {
        if (card == null || !card.usable()) return false;
        String url = card.getUrl().trim();
        return HkRouter.isActionUrl(url)
                || url.contains("@lazyRule=") || url.contains("@input=") || url.contains("@rule=");
    }

    /** 线路头：text_1/2/3/text_center_1，且 URL 为空、或为动作 URL、或以 # 开头。 */
    static boolean isLineHeader(HkCard card) {
        String t = card.getColType().trim().toLowerCase(Locale.ROOT);
        if (!"text_1".equals(t) && !"text_2".equals(t) && !"text_3".equals(t) && !"text_center_1".equals(t)) {
            return false;
        }
        String url = card.getUrl().trim();
        return url.isEmpty() || HkRouter.isActionUrl(url) || url.startsWith("#");
    }

    /**
     * 逐卡版 pickEpisodes（直译逆向报告 §7.1）：整表有 text_5 时只有 text_5 是选集；
     * 否则用第二遍谓词。调用方须先取走信息卡、判过线路头。
     */
    static boolean isEpisodeCard(HkCard card, boolean hasText5) {
        if (card == null || !card.usable()) return false;
        String t = card.getColType().trim().toLowerCase(Locale.ROOT);
        if (hasText5) return "text_5".equals(t);
        if (t.contains("button") || t.contains("input") || t.contains("header") || t.contains("tab")
                || t.contains("title") || "text_1".equals(t)) {
            return false;
        }
        String url = card.getUrl().trim();
        if (HkRouter.isActionUrl(url)) return false;
        return url.startsWith("http://") || url.startsWith("https://")
                || url.contains("@lazyRule=") || url.contains("@rule=") || url.startsWith("js:");
    }

    /** detail_col_type → 选集网格列数（外推，见任务文档）：text_2→2，text_3→3，movie_1→1，其余 4。 */
    public static int columnsFor(String detailColType) {
        if (TextUtils.isEmpty(detailColType)) return 4;
        String t = detailColType.trim().toLowerCase(Locale.ROOT);
        if ("text_2".equals(t)) return 2;
        if ("text_3".equals(t)) return 3;
        if ("movie_1".equals(t)) return 1;
        return 4;
    }

    /** 去掉标题里的 HTML 标签（规则标题常带 <small><font>）。ui.hk 的动作行渲染也用它。 */
    public static String stripHtml(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String out = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString();
        return out == null ? "" : out.trim();
    }
}
