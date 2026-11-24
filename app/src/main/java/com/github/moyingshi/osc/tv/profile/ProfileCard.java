package com.github.moyingshi.osc.tv.profile;

import com.github.moyingshi.osc.tv.R;

/** 我的页 10 张卡片；顺序与显隐由 {@link ProfileLayout} 存取。 */
public enum ProfileCard {
    CALENDAR(R.string.profile_card_calendar),
    OVERVIEW(R.string.profile_card_overview),
    HISTORY(R.string.profile_card_history),
    TRACKING(R.string.profile_card_tracking),
    FAVORITES(R.string.profile_card_favorites),
    WATCHING(R.string.profile_card_watching),
    MONTH(R.string.profile_card_month),
    GENRES(R.string.profile_card_genres),
    TREND1(R.string.profile_card_trend1),
    TREND2(R.string.profile_card_trend2);

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
