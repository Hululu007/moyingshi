package com.github.moyingshi.osc.tv.api.hk;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.App;

import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * 海阔规则取数专用 OkHttpClient（移植计划阶段 1；对照星辰 {@code HkHttp.client()}）。
 *
 * <p>星辰的取数 client（{@code HkHttp.java:875-940}）与我们的差别只在三处：
 * (1) 它是 hk 专用的 client（CookieJar / charset 头 / 10s 超时 / ContentType 拦截器 / DoH），
 * 我们此前用共享 client（30s 超时、无 CookieJar、无 charset 头）；
 * (2) 它多了按源定向的 SourceGate 层（只在配了代理且多源时才起作用）；
 * (3) 它的 GlobalProxy 开关是应用级“全局代理”。
 *
 * <p>3b 接线（本文件）：第 (1) 项除 DoH 的 DNS 对象本身外已全部落地——
 * <ul>
 *   <li>派生自共享 {@code OkHttp.client()}：trust-all SSL、OkDns、请求/响应/鉴权拦截器、
 *       30s 超时策略全部继承，不改行为；超时随即显式改为星辰口径 10s；
 *   <li>加 charset=UTF-8 公共头拦截器（星辰同款，8.83 OkGo 公共头；缺失时才补）；
 *   <li>加 {@code HkContentTypePreInterceptor}（应用拦截器）与
 *       {@code HkContentTypeAfterInterceptor}（网络拦截器）：前者备份 Cookie/Content-Type
 *       头、给 gitee 补 Referer，后者按 {@code #noCookie#} 哨兵恢复/删除 Cookie 头
 *       （fetchCookie 的 {@code fetchByHiker} 全链依赖这对拦截器）；
 *   <li>加 {@code HkCookieJar}（SharedPreferences 持久化 + 内存表，星辰同款）：
 *       装不上直接抛、client 不缓存、下次调用可重试（星辰“不吞”口径：无 Cookie
 *       还照常跑等于全站请求终身无 Cookie 且无任何报错）；
 *   <li>显式 {@code .proxySelector(OkHttp.selector())}：我们仓库没有星辰用的
 *       {@code com.github.catvod.net.GlobalProxy} / {@code SourceGate} 类（全仓库 grep
 *       零命中），等价物是 catvod 的 {@code OkProxySelector}——规则列表为空时 fallback
 *       系统选择器即直连，与星辰“无代理配置时直通默认选择器”等价；用户在 ProxySetting /
 *       ShellProxyDialog / BaseConfig 里配的代理规则由此生效；
 *   <li>Brotli 拦截器按星辰做法反射加载，依赖缺失则跳过（我们没有
 *       {@code okhttp-brotli} 依赖，当前一定跳过；后续加依赖即自动生效）；
 *   <li>显式 {@code followRedirects(true) / followSslRedirects(true)}（星辰同款；
 *       与共享 client 默认值一致，仅作口径锁定）。
 * </ul>
 *
 * <p>DoH（第 (1) 项的最后一块）：取数 client 应走 {@code HkHttp.HK_AUTO_DNS}
 * （命中 registerDNS 注册表的域名走注册的 DoH / 静态 IP，其余系统解析 + 阿里 DoH
 * 兜底；按 lookup 时读 live AUTO_DOMAIN 表，一次接入后登记即时生效，无需反复重建）。
 * 本文件只提供重配入口 {@link #setDns(Dns)} / {@link #reset()}（仅 hk 用）：
 * 构建时 {@code dnsOverride} 非空即应用。真正把 {@code HK_AUTO_DNS} 传进来的一行
 * 调用要写在 {@code HkHttp.applyDoh} 内（{@code HkHttp.java} 不在本步 scope 内，
 * 留待 3e 门面接线或下一次 HkHttp 改动时接上，见任务文档）。在此之前取数 client
 * 仍用系统 DNS（与 3b 前一致），DoH 仅作用于图片 client（现状不变）。
 *
 * <p>注意：{@code HkHttp.imageClient()} 经 {@code client().newBuilder()} 派生，
 * 会继承本 client 的 CookieJar 与 ContentType 拦截器（超时与 DNS 由它自己覆盖：
 * 15s、HK_IMAGE_DNS、callTimeout 45s）。ContentType 对图片请求基本透明
 * （无 Temp 头时直通；仅 gitee 图床请求会多一个 Referer，与规则取数同口径），
 * 若真机发现图片行为异常，优先在此处排查。
 */
public final class HkHttpClient {

    private static volatile OkHttpClient client;

    /** DoH/自动 DNS 覆盖（仅 hk 用；null = 不覆盖，继承共享 client 的 DNS）。 */
    private static volatile Dns dnsOverride;

    /** 星辰 HkHttp.client() 的 charset=UTF-8 公共头拦截器（缺失时才补）。 */
    private static final Interceptor CHARSET = chain -> {
        okhttp3.Request req = chain.request();
        if (req.header("charset") == null) {
            req = req.newBuilder().header("charset", "UTF-8").build();
        }
        return chain.proceed(req);
    };

    /** hk 取数专用 client（单例；派生自共享 client，代理选择器链见类注释）。 */
    public static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (HkHttpClient.class) {
                c = client;
                if (c == null) {
                    OkHttpClient.Builder b = OkHttp.client().newBuilder()
                            .addInterceptor(CHARSET)
                            .addInterceptor(HkContentTypePreInterceptor.INSTANCE)
                            .addNetworkInterceptor(HkContentTypeAfterInterceptor.INSTANCE)
                            .proxySelector(OkHttp.selector())
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true);
                    // Brotli（星辰 HkHttp.client() 同款反射加载；依赖缺失则跳过）。
                    try {
                        Class<?> clz = Class.forName("okhttp3.brotli.BrotliInterceptor");
                        Object inst = clz.getField("INSTANCE").get(null);
                        b.addInterceptor((okhttp3.Interceptor) inst);
                    } catch (Throwable ignored) {
                    }
                    // CookieJar（星辰同款；装不上直接抛，client 不缓存、下次调用可重试）。
                    b.cookieJar(HkCookieJar.get(App.get()));
                    // DoH/自动 DNS：覆盖已设置时应用（见 setDns；未设置则继承共享 client 的 DNS）。
                    Dns dns = dnsOverride;
                    if (dns != null) b.dns(dns);
                    c = b.build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** 带超时变体（毫秒；<=0 用默认超时）。语义同 {@code OkHttp.client(long)}。 */
    public static OkHttpClient client(long timeoutMs) {
        if (timeoutMs <= 0) return client();
        return client().newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 设置取数 client 的 DNS 覆盖（仅 hk 用），并使已缓存的 client 失效重建。
     * 接线方（HkHttp.applyDoh / 3e）在 HkHttp.java 内传 HkHttp 的 HK_AUTO_DNS。
     */
    public static void setDns(Dns dns) {
        dnsOverride = dns;
        reset();
    }

    /** 使已缓存的取数 client 失效，下次 client() 时重建（仅 hk 用；供 DoH 设置变更时调用）。 */
    public static void reset() {
        synchronized (HkHttpClient.class) {
            client = null;
        }
    }

    private HkHttpClient() {
    }
}
