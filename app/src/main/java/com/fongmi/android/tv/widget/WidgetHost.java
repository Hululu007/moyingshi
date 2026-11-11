package com.fongmi.android.tv.widget;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.JSUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Asset;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Widget 引擎宿主：一个组件脚本一个 Context（与 drpy 蜘蛛并行，各建各的 Context、各走各的宿主 API）。
 *
 * <p>加载流程：建 QuickJSContext → 挂 console → 注入 widget_host.js shim + WidgetBridge →
 * 设 storage 前缀 → 执行组件脚本 → 读全局 WidgetMetadata。
 * 调用流程：按 functionName（白名单正则校验）在全局对象上调模块函数（Async.run 处理 Promise 返回）。
 * 超时与异常隔离：单个组件加载/调用超时或抛错只影响该组件，由调用方隐藏对应行。
 */
public class WidgetHost {

    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
    private static final long LOAD_TIMEOUT_MS = 10000;
    private static final long INVOKE_TIMEOUT_MS = 20000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String widgetId;
    private final String scriptSource;
    private final WidgetBridge bridge;
    private QuickJSContext ctx;
    private volatile boolean destroyed;

    public WidgetHost(String widgetId, String scriptSource) {
        this.widgetId = widgetId;
        this.scriptSource = scriptSource;
        this.bridge = new WidgetBridge(widgetId);
    }

    public String getWidgetId() {
        return widgetId;
    }

    /** 加载组件脚本并返回其 WidgetMetadata；失败抛错（调用方隐藏该组件行）。 */
    public WidgetMetadata load() throws Exception {
        if (destroyed) throw new IllegalStateException("WidgetHost 已销毁: " + widgetId);
        Future<WidgetMetadata> future = executor.submit(() -> {
            createCtx();
            ctx.evaluate(Asset.read("js/lib/widget_host.js"));
            ctx.evaluate(scriptSource);
            Object meta = ctx.getProperty(ctx.getGlobalObject(), "WidgetMetadata");
            if (!(meta instanceof JSObject)) throw new IllegalStateException("WidgetMetadata 缺失");
            String json = ((JSObject) meta).stringify();
            WidgetMetadata metadata = App.gson().fromJson(json, WidgetMetadata.class);
            if (metadata == null || TextUtils.isEmpty(metadata.getId())) throw new IllegalStateException("WidgetMetadata 非法");
            return metadata;
        });
        try {
            return future.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            destroy();
            throw new TimeoutException("组件加载超时: " + widgetId);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /**
     * 调模块函数，返回结果的 JSON 字符串（条目数组）。
     * 返回 null 表示函数返回了 null/undefined（按空列表处理）。
     */
    public String invoke(String functionName, Map<String, String> params) throws Exception {
        if (destroyed) throw new IllegalStateException("WidgetHost 已销毁: " + widgetId);
        if (functionName == null || !SAFE_PATH.matcher(functionName).matches()) {
            throw new IllegalArgumentException("functionName 格式非法: " + functionName);
        }
        Future<String> future = executor.submit(() -> {
            JSObject paramsObj = JSUtil.toObject(ctx, params);
            Object result = Async.run(ctx.getGlobalObject(), functionName, paramsObj).get(INVOKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null) return null;
            if (result instanceof String) return (String) result;
            if (result instanceof JSObject) return ((JSObject) result).stringify();
            return String.valueOf(result);
        });
        try {
            return future.get(INVOKE_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            destroy();
            throw new TimeoutException("组件调用超时: " + widgetId + "." + functionName);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    if (ctx != null) ctx.destroy();
                } catch (Throwable e) {
                    SpiderDebug.log("widget", "destroy failed: %s", e.getMessage());
                }
                return null;
            });
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        } finally {
            executor.shutdownNow();
        }
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
        ctx.setConsole(new Console());
        bridge.bind(ctx);
    }

    private static Exception unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) return (Exception) cause;
        return new Exception(cause == null ? e.getMessage() : cause.getMessage(), cause);
    }
}
