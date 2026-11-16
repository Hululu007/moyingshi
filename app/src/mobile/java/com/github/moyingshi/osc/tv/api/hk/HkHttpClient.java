package com.github.moyingshi.osc.tv.api.hk;

import com.github.catvod.net.OkHttp;

import java.util.concurrent.TimeUnit;

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
 * <p>本阶段只搬第 (1) 项中与代理选择器链有关的部分，外加 charset 头：
 * <ul>
 *   <li>派生自共享 {@code OkHttp.client()}：trust-all SSL、OkDns、请求/响应/鉴权拦截器、
 *       30s 超时策略全部继承，不改行为；
 *   <li>加 charset=UTF-8 公共头拦截器（星辰同款，8.83 OkGo 公共头；缺失时才补）；
 *   <li>显式 {@code .proxySelector(OkHttp.selector())}：我们仓库没有星辰用的
 *       {@code com.github.catvod.net.GlobalProxy} / {@code SourceGate} 类（全仓库 grep
 *       零命中），等价物是 catvod 的 {@code OkProxySelector}——规则列表为空时 fallback
 *       系统选择器即直连，与星辰“无代理配置时直通默认选择器”等价；用户在 ProxySetting /
 *       ShellProxyDialog / BaseConfig 里配的代理规则由此生效；
 *   <li>Brotli 拦截器按星辰做法反射加载，依赖缺失则跳过（我们没有
 *       {@code okhttp-brotli} 依赖，当前一定跳过；后续加依赖即自动生效）。
 * </ul>
 *
 * <p>不做的事（留给阶段 3，随引擎核心搬）：CookieJar 持久化、ContentType 前后拦截器、
 * DoH、10s 超时、SourceGate 按源定向。没配代理时与原来直连等价，有代理时按代理走。
 */
public final class HkHttpClient {

    private static volatile OkHttpClient client;

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
                            .proxySelector(OkHttp.selector());
                    // Brotli（星辰 HkHttp.client() 同款反射加载；依赖缺失则跳过）。
                    try {
                        Class<?> clz = Class.forName("okhttp3.brotli.BrotliInterceptor");
                        Object inst = clz.getField("INSTANCE").get(null);
                        b.addInterceptor((okhttp3.Interceptor) inst);
                    } catch (Throwable ignored) {
                    }
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

    private HkHttpClient() {
    }
}
