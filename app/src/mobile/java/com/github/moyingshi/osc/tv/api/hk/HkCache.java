package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.github.moyingshi.osc.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 海阔小程序列表页的内存卡片缓存，对应魔影小程序 HkSiteAdapter 的 CACHE
 *（见逆向报告 §7.1：{@code Collections.synchronizedMap(new jo(128, 0.75f, true, 4))}
 * + {@code CACHE_MAX = 600}，jo 是按访问排序的 LinkedHashMap 子类）。
 * <p>
 * 键形态是窄化适配（见 docs/miniapp-warmup.md §3）：上游 CACHE 是单卡片
 * {@code url → 卡片}（供详情页按 URL 取卡片），本地详情页不按 URL 查卡片
 *（第 4 步已定：卡片字段走 intent 传过去），单卡片缓存没有消费者；列表页
 * “打开秒开”要的是一整页卡片，所以这里按页缓存：键 = 规则标题 + 分类 + 页码，
 * 值 = 一页 Vod 列表 + 取数时规则文件的最后修改时间。容量数字 128/600 与
 * LRU 语义与上游逐字一致。
 * <p>
 * 失效：规则文件变了（mtime 比对，含文件被删，lastModified 回 -1）、删除时
 * {@link #invalidate(String)} 立即清、LRU 满 600 条淘汰、进程退出即消失。
 * 不设 TTL——站点内容的新旧由用户下拉刷新（强制重取）决定。
 * <p>
 * 缓存里只存卡片（标题/海报/描述/链接/类型名），不存规则原文、不存请求头、
 * 不存 cookie、不存任何凭证；Vod 上没有这些字段，结构上就放不进去。
 * 磁盘的 {@code plugins/hk/data/<标题>/}（规则持久变量与已抓内容，第 3 步已建）
 * 与这层内存缓存分工不同：那是规则运行时的状态，本类绝不读写它。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。get/put 都是纯内存操作，主线程可调；
 * put/get 都做防御性拷贝，调用方拿到的列表改了也不会污染缓存。
 */
public final class HkCache {

    private static final String TAG = "HkCache";

    /** 初始容量 128（与报告的 jo(128, 0.75f, true, 4) 一致）。 */
    private static final int INITIAL_CAPACITY = 128;
    /** 上限 600 条（与报告的 CACHE_MAX 一致），超了按访问顺序淘汰最久未用。 */
    private static final int CACHE_MAX = 600;

    /** 一页缓存：Vod 列表 + 取数时规则文件的最后修改时间 + 写入时间（只记不判）。 */
    private static final class Entry {
        final List<Vod> vods;
        final long ruleModified;
        final long cachedAt;

        Entry(List<Vod> vods, long ruleModified) {
            this.vods = new ArrayList<>(vods);
            this.ruleModified = ruleModified;
            this.cachedAt = System.currentTimeMillis();
        }
    }

    /** 按访问排序的 LRU map：满 600 条时淘汰最久未用的一条；外层包同步。 */
    private static final Map<String, Entry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Entry>(INITIAL_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HkCache.Entry> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    private HkCache() {
    }

    /** 缓存键：标题 + 分类（空归一化成 ""）+ 页码。 */
    static String key(String title, String classId, int page) {
        String t = title == null ? "" : title;
        String c = classId == null ? "" : classId;
        return t + "\n" + c + "\n" + page;
    }

    /**
     * 取一页：无条目回 null；规则文件 mtime 与存入时不等（重导入/覆盖/手动替换/
     * 被删，删了时 lastModified 回 -1）→ 删条目、回 null。
     * 返回的是防御性拷贝，调用方改了不污染缓存。
     */
    public static List<Vod> get(String title, String classId, int page) {
        if (TextUtils.isEmpty(title)) return null;
        Entry entry = CACHE.get(key(title, classId, page));
        if (entry == null) return null;
        long current = HkStore.lastModified(title);
        if (current != entry.ruleModified) {
            CACHE.remove(key(title, classId, page));
            Log.i(TAG, "cache stale title=" + title + " page=" + page);
            return null;
        }
        Log.i(TAG, "cache hit title=" + title + " class=" + classId + " page=" + page + " items=" + entry.vods.size());
        return new ArrayList<>(entry.vods);
    }

    /**
     * 存一页：空列表不存（0 条可能是站点临时失败，存了会把空态粘住；
     * 下次打开重取才是正确行为）。非空才存，防御性拷贝后进 LRU。
     */
    public static void put(String title, String classId, int page, List<Vod> vods, long ruleModified) {
        if (TextUtils.isEmpty(title) || vods == null || vods.isEmpty()) return;
        CACHE.put(key(title, classId, page), new Entry(vods, ruleModified));
        Log.i(TAG, "cache put title=" + title + " class=" + classId + " page=" + page + " items=" + vods.size()
                + " size=" + CACHE.size());
    }

    /** 按标题清掉该规则的全部缓存页（删除/重导入覆盖时调；不等下次 get 才失效）。 */
    public static void invalidate(String title) {
        if (TextUtils.isEmpty(title)) return;
        String prefix = title + "\n";
        synchronized (CACHE) {
            Iterator<String> it = CACHE.keySet().iterator();
            while (it.hasNext()) {
                if (it.next().startsWith(prefix)) it.remove();
            }
        }
        Log.i(TAG, "cache invalidate title=" + title);
    }

    /** 清空全部缓存（目前没有调用方，留给以后“清缓存”入口；不要顺手调）。 */
    public static void clear() {
        CACHE.clear();
    }
}
