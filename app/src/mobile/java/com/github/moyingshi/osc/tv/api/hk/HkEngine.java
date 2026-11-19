package com.github.moyingshi.osc.tv.api.hk;
/** 移植自星辰 com/fongmi/android/tv/api/hk/HkEngine.java（251 行）：门面 home()/search() 按原文改写（适配见 §6.1），旧手写版 API 以适配段保留（见 §6.2）。详见 docs/miniapp-xingchen-3e-facade.md。 */

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.orhanobut.logger.Logger;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 海阔规则引擎（星辰 HkEngine 门面 + 旧 HkEngine API 适配段）。
 *
 * <p>门面部分：把 {@link HkRule}、{@link HkJsRuntime} 串起来，home/search 时按规则形态
 * 自动分流——{@code js:} 走 JS 运行时，否则走选择器（阶段 4 已恢复 HkSelector 分支）。
 *
 * <p>适配段部分：旧手写版 HkEngine（2016 行）的 prepare/evalPreRule/evalFindRule/
 * evalTextRule 仍被 HkSubpage.expand 与 HkRouter.computeLastChapter 调用（门面不跑
 * preRule，子页面展开与最新章节求值都在门面之外）；旧 API 以 {@code @Deprecated}
 * 适配方法保留，用新运行时实现。HkExecutor 已随阶段 5 删除，适配段同步瘦身（见下）。
 * 旧实现（BOOTSTRAP/config/initConfig/rc/storage0/batchFetch/require、HkContextFactory、
 * CxHandle、vars/myVars/items 内务）已退役，由 HkJsRuntime 接管。
 */
public class HkEngine {

    private static final String TAG = "HkEngine";
    // resolveUa shim 保留旧串（见 §6.2）：不得委托给 HkUaEnum——HkUaEnum.MOBILE 是
    // Android 15/Chrome 128 串，与旧串不同，改了会悄悄换全部 hk 请求 UA。
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    // ================= 门面部分（星辰 HkEngine 改写） =================

    private HkRule rule;
    private HkJsRuntime jsRuntime;
    /** 选择器（阶段 4 恢复）：home/search 的非 js 分支走它；无参适配构造在 prepare() 时赋值。 */
    private HkSelector selector;
    /**
     * 本引擎（＝一个宿主页面）的列表求值顶替身份：顶替只许在本面的新发之间
     * 发生，跨面提交不再把本面首拉顶成空（星辰原文，照搬）。
     */
    private final Object listEvalScope = new Object();
    /**
     * true = 包裹常驻运行时（只借用）：不重复 init、destroy() 是空操作，
     * 生命周期归 HkEngineManager 管。见修法 A（b12 分类点不进去定案）。
     * 5a 按星辰原文恢复（3e 删掉的部分）。
     */
    private final boolean adopted;

    public HkEngine(HkRule rule) throws Exception {
        this.rule = rule;
        this.adopted = false;
        try {
            this.jsRuntime = new HkJsRuntime(rule);
            this.jsRuntime.init();
        } catch (Exception e) {
            // 引擎建不起来时以前只有 logcat 有痕迹，调试日志里查不到；接进文件日志（星辰原文）。
            SpiderDebug.log("hk", "engine init failed rule=%s err=%s", rule.getTitle(), String.valueOf(e));
            throw e;
        }
        // 图片 @js= 求值依赖当前规则的 JS 运行时；HkPageActivity.getRouter() 也会设置，
        // 这里兜底保证引擎创建时即登记（8.83 JSEngine.getInstance() 单例语义，星辰原文）。
        HkImage.setCurrentRuntime(this.jsRuntime);
        HkJsRuntime.setActiveInstance(this.jsRuntime);
        HkImage.setCurrentTitle(rule.getTitle());
        this.selector = jsRuntime.newSelector();
    }

    /**
     * 修法 A：包裹常驻运行时（HkEngineManager.runtimeFor），不新建不 init，
     * destroy() 空操作——与 8.83 JSEngine 单例语义一致：写读同一张变量表，
     * 点击求值的 putMyVar 才能被页面刷新求值读到。
     * 调用方须先 HkEngineManager.ensureInit(rt)（只跑一次，不清变量表）。
     * 5a 按星辰原文恢复（3e 删掉的部分）。
     */
    private HkEngine(HkRule rule, HkJsRuntime resident) {
        this.rule = rule;
        this.adopted = true;
        this.jsRuntime = resident;
        // 图片 @js= 求值依赖当前规则的 JS 运行时；与原构造同款兜底登记
        HkImage.setCurrentRuntime(this.jsRuntime);
        HkJsRuntime.setActiveInstance(this.jsRuntime);
        HkImage.setCurrentTitle(rule.getTitle());
        this.selector = resident.newSelector();
    }

    /** 修法 A 工厂：HkRouter 取常驻运行时后用此构造包裹。5a 按星辰原文恢复。 */
    public static HkEngine wrapResident(HkRule rule, HkJsRuntime resident) {
        if (rule == null || resident == null) throw new IllegalArgumentException("rule/resident null");
        return new HkEngine(rule, resident);
    }

    /**
     * 首页/分类列表（星辰原文改写）。
     * 适配：① validate() 我方返回 String（null=通过），失败时抛 IllegalStateException
     * （星辰版是 void，失败时自抛）；② getMarked 去 SourceGate 标记，
     * 直接 HkHttp.get（代理选择器链已在 HkHttpClient 里生效）。
     */
    public List<HkItem> home(int page, String cls, String area, String year, String sort) {
        try {
            String verr = rule.validate();
            if (verr != null) throw new IllegalStateException(verr);
            String findRule = rule.getFindRule();
            boolean js = isJsRule(findRule);
            // js: 规则的 MY_URL 必须是原始 url（保留 hiker://empty# 前缀），
            // 规则 JS 里常写 MY_URL.replace("hiker://empty##", host) 做替换（星辰原文）。
            String url = js
                    ? HkHttp.expandUrl(rule.getUrl(), cls, area, year, sort, page, false)
                    : HkHttp.expandUrl(rule.getUrl(), cls, area, year, sort, page);
            if (js) {
                // P1：注入 MY_TYPE/MY_CLASS_URL/MY_CLASS_NAME 等官方变量
                // MY_PARAMS 照 8.83 统一取规则的 params 字段（子页参数由 PageParser 写进该字段）
                jsRuntime.setListContext("home", safe(cls), classNameOf(cls), rule.getParams(), safe(area), safe(year), safe(sort));
                // R1: 8.83 所有 js: 规则 Java 层先抓页（JSEngine.smali:20401），正文进 resCodeMap，
                // getResCode()/getCode() 可取。星辰之前完全不预抓，依赖 getResCode() 的规则必空。
                String fetchUrl = stripEmptyPrefix(url);
                String preFetched = "";
                if (fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://")) {
                    try {
                        // 8.83 对齐：预抓失败走失败路径——真因进引擎错误通道供空态展示，
                        // 绝不在空正文上照常求值（自创的"吞失败"行为，已删）
                        preFetched = prefetchBody(fetchUrl);
                    } catch (Throwable t) {
                        String msg = String.valueOf(t);
                        SpiderDebug.log("hk", "home prefetch failed rule=%s err=%s", rule.getTitle(), msg);
                        jsRuntime.noteFetchFailure(msg);
                        return new ArrayList<>();
                    }
                }
                jsRuntime.setEvalResCodeBody(preFetched);
                try {
                    return jsRuntime.parseList(findRule, url, page, listEvalScope);
                } finally {
                    jsRuntime.setEvalResCodeBody(null);
                }
            }
            String html = getMarked(url);
            return selector.parseList(html, findRule, url);
        } catch (HkJsRuntime.ListEvalSupersededException cancelled) {
            // 被本面更新的加载顶替（取消语义）：不记 home failed——继任发自会
            // 交付；本 API 无 throws，收口形态与本方法既有失败收口同为回空表，
            // 但语义是调用方丢弃、不是运行时伪造结果（星辰原文）。
            SpiderDebug.log("hk", "home superseded rule=%s", rule.getTitle());
            return new ArrayList<>();
        } catch (Throwable e) {
            Logger.t(TAG).d("home failed: %s", e.getMessage());
            // 死因隐身器拆掉：home 的异常以前只进 logcat，文件日志里必须留原文（星辰原文）。
            SpiderDebug.log("hk", "home failed rule=%s err=%s", rule.getTitle(), String.valueOf(e));
            return new ArrayList<>();
        }
    }

    /**
     * 搜索（单页内置搜索用）。8.83 同款：返回独立 SearchResult（星辰原文改写）。
     * 适配与 home() 同款（validate String 版、去 SourceGate）。
     */
    public List<SearchResult> search(String keyword, int page) {
        try {
            String verr = rule.validate();
            if (verr != null) throw new IllegalStateException(verr);
            if (!rule.hasSearch()) return new ArrayList<>();
            String url = HkHttp.expandSearchUrl(rule.getSearchUrl(), keyword, page);
            String searchFind = rule.getSearchFind();
            if (TextUtils.isEmpty(searchFind)) return new ArrayList<>();
            if (isJsRule(searchFind)) {
                // js: 搜索同理：MY_URL 传原始 url，保留 hiker://empty# 前缀。
                String rawUrl = HkHttp.expandSearchUrl(rule.getSearchUrl(), keyword, page, false);
                jsRuntime.setListContext("search", "", "", "", "", "", "");
                // R1: 8.83 js: 搜索也先抓页，正文进 resCodeMap
                String fetchUrl = stripEmptyPrefix(rawUrl);
                String preFetched = "";
                if (fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://")) {
                    try {
                        // 8.83 对齐：预抓失败走失败路径，绝不在空正文上照常求值（自创行为，已删）
                        preFetched = prefetchBody(fetchUrl);
                    } catch (Throwable t) {
                        String msg = String.valueOf(t);
                        SpiderDebug.log("hk", "search prefetch failed rule=%s err=%s", rule.getTitle(), msg);
                        jsRuntime.noteFetchFailure(msg);
                        return new ArrayList<>();
                    }
                }
                jsRuntime.setEvalResCodeBody(preFetched);
                try {
                    return jsRuntime.parseSearch(searchFind, rawUrl, keyword, page);
                } finally {
                    jsRuntime.setEvalResCodeBody(null);
                }
            }
            String html = getMarked(url);
            return selector.parseSearch(html, searchFind, url);
        } catch (Throwable e) {
            Logger.t(TAG).d("search failed: %s", e.getMessage());
            SpiderDebug.log("hk", "search failed rule=%s err=%s", rule.getTitle(), String.valueOf(e));
            return new ArrayList<>();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 按 class_url 反查 class_name（MY_CLASS_NAME 注入用，星辰原文）。 */
    private String classNameOf(String cls) {
        if (cls == null || cls.isEmpty()) return "";
        for (String[] p : rule.getClassPairs()) {
            if (cls.equals(p[1])) return p[0];
        }
        return "";
    }

    public String getError() {
        return jsRuntime == null ? "" : jsRuntime.getError();
    }

    /**
     * 3e 适配：星辰原文经 SourceGate 标记取数（SourceGate.mark("hk", …) 前后恢复）；
     * 代理选择器链已在 HkHttpClient 里生效，本门面直接取数，不再标记。
     */
    private String getMarked(String url) throws IOException {
        return HkHttp.get(url, rule.resolvedUa());
    }

    /**
     * R1 预抓取数（8.83 JSEngine.parseHomeCallBack 同款语义，星辰原文）：成功返回正文；
     * 失败或正文为空直接抛异常——调用方按现有失败路径处理，绝不在空正文上求值。
     */
    private String prefetchBody(String fetchUrl) throws IOException {
        String body = getMarked(fetchUrl);
        if (body == null || body.isEmpty()) {
            // 加固：预抓路径下正文为空直接按取数失败处理，避免次生 TypeError 盖住真因
            throw new IOException("取数失败：正文为空");
        }
        return body;
    }

    /** R1: 剥离 hiker://empty# 前缀取真实 URL，用于预抓取（星辰原文）。 */
    private static String stripEmptyPrefix(String url) {
        if (url == null) return "";
        String p = "hiker://empty#";
        if (url.startsWith(p)) return url.substring(p.length());
        // 兼容双 # 写法 hiker://empty##
        if (url.startsWith("hiker://empty##")) return url.substring("hiker://empty##".length());
        return url;
    }

    /**
     * 星辰 HkSelector.isJsRule（HkSelector.java:42）同款小谓词：
     * {@code rule != null && rule.trim().startsWith("js:")}。阶段 4 起直接委托。
     */
    private static boolean isJsRule(String rule) {
        return HkSelector.isJsRule(rule);
    }

    public HkRule getRule() {
        return rule;
    }

    public HkJsRuntime getJsRuntime() {
        return jsRuntime;
    }

    /** 选择器（阶段 4 恢复，星辰原文）；无参适配构造在 prepare() 之前调时为 null。 */
    public HkSelector getSelector() {
        return selector;
    }

    /**
     * 5a 恢复：星辰原文分 adopted（常驻运行时，destroy 空操作）与独立引擎两路。
     * 3e 称“HkEngineManager 在星辰仓库里根本没有这个类”并删掉常驻路径，该判断是错的：
     * HkEngineManager 在星辰 app/src/mobile/.../ui/hk/ 内（155 行，5a 已搬入），
     * HkRouter 构造器依赖 wrapResident。见 docs/miniapp-xingchen-5a-router-detail-play.md §3.4。
     */
    public void destroy() {
        // 修法 A：包裹常驻运行时时绝不能销毁它（原版单例语义：常驻引擎生命周期
        // 归 HkEngineManager 管，进程内永不销毁）；旧独立引擎才走原销毁。
        // 5a 按星辰原文恢复（3e 删掉的部分）；无参适配构造的 jsRuntime 可能为 null，守卫保留。
        if (!adopted && jsRuntime != null) jsRuntime.destroy();
    }

    // ================= 旧 HkEngine API 适配段（@Deprecated，3e-2） =================
    // 旧手写版 HkEngine（2016 行）的调用点未重接线到门面 home()/search()：门面不跑 preRule、
    // URL 拼法与旧 buildHomeRequest/buildDetailRequest 不等价（homeDetailed 的 classUrl ID 式/
    // fyAll/POST 守卫/诊断、详情/播放链的 preRule + initialVars 都在门面之外）。
    // 本段用新运行时实现旧 API；5e 起调用点只剩 HkSubpage.expand（子页面展开）与
    // HkRouter.computeLastChapter（最新章节求值）——HkExecutor 已随阶段 5 删除。
    // 适配段瘦身（5e）：wasResultSet/getResultCards/getExports/jsValueFromCards/setImportParam/
    // getVarsSnapshot/setRefreshEventEnabled/wasRefreshRequested/setDialogHost/getDialogHost/
    // DialogHost/evalExpression/eval 已删除（唯一消费方是已死的 HkExecutor、HkSubpage.requireOne
    // 与 HkActionHandler）；setImportParams 改为直通运行时。逐项映射见文档 §6.2。
    // 语义变化（旧“每引擎清 vars”→新“进程级 varMap 共享”、旧“prepared scope 直跑”
    // →新“每发鲜 scope”、旧“live JS 数组”→新“JSON 字符串”）已在文档写明。

    /**
     * 把规则 ua 简写映射成真浏览器 UA（海阔规则的 ua 是 "mobile"/"auto"/"pc"
     * 这类简写；原样发出会被站点当爬虫/非浏览器挡掉，见 docs/miniapp-nodata-fix.md）。
     * 已是 Mozilla/ 开头的真 UA 或其它自定义值原样通过。（旧版原样保留。）
     */
    public static String resolveUa(String ua) {
        if (TextUtils.isEmpty(ua)) return MOBILE_UA;
        String v = ua.trim();
        if ("mobile".equalsIgnoreCase(v) || "auto".equalsIgnoreCase(v)) return MOBILE_UA;
        if ("pc".equalsIgnoreCase(v)) return PC_UA;
        return v;
    }

    /** （旧版原样保留。） */
    public static class HkException extends Exception {
        public HkException(String message) { super(message); }
        public HkException(String message, Throwable cause) { super(message, cause); }
    }

    // ---- 适配段实例状态（门面字段 rule/jsRuntime 复用，prepare 时赋值） ----
    private String pageUrl = "";
    private int page = 1;
    private String pageType = "home";
    private String resCode = "";

    /** 旧调用点专用无参构造（HkSubpage/HkRouter.computeLastChapter 均 new HkEngine() 后调 prepare；5e 前另有 HkExecutor）。 */
    @Deprecated
    public HkEngine() {
        // 5a：adopted 是 final 字段，无参适配构造须显式置 false（独立引擎口径）。
        this.adopted = false;
    }

    @Deprecated
    public void prepare(HkRule rule, String pageUrl, int page, String pageType, String resCode) throws HkException {
        prepare(rule, pageUrl, page, pageType, resCode, null);
    }

    /**
     * prepare 的重载（旧版原样签名）：initialVars 非空时预填页面变量
     * （点击执行 @lazyRule= 后重跑详情页时，把执行里 putVar 的值带过去，
     * 臻品fix 的 tab/排序切换靠它；null 时与原来一致）。
     * 3e-2 实现：新建 HkJsRuntime(rule) + init() + setEvalResCodeBody(resCode)；
     * initialVars 经 HkJsRuntime.putVarValue 写进程级 varMap（与 JS putVar(String, Object)
     * 同款进表；旧“每引擎清 vars”语义随之变星辰“进程级一表”语义）。
     * setListContext 注入 MY_TYPE/MY_PARAMS（门面 home() 同款）；HkImage 当前运行时
     * 登记与门面构造器同款。
     */
    @Deprecated
    public void prepare(HkRule rule, String pageUrl, int page, String pageType, String resCode,
                        Map<String, Object> initialVars) throws HkException {
        this.rule = rule;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.page = page;
        this.pageType = TextUtils.isEmpty(pageType) ? "home" : pageType;
        this.resCode = resCode == null ? "" : resCode;
        try {
            this.jsRuntime = new HkJsRuntime(rule);
            this.jsRuntime.init();
        } catch (Exception e) {
            SpiderDebug.log("hk", "engine init failed rule=%s err=%s", rule == null ? "" : rule.getTitle(), String.valueOf(e));
            throw new HkException("引擎初始化失败: " + e.getMessage(), e);
        }
        this.selector = jsRuntime.newSelector();
        HkImage.setCurrentRuntime(this.jsRuntime);
        HkJsRuntime.setActiveInstance(this.jsRuntime);
        if (rule != null) HkImage.setCurrentTitle(rule.getTitle());
        this.jsRuntime.setEvalResCodeBody(this.resCode);
        this.jsRuntime.setListContext(this.pageType, "", "", rule == null ? "" : rule.getParams(), "", "", "");
        if (initialVars != null) {
            for (Map.Entry<String, Object> e : initialVars.entrySet()) {
                if (e.getKey() != null) this.jsRuntime.putVarValue(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 旧 evalPreRule：空 preRule 直接回；否则跑 preRule，失败抛异常。
     * 3e-2 实现：HkJsRuntime.parsePreRule(rule, true)（throwOnError=true 时原样 throw）。
     */
    @Deprecated
    public void evalPreRule() throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        if (rule == null || TextUtils.isEmpty(rule.getPreRule())) return;
        try {
            jsRuntime.parsePreRule(rule, true);
        } catch (Exception e) {
            throw new HkException("preRule 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行整段 js: find_rule，返回卡片列表。
     * 3e-2 实现：HkJsRuntime.parseList(findRule, pageUrl, page)（3 参版，自成顶替作用域；
     * parseListInternal 内已调 stripJsPrefix，传原始 find_rule 即可），再 HkItem→HkCard
     * 逐字段转换。5e：parseListInternal 已恢复直接 return 数组的回落（3e 缺口⑤），
     * wasResultSet 随适配段瘦身删除（唯一消费方是已死的 HkExecutor 命中诊断）。
     */
    @Deprecated
    public List<HkCard> evalFindRule(String findRule) throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        try {
            List<HkItem> items = jsRuntime.parseList(findRule, pageUrl, page);
            return toCards(items);
        } catch (HkJsRuntime.ListEvalSupersededException e) {
            throw new HkException("列表求值被顶替", e);
        } catch (Exception e) {
            throw new HkException("find_rule 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行一段返回字符串的 JS 规则（last_chapter_rule 用）。
     * 3e-2 实现：HkJsRuntime.evalJS(jsBody, resCode, false)；优先级：setStrResult/
     * setLastChapterResult 写下的 strResult 变化 → 新交付条目首标题 → JS 直接返回值；
     * evalJS 出错回 "error:..." 字符串，shim 遇此外转 HkException。
     * 5e：纯字符串 setResult("第…集") 的缺口已恢复（HkJsRuntime.noteStringResult，
     * 见 3e 缺口①），strAfter 正常读到；parseLastChapterRule 的 JSEngineCallback
     * 是 HkJsRuntime 私有接口，外部无法驱动（不变）。last_chapter_rule 在野外绝大多数
     * 是直接返回表达式（如 js:pdfh(…)），最坏只是装饰文案缺失
     * （HkRouter.computeLastChapter 全 catch 回 ""），不炸详情页。
     */
    @Deprecated
    public String evalTextRule(String rule) throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        String js = HkParserKit.jsBody(rule);
        int before = jsRuntime.actionResultCount();
        String strBefore = jsRuntime.getStrResult();
        String ret;
        try {
            ret = jsRuntime.evalJS(js, resCode, false);
        } catch (Throwable t) {
            throw new HkException("text_rule 执行失败: " + t.getMessage(), t);
        }
        if (ret != null && ret.startsWith("error:")) {
            throw new HkException("text_rule 执行失败: " + ret);
        }
        String strAfter = jsRuntime.getStrResult();
        if (strAfter != null && !strAfter.isEmpty() && !strAfter.equals(strBefore)) return strAfter;
        List<HkItem> snap = jsRuntime.snapshotActionResults();
        if (snap != null && snap.size() > before) {
            HkItem first = snap.get(before);
            if (first != null && first.getTitle() != null && !first.getTitle().isEmpty()) return first.getTitle();
        }
        if (ret != null && !ret.isEmpty() && !"undefined".equals(ret)) return ret;
        return "";
    }

    /**
     * 把 $.importParam 设为给定的 query 参数对象（子页面展开时用）。
     * 5e（3e 缺口③恢复）：直通 HkJsRuntime.setImportParams——运行时在 assembleFreshScope
     * 里把参数挂到 $ 对象上（见 HkJsRuntime.importParams 字段注）。prepare 之后调
     * （HkSubpage.expand 的调用序），运行时已存在；prepare 之前调则无运行时可转，静默丢弃。
     */
    @Deprecated
    public void setImportParams(Map<String, String> params) {
        if (jsRuntime != null) jsRuntime.setImportParams(params);
    }

    /** HkItem → HkCard 逐字段转换（两模型字段 1:1；HkItem.getType 无对应字段，丢弃）。 */
    private static List<HkCard> toCards(List<HkItem> items) {
        List<HkCard> out = new ArrayList<>();
        if (items == null) return out;
        for (HkItem item : items) {
            if (item == null) continue;
            HkCard card = new HkCard();
            card.setTitle(item.getTitle());
            card.setUrl(item.getUrl());
            card.setPic(item.getPic());
            card.setDesc(item.getDesc());
            card.setSelected(item.isSel());
            card.setColType(item.getColType());
            card.setExtra(item.getExtra());
            card.setContent(item.getContent());
            card.setLine(item.getLine());
            card.setFindRule(item.getFindRule());
            card.setGroup(item.getGroup());
            card.setPages(item.getPages());
            card.setPreRule(item.getPreRule());
            card.setRule(item.getRule());
            out.add(card);
        }
        return out;
    }
}
