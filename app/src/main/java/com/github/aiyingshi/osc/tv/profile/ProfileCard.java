package com.github.aiyingshi.osc.tv.profile;

import com.github.aiyingshi.osc.tv.R;

/** 我的页 10 张卡片；顺序与显隐由 {@link ProfileLayout} 存取。默认顺序按 Arcane 管理面板：日历、历史观看、追剧、观看概览、收藏、本月观看、在看、分类走势 1/2、类型分布。 */
public enum ProfileCard {
    CALENDAR(R.string.profile_card_calendar),
    HISTORY(R.string.profile_card_history),
    TRACKING(R.string.profile_card_tracking),
    OVERVIEW(R.string.profile_card_overview),
    FAVORITES(R.string.profile_card_favorites),
    MONTH(R.string.profile_card_month),
    WATCHING(R.string.profile_card_watching),
    TREND1(R.string.profile_card_trend1),
    TREND2(R.string.profile_card_trend2),
    GENRES(R.string.profile_card_genres);

    public final int titleRes;

    ProfileCard(int titleRes) {
        this.titleRes = titleRes;
    }

    public static ProfileCard fromKey(String key) {
        if (key == null) return null;
        for (ProfileCard card : values()) {
            if (card.name().equalsIgnoreCase(key.trim())) return card;
        }
        return null;
    }
}
