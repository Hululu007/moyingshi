package com.github.moyingshi.osc.tv.api;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Keep;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.bean.Want;
import com.github.moyingshi.osc.tv.setting.DiceSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 喜好选片的取数 + 随机 + 过滤引擎。
 *
 * <p>四个池：豆瓣（公开 web 接口，无 key，随时可能变/限流，绝不当唯一池）、
 * 当前站源（走现有蜘蛛取数）、收藏（Keep 表）、想看（Want 表）。
 * 所有联网都在后台线程做，结果回主线程回调。
 *
 * <p>约定（与 DiceSetting 的 14 个键对齐）：
 * dice_duration ""=不限、"long"=长片、"short"=短片；
 * dice_mode 保留开关量，本引擎不使用；
 * dice_bg 0=跟随主题，其余为 DiceFragment 色板下标。
 * Vod 没有时长字段，所以时长档不过滤（无字段的片不因此被筛掉）。
 */
public class DiceEngine {

    public static final String POOL_DOUBAN = "douban";
    public static final String POOL_SITE = "site";
    public static final String POOL_KEEP = "keep";
    public static final String POOL_WANT = "want";

    public static final int ROLL_COUNT = 3;

    private static final String DOUBAN_URL = "https://movie.douban.com/j/search_subjects?type=movie&tag=%s&sort=rank&page_limit=20&page_start=%d";
    private static final String DOUBAN_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    public interface Callback {
        /** 成功摇出；fallback=true 表示豆瓣失败后自动降级到了收藏池。 */
        void onSuccess(List<Vod> items, boolean fallback);

        void onError(String message);
    }

    public static void roll(Callback callback) {
        new Thread(() -> {
            try {
                RollResult result = rollSync();
                App.post(() -> callback.onSuccess(result.items, result.fallback));
            } catch (RollException e) {
                String message = e.getMessage();
                App.post(() -> callback.onError(message));
            } catch (Exception e) {
                App.post(() -> callback.onError(App.get().getString(R.string.dice_no_result)));
            }
        }).start();
    }

    private static class RollResult {
        final List<Vod> items;
        final boolean fallback;

        RollResult(List<Vod> items, boolean fallback) {
            this.items = items;
            this.fallback = fallback;
        }
    }

    private static class RollException extends Exception {
        RollException(String message) {
            super(message);
        }
    }

    private static RollResult rollSync() throws Exception {
        String pool = DiceSetting.getPool();
        List<Vod> candidates;
        boolean fallback = false;
        if (POOL_DOUBAN.equals(pool)) {
            try {
                candidates = fetchDouban();
            } catch (Exception e) {
                // 豆瓣失败：提示离线池可用，并自动降级到收藏池再试一次。
                candidates = fetchKeep();
                candidates = filter(candidates);
                if (!candidates.isEmpty()) return new RollResult(pick(candidates), true);
                throw new RollException(App.get().getString(R.string.dice_douban_failed));
            }
        } else if (POOL_SITE.equals(pool)) {
            candidates = fetchSite();
        } else if (POOL_KEEP.equals(pool)) {
            candidates = fetchKeep();
        } else if (POOL_WANT.equals(pool)) {
            candidates = fetchWant();
        } else {
            candidates = fetchDouban();
        }
        List<Vod> filtered = filter(candidates);
        if (filtered.isEmpty()) {
            if (DiceSetting.isNoRepeat() && !candidates.isEmpty() && allHistoryNames(candidates)) {
                throw new RollException(App.get().getString(R.string.dice_exhausted));
            }
            throw new RollException(App.get().getString(R.string.dice_no_result));
        }
        return new RollResult(pick(filtered), fallback);
    }

    /** 候选是否全是已摇过的片（不重复抽取把池子抽完时才报“已摇完”）。 */
    private static boolean allHistoryNames(List<Vod> candidates) {
        Set<String> history = historyNames();
        if (history.isEmpty()) return false;
        for (Vod item : candidates) {
            if (!history.contains(item.getName())) return false;
        }
        return true;
    }

    private static List<Vod> pick(List<Vod> items) {
        List<Vod> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, new Random());
        return new ArrayList<>(shuffled.subList(0, Math.min(ROLL_COUNT, shuffled.size())));
    }

    /** 过滤：避雷片名、题材屏蔽、类型/心情关键词、不重复抽取。时长档无字段可筛，不过滤。 */
    private static List<Vod> filter(List<Vod> items) {
        List<String> blockedNames = DiceSetting.isBlockFilm() ? DiceSetting.getBlockedNames() : Collections.emptyList();
        List<String> blockedTags = DiceSetting.isBlockGenre() ? DiceSetting.getBlockedTags() : Collections.emptyList();
        List<String> types = DiceSetting.getTypes();
        List<String> moods = DiceSetting.getMoods();
        Set<String> historyNames = DiceSetting.isNoRepeat() ? historyNames() : Collections.emptySet();
        List<Vod> result = new ArrayList<>();
        for (Vod item : items) {
            String name = item.getName();
            if (TextUtils.isEmpty(name)) continue;
            if (blockedNames.contains(name)) continue;
            if (historyNames.contains(name)) continue;
            String haystack = name + " " + item.getTypeName() + " " + item.getRemarks();
            boolean blockedTag = false;
            for (String tag : blockedTags) {
                if (!TextUtils.isEmpty(tag) && haystack.contains(tag)) {
                    blockedTag = true;
                    break;
                }
            }
            if (blockedTag) continue;
            if (!types.isEmpty() || !moods.isEmpty()) {
                boolean matched = false;
                for (String keyword : types) {
                    if (!TextUtils.isEmpty(keyword) && haystack.contains(keyword)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    for (String keyword : moods) {
                        if (!TextUtils.isEmpty(keyword) && haystack.contains(keyword)) {
                            matched = true;
                            break;
                        }
                    }
                }
                // 本地池用片名/类型包含匹配；豆瓣池已按 tag 拉取，关键词不中时也不硬筛——
                // 只有当用户明确选了类型/心情且本地池完全不匹配时才过滤，豆瓣池不过滤。
                if (!matched && !POOL_DOUBAN.equals(DiceSetting.getPool())) continue;
            }
            result.add(item);
        }
        return result;
    }

    /** 摇片历史里记过的片名集合（DiceSetting history 存 Vod 数组 JSON）。 */
    private static Set<String> historyNames() {
        Set<String> names = new HashSet<>();
        for (Vod item : Vod.arrayFrom(DiceSetting.getHistory())) names.add(item.getName());
        return names;
    }

    private static List<Vod> fetchDouban() throws Exception {
        String tag = pickTag();
        int pageStart = new Random().nextInt(4) * 20;
        String url = String.format(DOUBAN_URL, tag, pageStart);
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DOUBAN_UA);
        headers.put("Referer", "https://movie.douban.com/");
        String json = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(json)) throw new IllegalStateException("empty douban response");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray subjects = root.has("subjects") ? root.getAsJsonArray("subjects") : new JsonArray();
        List<Vod> items = new ArrayList<>();
        for (JsonElement element : subjects) {
            if (!element.isJsonObject()) continue;
            JsonObject subject = element.getAsJsonObject();
            String title = optString(subject, "title");
            if (TextUtils.isEmpty(title)) continue;
            Vod vod = new Vod();
            vod.setId(POOL_DOUBAN + ":" + optString(subject, "id"));
            vod.setName(title);
            vod.setPic(optString(subject, "cover"));
            vod.setTypeName(tag);
            vod.setRemarks(optString(subject, "rate"));
            items.add(vod);
        }
        if (items.isEmpty()) throw new IllegalStateException("no douban subjects");
        return items;
    }

    /** tag 取心情/类型首选，无则用“热门”。 */
    private static String pickTag() {
        List<String> moods = DiceSetting.getMoods();
        if (!moods.isEmpty()) return moods.get(0);
        List<String> types = DiceSetting.getTypes();
        if (!types.isEmpty()) return types.get(0);
        return "热门";
    }

    private static String optString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static List<Vod> fetchSite() throws Exception {
        Site home = VodConfig.get().getHome();
        if (home == null || TextUtils.isEmpty(home.getKey())) {
            throw new RollException(App.get().getString(R.string.dice_no_site));
        }
        Result result = SiteApi.homeContent(home);
        List<Vod> items = result == null ? Collections.emptyList() : result.getList();
        if (items == null) return Collections.emptyList();
        for (Vod item : items) {
            if (item.getSite() == null) item.setSite(home);
        }
        return new ArrayList<>(items);
    }

    private static List<Vod> fetchKeep() {
        List<Vod> items = new ArrayList<>();
        for (Keep keep : Keep.getVod()) {
            Vod vod = new Vod();
            vod.setId(keep.getVodId());
            vod.setName(keep.getVodName());
            vod.setPic(keep.getVodPic());
            Site site = VodConfig.get().getSite(keep.getSiteKey());
            if (site != null) vod.setSite(site);
            items.add(vod);
        }
        return items;
    }

    private static List<Vod> fetchWant() {
        List<Vod> items = new ArrayList<>();
        for (Want want : Want.findAll()) {
            Vod vod = new Vod();
            vod.setId(want.getVodId());
            vod.setName(want.getVodName());
            vod.setPic(want.getVodPic());
            Site site = VodConfig.get().getSite(want.getSiteKey());
            if (site != null) vod.setSite(site);
            items.add(vod);
        }
        return items;
    }
}
