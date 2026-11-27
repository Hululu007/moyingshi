package com.github.aiyingshi.osc.tv.utils;

import android.text.TextUtils;

import com.github.aiyingshi.osc.tv.App;
import com.github.aiyingshi.osc.tv.bean.AiConfig;
import com.github.aiyingshi.osc.tv.service.AiCompletionClient;
import com.github.aiyingshi.osc.tv.setting.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * AI 搜索建议（P0 功能 4 的共享入口，搜索页联想行与无结果页共用）。
 *
 * <p>只发用户输入的关键词本身，不发历史、不发站源；只在用户停顿或点搜索后由调用方
 * 发一次；开关关着、总开关关着或中转未配置时不发请求（{@link #isEnabled()} 是本地
 * 判断，不联网；真正发请求前 {@code AiCompletionClient.requestSpec} 还会再走一次
 * {@code resolveEffectiveConfig()}，中转不可用时抛异常，在这里接住并静默降级）。
 */
public final class AiSearchSuggest {

    private static final int MAX_SUGGESTIONS = 3;

    public interface Callback {
        /** 主线程回调；失败或未配置时给空列表，调用方直接隐藏 AI 行。 */
        void onResult(List<String> suggestions);
    }

    private AiSearchSuggest() {
    }

    /** 本地门禁：功能开关开着且 AI 配置就绪（总开关开 + 中转地址或 BYOK key 已填）。 */
    public static boolean isEnabled() {
        if (!Setting.isAiSearchSuggest()) return false;
        return AiConfig.objectFrom(Setting.getAiConfig()).isReady();
    }

    /** 后台发一次请求；回调在主线程。 */
    public static void suggest(String keyword, Callback callback) {
        if (callback == null) return;
        final String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty()) {
            App.post(() -> callback.onResult(new ArrayList<>()));
            return;
        }
        Task.execute(() -> {
            List<String> result = request(query);
            App.post(() -> callback.onResult(result));
        });
    }

    private static List<String> request(String keyword) {
        AiConfig config = AiConfig.objectFrom(Setting.getAiConfig());
        try {
            String prompt = "你是影视搜索纠错助手。用户输入了搜索关键词“" + keyword + "”。"
                    + "只返回严格 JSON，不要解释、不要 Markdown，格式为"
                    + "{\"correction\":\"纠错后的片名或空字符串\",\"suggestions\":[\"联想词\"]}。"
                    + "correction 只有在输入明显打错字、是片名谐音或拼音缩写时才填，否则为空字符串；"
                    + "suggestions 给 1-3 个相关的正确片名或联想词（同系列、续作、同类型），不要编造不存在的片名；"
                    + "如果输入已经是正确片名，correction 为空，suggestions 给同系列或同类型片名。";
            AiCompletionClient.RequestSpec spec = AiCompletionClient.requestSpec(config, prompt);
            Request request = AiCompletionClient.buildRequest(spec);
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    AiCompletionClient.noteRelayAuthFailure(config, response.code());
                    return new ArrayList<>();
                }
                String body = response.body() == null ? "" : response.body().string();
                return parse(AiCompletionClient.extractCompletionText(body, config));
            }
        } catch (Throwable ignored) {
            // 中转未配置、设备注册失败、网络失败：全部静默降级，不抛错。
            return new ArrayList<>();
        }
    }

    private static okhttp3.OkHttpClient client() {
        return com.github.catvod.net.OkHttp.client().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build();
    }

    /** 先按 JSON 解析（correction 放首位，其次 suggestions），解析失败按行切分兜底。 */
    static List<String> parse(String text) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return result;
        try {
            String json = extractJson(text);
            JsonElement element = JsonParser.parseString(json);
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                addClean(result, string(root, "correction"));
                JsonArray suggestions = root.has("suggestions") && root.get("suggestions").isJsonArray()
                        ? root.getAsJsonArray("suggestions") : null;
                if (suggestions != null) {
                    for (JsonElement item : suggestions) {
                        if (item != null && item.isJsonPrimitive()) addClean(result, item.getAsString());
                        if (result.size() >= MAX_SUGGESTIONS) break;
                    }
                }
                if (!result.isEmpty()) return result;
            }
        } catch (Throwable ignored) {
            // 不是 JSON，走按行兜底。
        }
        for (String line : text.split("\\n")) addClean(result, line);
        return result;
    }

    private static void addClean(List<String> result, String value) {
        if (result.size() >= MAX_SUGGESTIONS || TextUtils.isEmpty(value)) return;
        String clean = value.trim()
                .replaceAll("^[0-9]+[\\.、\\s]+", "")
                .replaceAll("^[《\"'“‘]+|[》\"'”’]+$", "")
                .trim();
        if (clean.isEmpty() || result.contains(clean)) return;
        result.add(clean);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return text;
        return text.substring(start, end + 1);
    }
}
