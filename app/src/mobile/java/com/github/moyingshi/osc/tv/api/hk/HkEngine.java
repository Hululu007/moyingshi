package com.github.moyingshi.osc.tv.api.hk;
/** 移植自星辰 com/fongmi/android/tv/api/hk/HkEngine.java（251 行）：门面 home()/search() 按原文改写（适配见 §6.1），旧手写版 API 以适配段保留（见 §6.2）。详见 docs/miniapp-xingchen-3e-facade.md。 */

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.orhanobut.logger.Logger;

import org.mozilla.javascript.Undefined;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 海阔规则引擎（星辰 HkEngine 门面 + 旧 HkEngine API 适配段）。
 *
 * <p>门面部分：把 {@link HkRule}、{@link HkJsRuntime} 串起来，home/search 时按规则形态
 * 自动分流——{@code js:} 走 JS 运行时，否则走选择器（选择器分支是阶段 4 的空位）。
 *
 * <p>适配段部分：旧手写版 HkEngine（2016 行）的调用点（HkExecutor、HkParser、
 * HkSubpage）未重接线到门面 home()/search()——门面不跑 preRule、选择器分支是空位、
 * URL 拼法与 HkExecutor 的 buildHomeRequest/buildDetailRequest 不等价。
 * 旧 API 以 {@code @Deprecated} 适配方法保留，用新运行时实现，调用点零改动。
 * 旧实现（BOOTSTRAP/config/initConfig/rc/storage0/batchFetch/require、HkContextFactory、
 * CxHandle、vars/myVars/items 内务）已退役，由 HkJsRuntime 接管。
 */
public class HkEngine {

    private static final String TAG = "HkEngine";
    private static final Gson GSON = new Gson();
    // resolveUa shim 保留旧串（见 §6.2）：不得委托给 HkUaEnum——HkUaEnum.MOBILE 是
    // Android 15/Chrome 128 串，与旧串不同，改了会悄悄换全部 hk 请求 UA。
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    // ================= 门面部分（星辰 HkEngine 改写） =================

    private HkRule rule;
    private HkJsRuntime jsRuntime;
    /**
     * 本引擎（＝一个宿主页面）的列表求值顶替身份：顶替只许在本面的新发之间
     * 发生，跨面提交不再把本面首拉顶成空（星辰原文，照搬）。
     */
    private final Object listEvalScope = new Object();

    public HkEngine(HkRule rule) throws Exception {
        this.rule = rule;
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
    }

    /**
     * 首页/分类列表（星辰原文改写）。
     * 适配：① validate() 我方返回 String（null=通过），失败时抛 IllegalStateException
     * （星辰版是 void，失败时自抛）；② 非 js 选择器分支是阶段 4 空位（HkSelector 未搬，
     * 本步回空列表 + 文件日志，不自造解析）；③ getMarked 去 SourceGate 标记，
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
            // 3e 空位：选择器分支。HkSelector 是阶段 4 的类（selector 字段与
            // getSelector() 同阶段 4 恢复）；本步按原文结构留空位，不自造解析。
            SpiderDebug.log("hk", "home selector branch not yet ported rule=%s (stage 4: HkSelector)", rule.getTitle());
            return new ArrayList<>();
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
     * 适配与 home() 同款（validate String 版、选择器分支阶段 4 空位、去 SourceGate）。
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
            // 3e 空位：选择器分支（阶段 4：HkSelector）。
            SpiderDebug.log("hk", "search selector branch not yet ported rule=%s (stage 4: HkSelector)", rule.getTitle());
            return new ArrayList<>();
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
     * {@code rule != null && rule.trim().startsWith("js:")}。阶段 4 用
     * HkSelector.isJsRule 替换本方法。
     */
    private static boolean isJsRule(String rule) {
        return rule != null && rule.trim().startsWith("js:");
    }

    public HkRule getRule() {
        return rule;
    }

    public HkJsRuntime getJsRuntime() {
        return jsRuntime;
    }

    /**
     * 3e 适配：星辰原文分 adopted（常驻运行时，destroy 空操作）与独立引擎两路；
     * HkEngineManager 在星辰仓库里根本没有这个类，常驻路径整段删掉（我方
     * HkExecutor 每次执行新建引擎，不必强行常驻）。本方法恒销毁自有运行时。
     */
    public void destroy() {
        if (jsRuntime != null) jsRuntime.destroy();
    }

    // ================= 旧 HkEngine API 适配段（@Deprecated，3e-2） =================
    // 旧手写版 HkEngine（2016 行）的调用点（HkExecutor、HkParser、HkSubpage，
    // 约 85 处）未重接线到门面 home()/search()：门面不跑 preRule、选择器分支是
    // 阶段 4 空位、URL 拼法与 HkExecutor 的 buildHomeRequest/buildDetailRequest
    // 不等价（homeDetailed 的 classUrl ID 式/fyAll/POST 守卫/诊断、详情/播放链的
    // preRule + initialVars 都在门面之外）。本段用新运行时实现旧 API，调用点
    // 零改动；阶段 5/7 接管弹框/详情/播放时按需移除。逐项映射见文档 §6.2。
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

    /**
     * 弹框宿主（第 7 步之七）：规则 JS 调 {@code confirm/select/input} 时，
     * 引擎经它弹框问用户；返回 null/false = 用户取消。宿主由调用方
     * （Fragment/Activity）在后台线程求值前通过 {@link #setDialogHost} 挂上；
     * 没挂 host 时 confirm/select 仍抛明确错（第 1–6 步的原行为）。
     * 实现方（见 {@code HkDialogs}）必须在主线程弹框、后台线程 latch 等结果，
     * 取消路径（按钮/返回键/点框外/Activity 销毁）都要回取消默认值，不悬挂。
     * （旧版原样保留接口；3e-2 适配段只存取、不 consult——新运行时 confirm/select
     * 走 authGate/AuthBridgeEvent 授权链，阶段 5/6 接管弹框时移除本接口。）
     */
    public interface DialogHost {
        boolean confirm(String title, String message);

        String select(String title, List<String> options);

        String input(String title, String hint, String preset);
    }

    // ---- 适配段实例状态（门面字段 rule/jsRuntime 复用，prepare 时赋值） ----
    private String pageUrl = "";
    private int page = 1;
    private String pageType = "home";
    private String resCode = "";
    private DialogHost dialogHost;
    private boolean refreshEventEnabled = true;
    /** require 的第二个参数 / 子页面 query 参数：运行时无 Java 侧设置口，只存值。 */
    private Object importParam;
    private Map<String, String> importParams;
    /** 上次 evalFindRule/eval 交的卡片（getResultCards 用）；未交过为 null。 */
    private List<HkCard> lastResultCards;
    /** 上次求值是否调过 setResult 一族（wasResultSet 用）。 */
    private boolean resultSetFlag;

    /** 旧调用点专用无参构造（HkExecutor/HkParser/HkSubpage 均 new HkEngine() 后调 prepare）。 */
    @Deprecated
    public HkEngine() {
    }

    @Deprecated
    public void setRefreshEventEnabled(boolean enabled) {
        // 只存值：新运行时 refreshPage 无门控，恒投 OnRefreshPageEvent；
        // 当前我方 UI 无该事件订阅方（全树 grep 为零），无 live 行为变化；
        // 阶段 5 恢复门控时删除本方法。
        this.refreshEventEnabled = enabled;
    }

    @Deprecated
    public void setDialogHost(DialogHost host) {
        this.dialogHost = host;
    }

    @Deprecated
    public DialogHost getDialogHost() {
        return dialogHost;
    }

    @Deprecated
    public boolean wasRefreshRequested() {
        return jsRuntime != null && jsRuntime.isRefreshRequested();
    }

    /**
     * 旧 vars 快照：新运行时 vars 在进程级 varMap 共享，跨发无需快照携带；
     * 调用方传回的空 initialVars 不丢东西（prepare 已把 initialVars 写进 varMap）。
     * 阶段 5 删除本方法。
     */
    @Deprecated
    public Map<String, Object> getVarsSnapshot() {
        return new HashMap<>();
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
        this.lastResultCards = null;
        this.resultSetFlag = false;
        try {
            this.jsRuntime = new HkJsRuntime(rule);
            this.jsRuntime.init();
        } catch (Exception e) {
            SpiderDebug.log("hk", "engine init failed rule=%s err=%s", rule == null ? "" : rule.getTitle(), String.valueOf(e));
            throw new HkException("引擎初始化失败: " + e.getMessage(), e);
        }
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

    /** JS 是否调过 setResult/setHomeResult/setSearchResult（命中诊断用）。 */
    @Deprecated
    public boolean wasResultSet() {
        return resultSetFlag;
    }

    /**
     * 执行整段 js: find_rule，返回卡片列表。
     * 3e-2 实现：HkJsRuntime.parseList(findRule, pageUrl, page)（3 参版，自成顶替作用域；
     * parseListInternal 内已调 stripJsPrefix，传原始 find_rule 即可），再 HkItem→HkCard
     * 逐字段转换。注意：新运行时只认 setResult 一族交付（直接 return 数组不再回落转换，
     * 与旧 cardsFromJs 回落不同）；wasResultSet 以返回表非空计（setResult 空数组不计，
     * 见文档 §6.2——deliverResult 走 findSink 时不写共享 results 表，计数差值法不成立）。
     */
    @Deprecated
    public List<HkCard> evalFindRule(String findRule) throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        try {
            List<HkItem> items = jsRuntime.parseList(findRule, pageUrl, page);
            resultSetFlag = items != null && !items.isEmpty();
            lastResultCards = toCards(items);
            return new ArrayList<>(lastResultCards);
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
     * 已知缺口（见文档 §6.2）：纯字符串 setResult("第…集") 在 evalJS 的 fresh scope 里
     * 走 collectResult，buildResultItems 解析不成条目会被静默丢掉；parseLastChapterRule
     * 的 JSEngineCallback 是 HkJsRuntime 私有接口，外部无法驱动。last_chapter_rule
     * 在野外绝大多数是直接返回表达式（如 js:pdfh(…)），最坏只是装饰文案缺失
     * （HkExecutor.lastChapter 全 catch 回 ""），不炸详情页。
     */
    @Deprecated
    public String evalTextRule(String rule) throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        String js = HkParser.jsBody(rule);
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
     * 求一段 .js: 后处理表达式的值，input 是待处理的值（进 scope 当全局变量）。
     * 返回字符串；JS 返回 undefined/null 时返回 ""。
     * 3e-2 实现：setNextInputOverride(input) + evalLazy(js, pageUrl, -1)
     * （evalLazyInternal 经 evalRawInFreshScope 注入 input，与旧“置 input 全局
     * 再求值”同形；区别是鲜 scope 而非 prepared scope，vars 经 varMap 延续）。
     */
    @Deprecated
    public String evalExpression(String js, String input) throws HkException {
        if (TextUtils.isEmpty(js)) return input == null ? "" : input;
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        try {
            jsRuntime.setNextInputOverride(input == null ? "" : input);
            String ret = jsRuntime.evalLazy(js, pageUrl, -1);
            if (ret == null || "undefined".equals(ret)) return "";
            return ret;
        } catch (Exception e) {
            throw new HkException(".js: 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在当前 scope 里执行一段 JS，返回求值结果（调用方决定怎么解读）。
     * 3e-2 实现：evalJS(js, resCode, false) 回 String；出错转 HkException。
     * 区别：旧 eval 在 prepared scope 里直跑、回 live JS 值；新 evalJS 每发鲜 scope、
     * 回字符串（对象/数组已 JSON 化）。HkSubpage.requireOne 的 fallback 仍可用。
     * sourceName 入参现仅作占位（曾是 Rhino 脚本名，供堆栈用）。
     */
    @Deprecated
    public Object eval(String js, String sourceName) throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        int before = jsRuntime.actionResultCount();
        String ret;
        try {
            ret = jsRuntime.evalJS(js, resCode, false);
        } catch (Throwable t) {
            throw new HkException("JS 执行失败: " + t.getMessage(), t);
        }
        if (ret != null && ret.startsWith("error:")) {
            throw new HkException("JS 执行失败: " + ret);
        }
        List<HkItem> snap = jsRuntime.snapshotActionResults();
        if (snap != null && snap.size() > before) {
            lastResultCards = toCards(snap.subList(before, snap.size()));
            resultSetFlag = true;
        }
        return ret == null ? "" : ret;
    }

    /**
     * 读当前 scope 里 $.exports（页面规则用它交导出值；未设置返回 Undefined）。
     * 3e-2 实现：新运行时每发鲜 scope，$.exports 不跨发留存，恒回 Undefined.instance；
     * 子页面 require 的 exports 优先分支在 3e 恒走不到，阶段 5/7 恢复。
     */
    @Deprecated
    public Object getExports() {
        return Undefined.instance;
    }

    /**
     * 把 $.importParam 设为 require 的第二个参数（任意 JS 值；Undefined 时不改）。
     * 3e-2 实现：只存值（运行时无 Java 侧 importParam 设置口）。
     */
    @Deprecated
    public void setImportParam(Object value) {
        this.importParam = value;
    }

    /**
     * 把 $.importParam 设为给定的 query 参数对象（子页面展开时用）。
     * 3e-2 实现：只存值（同上）。
     */
    @Deprecated
    public void setImportParams(Map<String, String> params) {
        this.importParams = params;
    }

    /** JS 调过 setResult 后交的卡片列表（require 取子引擎结果时用）；没调过返回 null。 */
    @Deprecated
    List<HkCard> getResultCards() {
        return lastResultCards;
    }

    /**
     * 把卡片列表转回调用方 scope 里的 JS 数组（require 返回子引擎的 setResult
     * 结果时用，供调用方 .filter/.map；空列表返回 []）。
     * 3e-2 实现：新运行时无 scope 外露，无法回 live JS 数组，回 JSON 字符串
     * （字段与旧版一致：title/url/img/pic/desc/col_type/extra）；仅 HkSubpage:275
     * 一个调用方，父规则对 require 结果做 .filter/.map 会失效，见文档 §6.2。
     */
    @Deprecated
    public Object jsValueFromCards(List<HkCard> cards) throws HkException {
        if (jsRuntime == null) throw new HkException("引擎未 prepare");
        List<Map<String, Object>> plain = new ArrayList<>();
        if (cards != null) {
            for (HkCard card : cards) {
                Map<String, Object> m = new HashMap<>();
                m.put("title", card.getTitle());
                m.put("url", card.getUrl());
                m.put("img", card.getPic());
                m.put("pic", card.getPic());
                m.put("desc", card.getDesc());
                m.put("col_type", card.getColType());
                if (!TextUtils.isEmpty(card.getExtra())) {
                    try {
                        m.put("extra", JsonParser.parseString(card.getExtra()));
                    } catch (Exception ignored) {
                        m.put("extra", card.getExtra());
                    }
                }
                plain.add(m);
            }
        }
        return GSON.toJson(plain);
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
