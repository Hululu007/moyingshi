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
import java.util.Arrays;
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
 * <p>心情在本地池的生效方式（dice-fix-2）：先把心情映射到站源自有分类名，
 * 站源池直接拉对应分类的列表；收藏/想看池没有分类字段，用映射后的类型词
 * 在片名/类型/备注里包含匹配；两种都无任何一部匹配时，回落到不过滤的整池
 * 摇出（moodFallback=true，页面提示一句），绝不只剩一个 toast + 空格子。
 * 豆瓣池行为不动（心情当 tag 拉取）。
 *
 * <p>约定（与 DiceSetting 的 14 个键对齐）：
 * dice_duration ""=不限、"long"=长片、"short"=短片；
 * dice_mode 保留开关量，本引擎不使用；
 * dice_bg 0=跟随主题，其余为 DiceFragment 色板下标。
 * Vod 没有时长字段，所以时长档不过滤（无字段的片不因此被筛掉）。
 * <p>第 5 轮（dice-round-5）：类别三档 dice_category（all/movie/tv，默认 all）。
 * 豆瓣池按档拉取（type 参数化，all 档 movie+tv 各拉一页合并打乱）；站源池用片名+
 * 类型名+备注含关键词过滤（电影“电影”，电视剧“电视剧/连续剧/剧集”），筛不到回落
 * 整池并提示（categoryFallback），绝不摇空；收藏/想看池不筛类别。
 * Callback.onSuccess 新增 categoryFallback 参数（第 5 位），唯一实现 DiceFragment 已同步。
 */
public class DiceEngine {

    public static final String POOL_DOUBAN = "douban";
    public static final String POOL_SITE = "site";
    public static final String POOL_KEEP = "keep";
    public static final String POOL_WANT = "want";

    public static final int ROLL_COUNT = 3;

    private static final String DOUBAN_URL = "https://movie.douban.com/j/search_subjects?type=%s&tag=%s&sort=rank&page_limit=20&page_start=%d";
    private static final String DOUBAN_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 心情 → 站源分类/类型名。站源分类名各异，匹配时用包含比对（见 findCategory）。 */
    private static final Map<String, List<String>> MOOD_TO_TYPES = new HashMap<>();

    static {
        MOOD_TO_TYPES.put("开心", Arrays.asList("喜剧", "爱情"));
        MOOD_TO_TYPES.put("治愈", Arrays.asList("剧情", "爱情"));
        MOOD_TO_TYPES.put("热血", Arrays.asList("动作", "武侠"));
        MOOD_TO_TYPES.put("悬疑", Arrays.asList("悬疑"));
        MOOD_TO_TYPES.put("浪漫", Arrays.asList("爱情"));
        MOOD_TO_TYPES.put("搞笑", Arrays.asList("喜剧"));
        MOOD_TO_TYPES.put("烧脑", Arrays.asList("悬疑", "科幻"));
        MOOD_TO_TYPES.put("轻松", Arrays.asList("喜剧", "剧情"));
    }

    public interface Callback {
        /**
         * 成功摇出；fallback=true 表示豆瓣失败后自动降级到了收藏池；
         * moodFallback=true 表示心情/类型在本地池无任何匹配，已按整池摇出；
         * siteFallback=true 表示站源池无内容，已用豆瓣池兜底摇出（须明确告诉用户）；
         * categoryFallback=true 表示当前类别在站源池无任何匹配，已按全部类别摇出；
         * fillers 为本轮候选池（供老虎机滚动填充海报），可能为空。
         */
        void onSuccess(List<Vod> items, boolean fallback, boolean moodFallback, boolean siteFallback, boolean categoryFallback, List<Vod> fillers);

        void onError(String message);
    }

    /** 预取候选海报的回调：只在主线程回一次结果，全部失败返回空列表，不抛错。 */
    public interface PrefetchCallback {
        void onResult(List<Vod> candidates);
    }

    public static void roll(Callback callback) {
        new Thread(() -> {
            try {
                RollResult result = rollSync();
                App.post(() -> callback.onSuccess(result.items, result.fallback, result.moodFallback, result.siteFallback, result.categoryFallback, result.fillers));
            } catch (RollException e) {
                String message = e.getMessage();
                App.post(() -> callback.onError(message));
            } catch (Exception e) {
                App.post(() -> callback.onError(App.get().getString(R.string.dice_no_result)));
            }
        }).start();
    }

    public static void prefetch(PrefetchCallback callback) {
        new Thread(() -> {
            List<Vod> items = prefetchSync();
            App.post(() -> callback.onResult(items));
        }).start();
    }

    /**
     * 预取：按当前池拉候选，走与摇片同款的 filterBase + filterMoodType 过滤
     * （心情/类型无匹配时同样回落到整池，只为取海报）；
     * 站源池无内容时用豆瓣热门海报兜底；任何一步失败都返回空列表，不抛错。
     */
    private static List<Vod> prefetchSync() {
        String pool = DiceSetting.getPool();
        try {
            List<Vod> candidates;
            if (POOL_DOUBAN.equals(pool)) {
                candidates = fetchDouban();
            } else if (POOL_SITE.equals(pool)) {
                candidates = fetchSite();
                if (candidates.isEmpty()) candidates = fetchDouban("热门");
            } else if (POOL_KEEP.equals(pool)) {
                candidates = fetchKeep();
            } else if (POOL_WANT.equals(pool)) {
                candidates = fetchWant();
            } else {
                candidates = fetchDouban();
            }
            List<Vod> base = filterBase(candidates);
            if (base.isEmpty()) return Collections.emptyList();
            List<Vod> categoryFiltered = filterCategory(base);
            if (categoryFiltered.isEmpty()) categoryFiltered = base;
            List<Vod> filtered = filterMoodType(categoryFiltered);
            if (filtered.isEmpty() && hasMoodOrType()) filtered = categoryFiltered;
            return filtered;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static class RollResult {
        final List<Vod> items;
        final boolean fallback;
        final boolean moodFallback;
        final boolean siteFallback;
        final boolean categoryFallback;
        final List<Vod> fillers;

        RollResult(List<Vod> items, boolean fallback, boolean moodFallback, boolean siteFallback, boolean categoryFallback, List<Vod> fillers) {
            this.items = items;
            this.fallback = fallback;
            this.moodFallback = moodFallback;
            this.siteFallback = siteFallback;
            this.categoryFallback = categoryFallback;
            this.fillers = fillers;
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
        boolean siteFallback = false;
        if (POOL_DOUBAN.equals(pool)) {
            try {
                candidates = fetchDouban();
            } catch (Exception e) {
                // 豆瓣失败：提示离线池可用，并自动降级到收藏池再试一次。
                candidates = fetchKeep();
                List<Vod> base = filterBase(candidates);
                List<Vod> filtered = filterMoodType(base);
                boolean moodFallback = filtered.isEmpty() && !base.isEmpty() && hasMoodOrType();
                if (moodFallback) filtered = base;
                if (!filtered.isEmpty()) return new RollResult(pick(filtered), true, moodFallback, false, false, filtered);
                throw new RollException(App.get().getString(R.string.dice_douban_failed));
            }
        } else if (POOL_SITE.equals(pool)) {
            candidates = fetchSite();
            if (candidates.isEmpty()) {
                // 站源彻底没内容：用豆瓣池兜底摇出，并明确告诉用户，不许静默换池。
                try {
                    candidates = fetchDouban();
                    siteFallback = true;
                } catch (Exception ignored) {
                    throw new RollException(App.get().getString(R.string.dice_site_empty));
                }
            }
        } else if (POOL_KEEP.equals(pool)) {
            candidates = fetchKeep();
        } else if (POOL_WANT.equals(pool)) {
            candidates = fetchWant();
        } else {
            candidates = fetchDouban();
        }
        List<Vod> base = filterBase(candidates);
        if (base.isEmpty()) {
            if (DiceSetting.isNoRepeat() && !candidates.isEmpty() && allHistoryNames(candidates)) {
                throw new RollException(App.get().getString(R.string.dice_exhausted));
            }
            if (!candidates.isEmpty() && allBlocked(candidates)) {
                throw new RollException(App.get().getString(R.string.dice_all_blocked));
            }
            if (POOL_SITE.equals(pool) && candidates.isEmpty() && !siteFallback) {
                throw new RollException(App.get().getString(R.string.dice_site_empty));
            }
            throw new RollException(App.get().getString(R.string.dice_no_result));
        }
        // 类别过滤只对站源池生效；站源空走豆瓣兜底时豆瓣已按 type 拉取，不再筛。
        // 筛不到回落到不过滤的整池并提示，绝不摇空。
        List<Vod> categoryFiltered = siteFallback ? base : filterCategory(base);
        boolean categoryFallback = false;
        if (categoryFiltered.isEmpty()) {
            categoryFiltered = base;
            categoryFallback = true;
        }
        List<Vod> filtered = filterMoodType(categoryFiltered);
        boolean moodFallback = false;
        if (filtered.isEmpty() && hasMoodOrType()) {
            // 本地池里心情/类型一个都匹配不上：回落到整池摇出，不许空手而归。
            filtered = categoryFiltered;
            moodFallback = true;
        }
        if (filtered.isEmpty()) throw new RollException(App.get().getString(R.string.dice_no_result));
        return new RollResult(pick(filtered), fallback, moodFallback, siteFallback, categoryFallback, filtered);
    }

    private static boolean hasMoodOrType() {
        return !DiceSetting.getTypes().isEmpty() || !DiceSetting.getMoods().isEmpty();
    }

    /** 候选是否全被避雷片名 / 题材屏蔽滤掉（只重算这两层过滤，不含不重复抽取）。 */
    private static boolean allBlocked(List<Vod> candidates) {
        List<String> blockedNames = DiceSetting.isBlockFilm() ? DiceSetting.getBlockedNames() : Collections.emptyList();
        List<String> blockedTags = DiceSetting.isBlockGenre() ? DiceSetting.getBlockedTags() : Collections.emptyList();
        if (blockedNames.isEmpty() && blockedTags.isEmpty()) return false;
        for (Vod item : candidates) {
            String name = item.getName();
            if (TextUtils.isEmpty(name)) return false;
            if (blockedNames.contains(name)) continue;
            String haystack = name + " " + item.getTypeName() + " " + item.getRemarks();
            boolean blockedTag = false;
            for (String tag : blockedTags) {
                if (!TextUtils.isEmpty(tag) && haystack.contains(tag)) {
                    blockedTag = true;
                    break;
                }
            }
            if (!blockedTag) return false;
        }
        return true;
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

    /** 第一层过滤：避雷片名、题材屏蔽、不重复抽取。时长档无字段可筛，不过滤。 */
    private static List<Vod> filterBase(List<Vod> items) {
        List<String> blockedNames = DiceSetting.isBlockFilm() ? DiceSetting.getBlockedNames() : Collections.emptyList();
        List<String> blockedTags = DiceSetting.isBlockGenre() ? DiceSetting.getBlockedTags() : Collections.emptyList();
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
            result.add(item);
        }
        return result;
    }

    /**
     * 第二层过滤：类型/心情关键词。心情先展开成映射的类型词（开心→喜剧/爱情…），
     * 再加心情词本身；类型直接用分类名。豆瓣池已按 tag 拉取，不过滤。
     */
    private static List<Vod> filterMoodType(List<Vod> items) {
        if (POOL_DOUBAN.equals(DiceSetting.getPool())) return new ArrayList<>(items);
        List<String> keywords = expandKeywords(DiceSetting.getTypes(), DiceSetting.getMoods());
        if (keywords.isEmpty()) return new ArrayList<>(items);
        List<Vod> result = new ArrayList<>();
        for (Vod item : items) {
            String haystack = item.getName() + " " + item.getTypeName() + " " + item.getRemarks();
            for (String keyword : keywords) {
                if (!TextUtils.isEmpty(keyword) && haystack.contains(keyword)) {
                    result.add(item);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 类别过滤（只对站源池生效；收藏/想看池本身是什么类别就摇什么，不筛；
     * 豆瓣池已按 type 拉取，不再筛）。Vod 没有 typeId 字段，用片名 + 类型名 +
     * 备注含关键词判断：电影档只认“电影”，电视剧档认“电视剧/连续剧/剧集”
     *（不用单字“剧”，会误中“剧情”）。
     */
    private static List<Vod> filterCategory(List<Vod> items) {
        if (!POOL_SITE.equals(DiceSetting.getPool())) return new ArrayList<>(items);
        String category = DiceSetting.getCategory();
        List<String> keywords;
        if (DiceSetting.CATEGORY_MOVIE.equals(category)) keywords = Arrays.asList("电影");
        else if (DiceSetting.CATEGORY_TV.equals(category)) keywords = Arrays.asList("电视剧", "连续剧", "剧集");
        else return new ArrayList<>(items);
        List<Vod> result = new ArrayList<>();
        for (Vod item : items) {
            String haystack = item.getName() + " " + item.getTypeName() + " " + item.getRemarks();
            for (String keyword : keywords) {
                if (haystack.contains(keyword)) {
                    result.add(item);
                    break;
                }
            }
        }
        return result;
    }

    /** 类型原样 + 心情展开成映射类型词 + 心情词本身（自定义心情无映射时只用原词）。 */
    private static List<String> expandKeywords(List<String> types, List<String> moods) {
        List<String> keywords = new ArrayList<>();
        for (String type : types) {
            if (!TextUtils.isEmpty(type) && !keywords.contains(type)) keywords.add(type);
        }
        for (String mood : moods) {
            if (TextUtils.isEmpty(mood)) continue;
            List<String> mapped = MOOD_TO_TYPES.get(mood);
            if (mapped != null) {
                for (String word : mapped) {
                    if (!keywords.contains(word)) keywords.add(word);
                }
            }
            if (!keywords.contains(mood)) keywords.add(mood);
        }
        return keywords;
    }

    /** 摇片历史里记过的片名集合（DiceSetting history 存 Vod 数组 JSON）。 */
    private static Set<String> historyNames() {
        Set<String> names = new HashSet<>();
        for (Vod item : Vod.arrayFrom(DiceSetting.getHistory())) names.add(item.getName());
        return names;
    }

    private static List<Vod> fetchDouban() throws Exception {
        return fetchDouban(pickTag());
    }

    /**
     * 指定 tag 拉豆瓣列表（预取兜底用“热门”）。类别按 dice_category 走：
     * movie 档只拉 type=movie，tv 档只拉 type=tv，all 档 movie 和 tv 各拉一页
     * 合并后打乱；all 档两路都失败才抛错，单路成功就用单路结果。
     */
    private static List<Vod> fetchDouban(String tag) throws Exception {
        String category = DiceSetting.getCategory();
        if (DiceSetting.CATEGORY_MOVIE.equals(category)) return fetchDoubanPage("movie", tag);
        if (DiceSetting.CATEGORY_TV.equals(category)) return fetchDoubanPage("tv", tag);
        List<Vod> items = new ArrayList<>();
        Exception lastError = null;
        try {
            items.addAll(fetchDoubanPage("movie", tag));
        } catch (Exception e) {
            lastError = e;
        }
        try {
            items.addAll(fetchDoubanPage("tv", tag));
        } catch (Exception e) {
            lastError = e;
        }
        if (items.isEmpty()) throw lastError != null ? lastError : new IllegalStateException("no douban subjects");
        Collections.shuffle(items, new Random());
        return items;
    }

    /** 单路拉豆瓣一页：type=movie/tv，tag 为心情/类型首选或“热门”。 */
    private static List<Vod> fetchDoubanPage(String type, String tag) throws Exception {
        int pageStart = new Random().nextInt(4) * 20;
        String url = String.format(DOUBAN_URL, type, tag, pageStart);
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

    /**
     * 当前站源池：先拿首页取分类；选了心情/类型时，把关键词映射到站源自有分类名，
     * 直接拉对应分类第 1 页做候选（心情真正按分类生效）；没有分类能对上、
     * 或分类拉取失败/为空时，回落到首页列表。
     */
    private static List<Vod> fetchSite() throws Exception {
        Site home = VodConfig.get().getHome();
        if (home == null || TextUtils.isEmpty(home.getKey())) {
            throw new RollException(App.get().getString(R.string.dice_no_site));
        }
        Result result = SiteApi.homeContent(home);
        List<Vod> homeItems = result == null ? Collections.emptyList() : result.getList();
        List<Vod> fromCategory = fetchSiteByCategory(home, result);
        List<Vod> items = !fromCategory.isEmpty() ? fromCategory : homeItems;
        if (items == null || items.isEmpty()) {
            // 首页列表与心情映射分类都为空：再拉第一个分类第 1 页试一次。
            items = fetchFirstCategory(home, result);
        }
        if (items == null) return Collections.emptyList();
        List<Vod> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Vod item : items) {
            if (item == null || TextUtils.isEmpty(item.getName()) || !seen.add(item.getName())) continue;
            if (item.getSite() == null) item.setSite(home);
            output.add(item);
        }
        return output;
    }

    /** 拉第一个分类第 1 页做候选；无分类或拉取失败返回空列表。 */
    private static List<Vod> fetchFirstCategory(Site home, Result homeResult) {
        if (homeResult == null || homeResult.getTypes() == null || homeResult.getTypes().isEmpty()) return Collections.emptyList();
        com.github.moyingshi.osc.tv.bean.Class first = homeResult.getTypes().get(0);
        if (first == null || TextUtils.isEmpty(first.getTypeId())) return Collections.emptyList();
        try {
            Result result = SiteApi.categoryContent(home.getKey(), first.getTypeId(), "1", false, new HashMap<>());
            return result != null && result.getList() != null ? result.getList() : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /** 按心情/类型关键词找站源分类并拉第 1 页；无匹配或拉取失败返回空列表。 */
    private static List<Vod> fetchSiteByCategory(Site home, Result homeResult) {
        List<String> keywords = expandKeywords(DiceSetting.getTypes(), DiceSetting.getMoods());
        if (keywords.isEmpty() || homeResult == null || homeResult.getTypes() == null) return Collections.emptyList();
        List<com.github.moyingshi.osc.tv.bean.Class> types = homeResult.getTypes();
        List<String> tids = new ArrayList<>();
        for (String keyword : keywords) {
            com.github.moyingshi.osc.tv.bean.Class matched = findCategory(types, keyword);
            if (matched != null && !TextUtils.isEmpty(matched.getTypeId()) && !tids.contains(matched.getTypeId())) {
                tids.add(matched.getTypeId());
                if (tids.size() >= 3) break;
            }
        }
        List<Vod> items = new ArrayList<>();
        for (String tid : tids) {
            try {
                Result result = SiteApi.categoryContent(home.getKey(), tid, "1", false, new HashMap<>());
                if (result != null && result.getList() != null) items.addAll(result.getList());
            } catch (Exception ignored) {
                // 单个分类拉取失败不影响其他分类，继续。
            }
        }
        return items;
    }

    /** 分类名包含比对：站源分类名含关键词，或关键词含分类名（如“电视剧”含“剧”不算，只认整词方向）。 */
    private static com.github.moyingshi.osc.tv.bean.Class findCategory(List<com.github.moyingshi.osc.tv.bean.Class> types, String keyword) {
        for (com.github.moyingshi.osc.tv.bean.Class type : types) {
            String name = type.getTypeName();
            if (TextUtils.isEmpty(name)) continue;
            if (name.contains(keyword) || keyword.contains(name)) return type;
        }
        return null;
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
