package com.github.moyingshi.osc.tv.api.hk;

import android.util.Log;

import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.ui.hk.HkEngineManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 海阔小程序后台预热，对应魔影小程序 HkSiteAdapter 的 warmUp()
 *（见逆向报告 §7.1：空表直接返回，否则起一个名叫 {@code hk-warmup} 的后台
 * 线程跑 lambda$warmUp$0——前 5 条串行跑首页 {@code f22.p(标题).k(1, "")}，
 * 0 条且有分类时再跑一次首分类；每条独立 try/catch，只记日志，不抛、不弹）。
 * <p>
 * 预热 = 后台先跑列表页同一条取数链路（{@link HkRouter#home(int, String, String, String, String)}），
 * 结果经 {@link HkCard#pickCards}/{@link HkCard#toVods} 成 Vod 后写进 {@link HkCache}；
 * 列表页打开时命中缓存直接渲染。预热失败（规则坏了、站点挂了、断网了）只记一条日志，
 * 不弹提示、不阻断下一条、不阻塞前台。
 * <p>
 * 并发：单线程串行（与上游一致，不一次把 5 条全并行打满）；同一时间最多一个
 * 预热线程在跑（{@code running} 门），导入与启动几乎同时触发时后到的直接丢弃。
 * 超时：单条规则的网络由 OkHttp 共享客户端的 30s connect/read/write 兜底
 *（{@code HkRuleFetch.fetch} 用 {@code HkHttpClient.client()}）；另设总时限 120s，
 * 跑下一条前若已超时就停。Rhino 单次执行本身没有安全的硬中断手段，总时限只
 * 约束“不再起新规则”，不谎称能中断正在跑的那条；最坏情况是最后一条由 OkHttp
 * 30s 兜底结束，后台线程自然退出，不阻塞前台、不阻塞退出。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。两个入口都只起线程、不做 I/O，
 * 主线程可调。
 */
public final class HkWarmup {

    private static final String TAG = "hk-warmup";

    /** 预热前 5 条规则（与报告的 min(size, 5) 一致）。 */
    private static final int WARMUP_COUNT = 5;
    /** 文件导入链路的门槛：规则总数 > 20 时不预热（与报告 importFiles 的判断一致）。 */
    private static final int IMPORT_WARMUP_MAX_RULES = 20;
    /** 预热总时限 120s：跑下一条前若已超时就停（见类注释，不中断正在跑的那条）。 */
    private static final long WARMUP_DEADLINE_MS = 120_000L;

    /** 进程内启动预热只跑一次。 */
    private static final AtomicBoolean startupDone = new AtomicBoolean(false);
    /** 同一时间最多一个预热线程在跑。 */
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private HkWarmup() {
    }

    /**
     * 导入完成后调：后台线程里读已装规则表，空表直接返回；总数 > 20 时跳过
     *（报告 importFiles 链路的门槛）；否则预热前 5 条。
     * 三路导入（文件/目录、粘贴 JSON、云口令）都汇到 HkMiniappActivity 的
     * 导入回调里调这里；同步（runSync）只是重登记条目，不调。
     */
    public static void warmUpAfterImport() {
        new Thread(() -> {
            List<HkRule> rules = HkStore.list();
            if (rules == null || rules.isEmpty()) return;
            if (rules.size() > IMPORT_WARMUP_MAX_RULES) {
                Log.i(TAG, "skip warmup after import: too many rules (" + rules.size() + " > " + IMPORT_WARMUP_MAX_RULES + ")");
                return;
            }
            warmUp(rules);
        }, TAG).start();
    }

    /**
     * App 启动后调（mobile HomeActivity.onCreate）：进程内只跑一次；有已装
     * 规则时后台预热前 5 条，没装规则时不跑。启动链路不设 ≤20 门槛——启动时
     * 规则数多是常态，门槛会让重度用户永远冷启动；预热的仍是前 5 条。
     * 与 warmUpAfterImport 几乎同时触发时，后到的因 running 门直接丢弃。
     */
    public static void warmUpOnStartup() {
        if (!startupDone.compareAndSet(false, true)) return;
        new Thread(() -> {
            List<HkRule> rules = HkStore.list();
            if (rules == null || rules.isEmpty()) {
                Log.i(TAG, "skip warmup on startup: no rules installed");
                return;
            }
            warmUp(rules);
        }, TAG).start();
    }

    /**
     * 共用实现：单线程顺序跑前 5 条。每条跑首页第 1 页首分类
     *（{@code home(rule, 1, 首分类名)}；上游 lambda 里“0 条再跑一次首分类”
     * 在本地会发出相同的 URL，不再重复跑），成功且非空时写缓存；0 条只记日志，
     * 不进缓存；任何异常只记日志，继续下一条。跑下一条前检查总时限，超了就停。
     */
    static void warmUp(List<HkRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "warmup already running, skipped");
            return;
        }
        try {
            long startedAt = System.currentTimeMillis();
            int count = Math.min(rules.size(), WARMUP_COUNT);
            for (int i = 0; i < count; i++) {
                if (System.currentTimeMillis() - startedAt > WARMUP_DEADLINE_MS) {
                    Log.i(TAG, "warmup deadline reached, stopped at " + i + "/" + count);
                    break;
                }
                HkRule rule = rules.get(i);
                if (rule == null) continue;
                String title = rule.getTitle();
                // 分类取第一个分类名——与 HkListFragment.setClasses() 定 mClassId 的
                // 口径一致（单分类取唯一名、多分类取首名、无分类为 null），这样预热
                // 写入的缓存键与列表页打开时查的键是同一个，否则永远命中不了。
                String[] names = rule.classNames();
                String firstClass = names != null && names.length > 0 ? names[0] : null;
                try {
                    // 5e：取数改走新引擎 HkRouter.home（与列表页同一条链路）；new HkRouter 不建
                    // surface，构造器只接常驻运行时，故预热不需 destroy()（常驻引擎上是空操作）；
                    // 取数前后各加一次 surfaceOpened/surfaceClosed 配对（无界面的后台取数，
                    // 配对持有可保常驻运行时在取数期间不被清定时器；净计数为零）。
                    HkEngineManager.surfaceOpened(rule);
                    try {
                        HkRouter router = new HkRouter(rule);
                        List<HkItem> items = router.home(1, firstClass, "", "", "");
                        List<HkCard> cards = HkCard.fromItems(items);
                        List<Vod> vods = HkCard.toVods(HkCard.pickCards(cards, rule), rule.getType());
                        if (vods == null || vods.isEmpty()) {
                            Log.i(TAG, "warmup rule=" + title + " items=0");
                        } else {
                            HkCache.put(title, firstClass, 1, vods, HkStore.lastModified(title));
                            Log.i(TAG, "warmup rule=" + title + " items=" + vods.size());
                        }
                    } finally {
                        HkEngineManager.surfaceClosed(rule);
                    }
                } catch (Throwable th) {
                    Log.w(TAG, "warmup fail rule=" + title + " err=" + th.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
