package com.github.aiyingshi.osc.tv.setting;

import com.github.catvod.utils.Prefers;
import com.github.aiyingshi.osc.tv.App;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 喜好选片的状态：全放 SharedPreferences，键名前缀 dice_（仿 Rove rove_dice_* 思路自定）。
 * 想看清单不在这里，在 Want 表（Room）。
 * 列表用 JSON 字符串存；presets / history 是对象数组，也以 JSON 字符串存，由 phase 3 按需解析。
 */
public class DiceSetting {

    private static final Type STRING_LIST_TYPE = TypeToken.getParameterized(List.class, String.class).getType();

    public static String getPool() {
        return Prefers.getString("dice_pool", "douban");
    }

    public static void putPool(String pool) {
        Prefers.put("dice_pool", pool == null ? "douban" : pool);
    }

    /** 类别三档：all 全部（默认）/ movie 电影 / tv 电视剧。 */
    public static final String CATEGORY_ALL = "all";
    public static final String CATEGORY_MOVIE = "movie";
    public static final String CATEGORY_TV = "tv";

    public static String getCategory() {
        String category = Prefers.getString("dice_category", CATEGORY_ALL);
        return CATEGORY_MOVIE.equals(category) || CATEGORY_TV.equals(category) ? category : CATEGORY_ALL;
    }

    public static void putCategory(String category) {
        Prefers.put("dice_category", CATEGORY_MOVIE.equals(category) || CATEGORY_TV.equals(category) ? category : CATEGORY_ALL);
    }

    public static List<String> getMoods() {
        return getStringList("dice_moods");
    }

    public static void putMoods(List<String> moods) {
        putStringList("dice_moods", moods);
    }

    public static List<String> getCustomMoods() {
        return getStringList("dice_custom_moods");
    }

    public static void putCustomMoods(List<String> moods) {
        putStringList("dice_custom_moods", moods);
    }

    public static List<String> getTypes() {
        return getStringList("dice_types");
    }

    public static void putTypes(List<String> types) {
        putStringList("dice_types", types);
    }

    /** 时长筛选："" 不限，另由 phase 3 约定 "long" / "short"。 */
    public static String getDuration() {
        return Prefers.getString("dice_duration", "");
    }

    public static void putDuration(String duration) {
        Prefers.put("dice_duration", duration == null ? "" : duration);
    }

    /** 模式开关量，默认关；具体含义由 phase 3 定。 */
    public static boolean isMode() {
        return Prefers.getBoolean("dice_mode", false);
    }

    public static void putMode(boolean mode) {
        Prefers.put("dice_mode", mode);
    }

    public static boolean isNoRepeat() {
        return Prefers.getBoolean("dice_no_repeat", true);
    }

    public static void putNoRepeat(boolean noRepeat) {
        Prefers.put("dice_no_repeat", noRepeat);
    }

    public static boolean isBlockFilm() {
        return Prefers.getBoolean("dice_block_film", true);
    }

    public static void putBlockFilm(boolean blockFilm) {
        Prefers.put("dice_block_film", blockFilm);
    }

    public static boolean isBlockGenre() {
        return Prefers.getBoolean("dice_block_genre", false);
    }

    public static void putBlockGenre(boolean blockGenre) {
        Prefers.put("dice_block_genre", blockGenre);
    }

    public static List<String> getBlockedNames() {
        return getStringList("dice_blocked_names");
    }

    public static void putBlockedNames(List<String> names) {
        putStringList("dice_blocked_names", names);
    }

    public static List<String> getBlockedTags() {
        return getStringList("dice_blocked_tags");
    }

    public static void putBlockedTags(List<String> tags) {
        putStringList("dice_blocked_tags", tags);
    }

    /** 我的预设：对象数组 JSON，默认空数组。 */
    public static String getPresets() {
        return Prefers.getString("dice_presets", "[]");
    }

    public static void putPresets(String presetsJson) {
        Prefers.put("dice_presets", presetsJson == null ? "[]" : presetsJson);
    }

    /** 摇片历史：对象数组 JSON，默认空数组。 */
    public static String getHistory() {
        return Prefers.getString("dice_history", "[]");
    }

    public static void putHistory(String historyJson) {
        Prefers.put("dice_history", historyJson == null ? "[]" : historyJson);
    }

    /** 喜好页背景：0 表示跟随主题，其余值由 phase 3 约定色板下标。 */
    public static int getBg() {
        return Prefers.getInt("dice_bg", 0);
    }

    public static void putBg(int bg) {
        Prefers.put("dice_bg", bg);
    }

    private static List<String> getStringList(String key) {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(key, "[]"), STRING_LIST_TYPE);
            return items == null ? Collections.emptyList() : items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void putStringList(String key, List<String> items) {
        List<String> values = items == null ? new ArrayList<>() : items;
        Prefers.put(key, App.gson().toJson(values, STRING_LIST_TYPE));
    }
}
