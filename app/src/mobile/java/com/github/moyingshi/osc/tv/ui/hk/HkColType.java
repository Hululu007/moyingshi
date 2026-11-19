package com.github.moyingshi.osc.tv.ui.hk;

import com.github.moyingshi.osc.tv.bean.Style;
/** 移植自星辰 app/src/mobile/java/com/fongmi/android/tv/ui/hk/HkColType.java（124 行）：除包名外与原文一致；详见 docs/miniapp-xingchen-5b-search-episodes.md。
 * 5e 增补：{@link #styleFor}/{@link #columnsFor} 是本项目自有方法（非星辰原文），按本 enum 的官表派生列表样式与列数，
 * 替代已删 {@code HkListHelper} 的旧表；“除包名外与原文一致”自此只适用于 enum 本体 57 码与已有方法。 */

/**
 * 照抄 8.83 com.example.hikerview.constants.ArticleColTypeEnum（smali 逐常量转写）。
 * 字段序与原版构造器一致：itemType / code / leftRight / spanCount / landSpanCount。
 * spanCount 为 60 栅格：60=全宽，30=2 列，20=3 列，15=4 列，12=5 列。
 */
public enum HkColType {
    TEXT_1(5, "text_1", 0, 60, 60),
    TEXT_2(6, "text_2", 10, 30, 15),
    TEXT_3(11, "text_3", 10, 20, 20),
    TEXT_4(12, "text_4", 10, 15, 15),
    TEXT_5(44, "text_5", 5, 12, 12),
    TEXT_CENTER_1(18, "text_center_1", 0, 60, 60),
    MOVIE_1(4, "movie_1", 0, 60, 60),
    MOVIE_2(9, "movie_2", 12, 30, 15),
    MOVIE_3(1, "movie_3", 12, 20, 10),
    MOVIE_1_LEFT_PIC(20, "movie_1_left_pic", 0, 60, 60),
    MOVIE_1_VERTICAL_PIC(21, "movie_1_vertical_pic", 0, 60, 60),
    MOVIE_1_VERTICAL_PIC_BLUR(45, "movie_1_vertical_pic_blur", 0, 60, 60),
    MOVIE_3_MARQUEE(27, "movie_3_marquee", 12, 20, 10),
    PIC_1(7, "pic_1", 0, 60, 60),
    PIC_2(8, "pic_2", 12, 30, 15),
    PIC_3(10, "pic_3", 3, 20, 20),
    PIC_1_FULL(19, "pic_1_full", 0, 60, 60),
    PIC_1_CENTER(51, "pic_1_center", 0, 60, 60),
    PIC_3_SQUARE(30, "pic_3_square", 0, 20, 20),
    PIC_1_CARD(32, "pic_1_card", 0, 60, 60),
    PIC_2_CARD(47, "pic_2_card", 10, 30, 15),
    ICON_1_SEARCH(33, "icon_1_search", 0, 60, 60),
    ICON_2(17, "icon_2", 10, 30, 15),
    ICON_2_ROUND(35, "icon_2_round", 10, 30, 15),
    ICON_3_ROUND_FILL(53, "icon_3_round_fill", 10, 20, 20),
    ICON_3_FILL(55, "icon_3_fill", 10, 20, 20),
    ICON_3_SMALL(25, "icon_small_3", 10, 20, 20),
    ICON_4(13, "icon_4", 10, 15, 10),
    ICON_4_CARD(36, "icon_4_card", 10, 15, 10),
    ICON_4_SMALL(14, "icon_small_4", 10, 15, 10),
    ICON_4_ROUND(15, "icon_round_4", 10, 15, 10),
    ICON_4_ROUND_SMALL(16, "icon_round_small_4", 10, 15, 10),
    ICON_5(49, "icon_5", 10, 12, 12),
    ICON_5_NO_CROP(58, "icon_5_no_crop", 10, 12, 12),
    LONG_TEXT(22, "long_text", 0, 60, 60),
    RICH_TEXT(24, "rich_text", 0, 60, 60),
    AVATAR(28, "avatar", 0, 60, 60),
    TEXT_ICON(50, "text_icon", 0, 60, 60),
    X5_WEB_VIEW(34, "x5_webview_single", 0, 60, 60),
    VIDEO(56, "video", 0, 60, 60),
    HEADER(2, "header", 0, 60, 60),
    FOOTER(3, "footer", 0, 60, 60),
    LINE(23, "line", 0, 60, 60),
    LINE_BLANK(26, "line_blank", 0, 60, 60),
    BLANK_BLOCK(29, "blank_block", 0, 60, 60),
    BIG_BLANK_BLOCK(31, "big_blank_block", 0, 60, 60),
    FLEX_BUTTON(37, "flex_button", 2, 60, 60),
    SCROLL_BUTTON(38, "scroll_button", 2, 60, 60),
    CARD_PIC_2(39, "card_pic_2", 15, 30, 15),
    CARD_PIC_3(48, "card_pic_3", 15, 20, 10),
    CARD_PIC_3_CENTER(52, "card_pic_3_center", 15, 20, 10),
    CARD_PIC_1(42, "card_pic_1", 15, 60, 60),
    CARD_PIC_2_2(40, "card_pic_2_2", 15, 30, 15),
    CARD_PIC_2_2_LEFT(41, "card_pic_2_2_left", 15, 30, 15),
    input(43, "input", 12, 60, 60),
    BIG_BIG_BLANK_BLOCK(46, "big_big_blank_block", 0, 60, 60),
    ICON_1_LEFT_PIC(57, "icon_1_left_pic", 0, 60, 60);

    public final int itemType;
    public final String code;
    public final int leftRight;
    public final int spanCount;
    public final int landSpanCount;

    HkColType(int itemType, String code, int leftRight, int spanCount, int landSpanCount) {
        this.itemType = itemType;
        this.code = code;
        this.leftRight = leftRight;
        this.spanCount = spanCount;
        this.landSpanCount = landSpanCount;
    }

    /** 照抄原版 getItemTypeByCode：按 code 查不到时回退 MOVIE_3（ArticleColTypeEnum.smali:1628-1660）。 */
    public static HkColType byCode(String code) {
        if (code != null) {
            for (HkColType t : VALUES) {
                if (t.code.equals(code)) return t;
            }
        }
        return MOVIE_3;
    }

    /** 照抄原版 getItemTypeByCode 的 int 形态。 */
    public static int itemTypeByCode(String code) {
        return byCode(code).itemType;
    }

    /** 照抄原版 getSpanCountByItemType（ArticleColTypeEnum.smali:1791-1843）：按 itemType 查 spanCount；-3 取 ICON_3_SMALL 的；其余查不到给 60。 */
    public static int spanCountByItemType(int itemType) {
        for (HkColType t : VALUES) {
            if (t.itemType == itemType) return t.spanCount;
        }
        if (itemType == -3) return ICON_3_SMALL.spanCount;
        return 60;
    }

    /** 照抄原版 getLandSpanCountByItemType（与 getSpanCountByItemType 同构，取 landSpanCount）。 */
    public static int landSpanCountByItemType(int itemType) {
        for (HkColType t : VALUES) {
            if (t.itemType == itemType) return t.landSpanCount;
        }
        if (itemType == -3) return ICON_3_SMALL.landSpanCount;
        return 60;
    }

    /** 照抄原版 getLeftRightByItemType：-3 取 ICON_3_SMALL 的；按 itemType 查 leftRight；查不到给 0。 */
    public static int leftRightByItemType(int itemType) {
        if (itemType == -3) return ICON_3_SMALL.leftRight;
        for (HkColType t : VALUES) {
            if (t.itemType == itemType) return t.leftRight;
        }
        return 0;
    }

    /**
     * col_type 到本 App 列表样式的映射（5e 自有，替代 {@code HkListHelper.styleFor} 的旧表；
     * 派生规则与行为差见 docs/miniapp-xingchen-5e-click-dispatch-cleanup.md §4）。
     * 按 code 家族派生（查不到回退 {@code MOVIE_3}，即星辰官行为）：
     * 文字/结构行 → {@code Style.list()}；含 {@code round} 或 {@code avatar} → oval；
     * {@code pic_1} 族 → 横图 rect 1.33；其余 {@code pic_*}/{@code icon_*} → 方图 rect 1.0；
     * 其余（{@code movie_2/3}、{@code card_pic_*}、{@code video}、未知/空）→ 海报 rect 0.75。
     */
    public static Style styleFor(String colType) {
        String code = byCode(colType).code;
        if (code.startsWith("text_") || "long_text".equals(code) || "rich_text".equals(code)
                || "input".equals(code) || "flex_button".equals(code) || "scroll_button".equals(code)
                || "icon_1_search".equals(code) || "icon_1_left_pic".equals(code) || "text_icon".equals(code)
                || "blank_block".equals(code) || "big_blank_block".equals(code) || "big_big_blank_block".equals(code)
                || "line".equals(code) || "line_blank".equals(code) || "header".equals(code) || "footer".equals(code)
                || code.startsWith("movie_1")) {
            return Style.list();
        }
        if (code.contains("round") || "avatar".equals(code)) {
            return new Style("oval", 1.0f);
        }
        if ("pic_1".equals(code) || "pic_1_full".equals(code) || "pic_1_center".equals(code) || "pic_1_card".equals(code)) {
            return new Style("rect", 1.33f);
        }
        if (code.startsWith("pic_") || code.startsWith("icon_")) {
            return new Style("rect", 1.0f);
        }
        return Style.rect();
    }

    /**
     * col_type 到网格列数的映射（5e 自有，与 {@link #styleFor} 共用同一张官表）。
     * list 形态回 0（走 {@code Product.getColumn} 的 App 默认列数，与旧 {@code LIST_FORM} 一致）；
     * 其余回 {@code 60 / spanCount}（60 栅格：60=1 列，30=2 列，20=3 列，15=4 列，12=5 列；
     * 横竖屏不分，旧表也不分，{@code setStyle} 无横竖分支）。
     */
    public static int columnsFor(String colType) {
        HkColType t = byCode(colType);
        if (styleFor(colType).isList()) return 0;
        return 60 / t.spanCount;
    }

    private static final HkColType[] VALUES = values();
}
