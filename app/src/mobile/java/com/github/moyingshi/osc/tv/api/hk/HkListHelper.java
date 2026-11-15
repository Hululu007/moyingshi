package com.github.moyingshi.osc.tv.api.hk;

import android.text.Html;
import android.text.TextUtils;

import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Vod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 列表页的卡片筛选与转换，对应魔影小程序 HkSiteAdapter 里的 pickCards / toVods
 *（见逆向报告 §7.1，全文可读，直译）+ col_type 到本 App 列表样式的全表映射
 *（第 7 步之六：42 种实测类型 + 数字族通用解析，未知类型 fallback 默认卡片）。
 * <p>
 * pickCards 三遍筛选：先取 colType == 规则 col_type 的可用卡片，空则取可用且
 * 非动作 URL 的，再空则取全部可用卡片。toVods 把卡片转成本 App 的 Vod，
 * 直接复用首页同款 VodAdapter 渲染。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkListHelper {

    private HkListHelper() {
    }

    /** 动作 URL：不是播放/详情地址，是规则让 App 弹提示/复制/输入/确认/选择/下载的指令。 */
    public static boolean isActionUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.trim();
        return u.startsWith("toast://") || u.startsWith("copy://") || u.startsWith("input://")
                || u.startsWith("confirm://") || u.startsWith("select://")
                || u.startsWith("download://") || u.startsWith("hiker://empty");
    }

    /**
     * 卡片筛选（直译报告 §7.1 pickCards）：第一遍只留 colType 与规则 col_type
     * 一致的可用卡片；空了第二遍留可用且非动作 URL 的；还空第三遍留全部可用卡片。
     * 第四遍（第 7 步之三，西瓜影院）：前三遍都空时，留 URL 非空但标题为空的
     * {@code x5_webview_single} 卡片（内联 WebView 页），当一张可点卡片；
     * 内联 WebView 渲染是后续步骤，点开目前是详情错误态。
     */
    public static List<HkCard> pickCards(List<HkCard> list, HkRule rule) {
        List<HkCard> picked = new ArrayList<>();
        if (list == null || list.isEmpty()) return picked;
        String colType = rule == null ? "" : rule.getColType().trim();
        if (!colType.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && colType.equalsIgnoreCase(card.getColType().trim()) && card.usable()) {
                    picked.add(card);
                }
            }
        }
        if (picked.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && card.usable() && !isActionUrl(card.getUrl())) {
                    picked.add(card);
                }
            }
        }
        if (picked.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && card.usable()) {
                    picked.add(card);
                }
            }
        }
        if (picked.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && "x5_webview_single".equalsIgnoreCase(card.getColType().trim())
                        && !TextUtils.isEmpty(card.getUrl().trim())) {
                    picked.add(card);
                }
            }
        }
        return picked;
    }

    /** 卡片转 Vod（直译报告 §7.1 toVods，去掉了魔影的 CACHE——详情页才按 URL 取卡片）。 */
    public static List<Vod> toVods(List<HkCard> list, String typeName) {
        List<Vod> vods = new ArrayList<>();
        if (list == null) return vods;
        for (HkCard card : list) {
            if (card == null) continue;
            String url = card.getUrl().trim();
            String title = card.getTitle().trim();
            if (url.isEmpty()) continue;
            if (title.isEmpty()) {
                // 只有 x5_webview_single 卡片允许标题为空（第 7 步之三第四遍筛出来的），
                // 回落标题“网页”；其他无标题卡片仍丢掉。
                if (!"x5_webview_single".equalsIgnoreCase(card.getColType().trim())) continue;
                title = "网页";
            }
            // rich_text / long_text 卡片的标题常带 HTML 标签，去掉再显示；其余类型原样。
            if (isRichText(card)) title = stripHtml(title);
            Vod vod = new Vod();
            vod.setId(url);
            vod.setName(title);
            vod.setPic(card.getPic());
            // rich_text / long_text 卡片的描述常带 HTML 标签，去掉再显示；其余类型原样
            //（remarks 与 content 取同一份 desc）。
            String desc = card.getDesc();
            if (isRichText(card)) desc = stripHtml(desc);
            vod.setRemarks(desc);
            vod.setContent(desc);
            vod.setTypeName(typeName == null ? "" : typeName);
            vods.add(vod);
        }
        return vods;
    }

    /** 这张卡片是不是富文本/长文本行（标题与描述要去 HTML 标签再显示）。 */
    private static boolean isRichText(HkCard card) {
        String t = card.getColType().trim().toLowerCase(Locale.ROOT);
        return "rich_text".equals(t) || "long_text".equals(t);
    }

    /** 去掉文本里的 HTML 标签（与 HkDetailHelper.stripHtml 同款写法）。 */
    private static String stripHtml(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String out = Html.fromHtml(text.trim(), Html.FROM_HTML_MODE_LEGACY).toString();
        return out == null ? "" : out.trim();
    }

    /**
     * 一种 col_type 的形态：列表样式 + 网格列数（0 = 走 App 默认列数；list 形态下恒 0）。
     * 全表只在这里定义一次，{@link #styleFor} 与 {@link #columnsFor} 都是它的薄壳，
     * 列表页、搜索页、内嵌首页都从同一处取样式，不各自写映射。
     */
    private static final class Form {
        final Style style;
        final int columns;

        Form(Style style, int columns) {
            this.style = style;
            this.columns = columns;
        }
    }

    /** 默认形态：海报网格 rect 0.75 + 默认列数（空/未知 col_type 的 fallback）。 */
    private static final Form DEFAULT_FORM = new Form(Style.rect(), 0);
    /** 单列行形态。 */
    private static final Form LIST_FORM = new Form(Style.list(), 0);

    /**
     * col_type 全表（第 7 步之六）：42 种实测类型逐一登记 + 数字族通用解析。
     * 实测分布见任务文档 docs/miniapp-coltype.md §4（4255 条合集统计）：
     * 前三名 movie_3 2234 / movie_2 563 / movie_3_marquee 503，占 77.6%。
     * 形态来源：movie_3/movie_2/movie_1/text_1/2/3 沿用第 3 步已发布映射；
     * x5_webview_single 沿用第 7 步之三；其余按名字语义外推，未经真机对照。
     */
    private static final Map<String, Form> FORMS = new HashMap<>();

    static {
        // 影视海报族
        FORMS.put("movie_3", new Form(Style.rect(), 3));
        FORMS.put("movie_2", new Form(Style.rect(), 2));
        FORMS.put("movie_3_marquee", new Form(Style.rect(), 3));
        FORMS.put("movie_4", new Form(Style.rect(), 4));
        FORMS.put("movie_5", new Form(Style.rect(), 5));
        FORMS.put("movie_6", new Form(Style.rect(), 6));
        FORMS.put("movie_1", LIST_FORM);
        FORMS.put("movie_1_left_pic", LIST_FORM);
        FORMS.put("movie_1_vertical_pic", LIST_FORM);
        // 文字族
        FORMS.put("text_1", LIST_FORM);
        FORMS.put("text_2", LIST_FORM);
        FORMS.put("text_3", LIST_FORM);
        FORMS.put("text_4", LIST_FORM);
        FORMS.put("text_5", LIST_FORM);
        FORMS.put("text_center_1", LIST_FORM);
        FORMS.put("long_text", LIST_FORM);
        FORMS.put("rich_text", LIST_FORM);
        // 图片族
        FORMS.put("pic_1", new Form(new Style("rect", 1.33f), 1));
        FORMS.put("pic_1_card", new Form(Style.rect(), 1));
        FORMS.put("pic_1_full", new Form(new Style("rect", 1.33f), 1));
        FORMS.put("pic_2", new Form(new Style("rect", 1.0f), 2));
        FORMS.put("pic_2_card", new Form(Style.rect(), 2));
        FORMS.put("pic_3", new Form(new Style("rect", 1.0f), 3));
        FORMS.put("card_pic_1", new Form(Style.rect(), 1));
        FORMS.put("card_pic_2", new Form(Style.rect(), 2));
        FORMS.put("card_pic_3", new Form(Style.rect(), 3));
        FORMS.put("card_pic_3_center", new Form(Style.rect(), 3));
        FORMS.put("card_pic_2_2_left", new Form(Style.rect(), 2));
        // 图标族（无 round 后缀 = 方图 rect 1.0；有 round = 圆图 oval）
        FORMS.put("icon_2", new Form(new Style("rect", 1.0f), 2));
        FORMS.put("icon_2_round", new Form(new Style("oval", 1.0f), 2));
        FORMS.put("icon_3_fill", new Form(new Style("rect", 1.0f), 3));
        FORMS.put("icon_3_round_fill", new Form(new Style("oval", 1.0f), 3));
        FORMS.put("icon_4", new Form(new Style("rect", 1.0f), 4));
        FORMS.put("icon_4_card", new Form(new Style("rect", 1.0f), 4));
        FORMS.put("icon_5", new Form(new Style("rect", 1.0f), 5));
        FORMS.put("icon_small_4", new Form(new Style("rect", 1.0f), 4));
        FORMS.put("icon_round_4", new Form(new Style("oval", 1.0f), 4));
        FORMS.put("icon_round_small_4", new Form(new Style("oval", 1.0f), 4));
        FORMS.put("avatar", new Form(new Style("oval", 1.0f), 4));
        // 控制 / 结构类（海阔原 App 是输入框/按钮组/滚动按钮/空白占位，本 App 没有对应控件，
        // 降级为单列文字行：有标题就显示、可点，点开进详情错误态，不崩）
        FORMS.put("flex_button", LIST_FORM);
        FORMS.put("input", LIST_FORM);
        FORMS.put("scroll_button", LIST_FORM);
        FORMS.put("icon_1_search", LIST_FORM);
        FORMS.put("big_big_blank_block", LIST_FORM);
        // 内联 WebView 单页：沿用第 7 步之三（rect 默认列数），本步不改形态
        FORMS.put("x5_webview_single", DEFAULT_FORM);
    }

    /**
     * col_type 到本 App 列表样式的全表映射。
     * <ul>
     *   <li>{@code movie_3/movie_2/movie_4/5/6}：海报网格 rect 0.75，列数按数字走。</li>
     *   <li>{@code movie_3_marquee}：海报网格 rect 0.75（横向滚动降级为竖向网格：
     *       {@code CustomScroller} 只认纵向滚动，真横向会反复触发翻页，见任务文档）。</li>
     *   <li>{@code movie_1/movie_1_left_pic/movie_1_vertical_pic}：单列行 list。</li>
     *   <li>{@code text_1~5/text_center_1/long_text/rich_text}：单列文字行 list。</li>
     *   <li>{@code pic_1/pic_1_full}：横图 rect 1.33，1 列；{@code pic_2/pic_3}：
     *       方图 rect 1.0；{@code pic_*_card/card_pic_*}：海报比例 rect 0.75。</li>
     *   <li>{@code icon_*}：无 round = 方图 rect 1.0，有 round = 圆图 oval 1.0，
     *       列数按数字走；{@code avatar}：圆图 oval，4 列。</li>
     *   <li>控制类（{@code flex_button/input/scroll_button/icon_1_search/
     *       big_big_blank_block}）：单列行 list（降级显示，不崩）。</li>
     *   <li>其余（含空、未知）：默认 rect 卡片（fallback，见 {@link #columnsFor}）。</li>
     * </ul>
     */
    public static Style styleFor(String colType) {
        return formFor(colType).style;
    }

    /**
     * col_type 到网格列数的映射（与 {@link #styleFor} 共用同一张表）。
     * 返回 0 = 走 App 默认列数（{@code Product.getColumn}；空/未知/list 形态都返回 0），
     * 其余返回 1–6 的字面列数（规则列数优先于用户“字号/列数”设置）。
     */
    public static int columnsFor(String colType) {
        return formFor(colType).columns;
    }

    /** 查表：精确登记优先，未登记的编号变体走族通用解析，最后 fallback 默认形态。 */
    private static Form formFor(String colType) {
        if (TextUtils.isEmpty(colType)) return DEFAULT_FORM;
        String t = colType.trim().toLowerCase(Locale.ROOT);
        Form exact = FORMS.get(t);
        if (exact != null) return exact;
        return parseFamily(t);
    }

    /**
     * 族通用解析（合集里没出现、将来可能出现的编号变体，如 movie_4/5/6、icon_6、pic_4）：
     * 按“族前缀 + 名字里首个数字”定形态；解析不到数字或族不认得 → 默认形态。
     */
    private static Form parseFamily(String t) {
        int n = firstInt(t);
        if (t.startsWith("movie_")) {
            if (n == 1) return LIST_FORM;
            if (n >= 2) return new Form(Style.rect(), Math.min(n, 6));
            return DEFAULT_FORM;
        }
        if (t.startsWith("text_") || t.startsWith("long_text") || t.startsWith("rich_text")) {
            return LIST_FORM;
        }
        if (t.startsWith("pic_")) {
            if (n < 1) return DEFAULT_FORM;
            if (t.contains("_card")) return new Form(Style.rect(), Math.min(n, 6));
            if (n == 1) return new Form(new Style("rect", 1.33f), 1);
            return new Form(new Style("rect", 1.0f), Math.min(n, 6));
        }
        if (t.startsWith("card_pic_")) {
            if (n < 1) return DEFAULT_FORM;
            return new Form(Style.rect(), Math.min(n, 6));
        }
        if (t.startsWith("icon_")) {
            if (n < 1) return DEFAULT_FORM;
            if (t.contains("round")) return new Form(new Style("oval", 1.0f), Math.min(n, 6));
            return new Form(new Style("rect", 1.0f), Math.min(n, 6));
        }
        if ("avatar".equals(t)) return new Form(new Style("oval", 1.0f), 4);
        return DEFAULT_FORM;
    }

    /** 取名字里的首个数字（如 movie_3_marquee→3、card_pic_2_2_left→2）；没有返回 -1。 */
    private static int firstInt(String t) {
        int n = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                if (n < 0) n = 0;
                n = n * 10 + (c - '0');
            } else if (n >= 0) {
                break;
            }
        }
        return n;
    }
}
