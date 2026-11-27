package com.github.aiyingshi.osc.tv.profile;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 我的页卡片布局：顺序 + 隐藏，两串逗号分隔的卡片 key 存在 Prefers 里。
 * 新卡片（以后加的）默认排最后且显示；存过的顺序里丢了的卡片名直接跳过。
 */
public final class ProfileLayout {

    private static final String KEY_ORDER = "profile_card_order";
    private static final String KEY_HIDDEN = "profile_card_hidden";

    private ProfileLayout() {
    }

    /** 全部卡片按保存的顺序排（含隐藏的）；没存过时按枚举顺序。 */
    public static List<ProfileCard> orderedCards() {
        List<ProfileCard> cards = new ArrayList<>();
        String saved = Prefers.getString(KEY_ORDER, "");
        if (!TextUtils.isEmpty(saved)) {
            for (String key : saved.split(",")) {
                ProfileCard card = ProfileCard.fromKey(key);
                if (card != null && !cards.contains(card)) cards.add(card);
            }
        }
        for (ProfileCard card : ProfileCard.values()) {
            if (!cards.contains(card)) cards.add(card);
        }
        return cards;
    }

    public static List<ProfileCard> visibleCards() {
        Set<ProfileCard> hidden = hiddenCards();
        List<ProfileCard> visible = new ArrayList<>();
        for (ProfileCard card : orderedCards()) {
            if (!hidden.contains(card)) visible.add(card);
        }
        return visible;
    }

    public static Set<ProfileCard> hiddenCards() {
        Set<ProfileCard> hidden = new HashSet<>();
        String saved = Prefers.getString(KEY_HIDDEN, "");
        if (TextUtils.isEmpty(saved)) return hidden;
        for (String key : saved.split(",")) {
            ProfileCard card = ProfileCard.fromKey(key);
            if (card != null) hidden.add(card);
        }
        return hidden;
    }

    public static void save(List<ProfileCard> order, Set<ProfileCard> hidden) {
        Prefers.put(KEY_ORDER, join(order));
        Prefers.put(KEY_HIDDEN, join(hidden));
    }

    private static String join(Iterable<ProfileCard> cards) {
        StringBuilder builder = new StringBuilder();
        for (ProfileCard card : cards) {
            if (builder.length() > 0) builder.append(',');
            builder.append(card.name());
        }
        return builder.toString();
    }
}
