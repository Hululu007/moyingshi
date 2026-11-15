package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Vod;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表页的卡片筛选与转换，对应魔影小程序 HkSiteAdapter 里的 pickCards / toVods
 *（见逆向报告 §7.1，全文可读，直译）+ col_type 到本 App 列表样式的窄化映射。
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
            if (url.isEmpty() || title.isEmpty()) continue;
            Vod vod = new Vod();
            vod.setId(url);
            vod.setName(title);
            vod.setPic(card.getPic());
            vod.setRemarks(card.getDesc());
            vod.setContent(card.getDesc());
            vod.setTypeName(typeName == null ? "" : typeName);
            vods.add(vod);
        }
        return vods;
    }

    /**
     * col_type 到本 App 列表样式的窄化映射（完整版 48 种是第 7 步长尾）。
     * 海报类走 rect 网格，文字类走 list 行，其余落到默认 rect 卡片：
     * <ul>
     *   <li>{@code movie_3} / {@code movie_2}：海报网格（验收三条规则全是 movie_3）。</li>
     *   <li>{@code movie_1}：单列行（“1 列”的窄化对应：每行一张小图 + 标题/描述）。</li>
     *   <li>{@code text_1} / {@code text_2} / {@code text_3}：文字行。</li>
     *   <li>其余（含空、未知、`movie_4/5/6`、`pic_*`、`icon_*`、`x5_webview_single`、
     *       `movie_3_marquee`、`rich_text` 等）：默认 rect 卡片，不崩。</li>
     * </ul>
     */
    public static Style styleFor(String colType) {
        if (TextUtils.isEmpty(colType)) return Style.rect();
        String t = colType.trim().toLowerCase();
        if ("movie_1".equals(t) || "text_1".equals(t) || "text_2".equals(t) || "text_3".equals(t)) {
            return Style.list();
        }
        return Style.rect();
    }
}
