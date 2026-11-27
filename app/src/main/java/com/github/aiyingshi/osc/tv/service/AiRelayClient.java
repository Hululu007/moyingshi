package com.github.aiyingshi.osc.tv.service;

import com.github.aiyingshi.osc.tv.App;
import com.github.aiyingshi.osc.tv.BuildConfig;
import com.github.aiyingshi.osc.tv.bean.AiConfig;
import com.github.aiyingshi.osc.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 统付中转的设备身份与用量查询。
 *
 * <p>中转模式首次用 AI 时调 {@code POST {relayUrl}/v1/register} 拿设备 token；
 * token 用 Keystore AES-GCM 加密存（{@link AiRelayTokenStore}，alias
 * {@code webhtv_ai_relay_token}，与 BYOK 的 {@code webhtv_ai_key} 分开）。
 * 设备指纹是 {@code SHA-256(ANDROID_ID + 包名)}，原始 ANDROID_ID 绝不离开手机。
 *
 * <p>所有方法都是同步网络调用，必须在子线程执行；失败一律返回空/失败，
 * 调用方按“AI 不可用，优雅降级”处理，不抛到界面、不崩溃。
 */
public final class AiRelayClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PREF_TOKEN = "ai_relay_token";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 20;
    private static final int CALL_TIMEOUT_SECONDS = 30;

    private AiRelayClient() {
    }

    /** 设备指纹：SHA-256(ANDROID_ID + 包名) 的 64 位小写 hex；原始 ANDROID_ID 不出手机。 */
    public static String deviceHash() {
        try {
            String raw = Util.getAndroidId() + App.get().getPackageName();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) builder.append(String.format(Locale.US, "%02x", b));
            return builder.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 中转 base URL：去尾斜杠；空值原样返回空串。 */
    public static String relayBase(String relayUrl) {
        String value = Objects.toString(relayUrl, "").trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** 读本地设备 token（解密后明文）；没有或解不开返回空串。 */
    public static String loadToken() {
        try {
            String stored = Prefers.getString(PREF_TOKEN);
            if (stored == null || stored.isEmpty()) return "";
            if (!AiRelayTokenStore.isEncrypted(stored)) return "";
            String token = AiRelayTokenStore.decrypt(stored);
            return token == null ? "" : token.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean hasToken() {
        return !loadToken().isEmpty();
    }

    /** 加密保存设备 token；保存失败时清掉旧值，不留半截状态。 */
    public static void saveToken(String token) {
        String value = Objects.toString(token, "").trim();
        if (value.isEmpty()) {
            clearToken();
            return;
        }
        try {
            Prefers.put(PREF_TOKEN, AiRelayTokenStore.encrypt(value));
        } catch (Throwable ignored) {
            Prefers.remove(PREF_TOKEN);
        }
    }

    /** 删掉本地设备 token（登出/吊销/重置时用）。 */
    public static void clearToken() {
        try {
            Prefers.remove(PREF_TOKEN);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取设备 token：本地有就直接用，没有就向中转注册一个。
     * 注册失败返回空串，调用方按“本次 AI 不可用”降级，不重试注册。
     */
    public static synchronized String getOrRegisterToken(String relayUrl) {
        String token = loadToken();
        if (!token.isEmpty()) return token;
        String base = relayBase(relayUrl);
        if (base.isEmpty()) return "";
        token = register(base);
        if (!token.isEmpty()) saveToken(token);
        return token;
    }

    /**
     * 删本地 token 并重新注册。relayUrl 为空时只删不注册，返回 false。
     * 注意：中转对同一指纹当天只接受 1 次新注册；本地 token 被吊销后当天重注册会被
     * 中转以 429 拒绝（需管理员在 KV 里删掉当天指纹记录才放行），此时返回 false。
     */
    public static synchronized boolean resetIdentity(String relayUrl) {
        clearToken();
        String base = relayBase(relayUrl);
        if (base.isEmpty()) return false;
        String token = register(base);
        if (token.isEmpty()) return false;
        saveToken(token);
        return true;
    }

    /** 调 GET {relayUrl}/v1/status 查本月用量；任何失败返回 null，不抛异常。 */
    public static RelayStatus fetchStatus(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isRelayMode()) return null;
        String base = relayBase(safe.getRelayUrl());
        if (base.isEmpty()) return null;
        String token = getOrRegisterToken(base);
        if (token.isEmpty()) return null;
        try {
            Request request = new Request.Builder()
                    .url(base + "/v1/status")
                    .header("Authorization", "Bearer " + token)
                    .get()
                    .build();
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    noteAuthFailure(response.code());
                    return null;
                }
                String body = response.body() == null ? "" : response.body().string();
                return parseStatus(body);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 中转回 401/403 说明本地 token 已失效（未知/吊销）：清掉，下次调用重新注册。 */
    public static void noteAuthFailure(int code) {
        if (code == 401 || code == 403) clearToken();
    }

    private static String register(String base) {
        String deviceHash = deviceHash();
        if (deviceHash.isEmpty()) return "";
        JsonObject body = new JsonObject();
        body.addProperty("deviceHash", deviceHash);
        body.addProperty("appVersion", BuildConfig.VERSION_NAME);
        Request request = new Request.Builder()
                .url(base + "/v1/register")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful()) return "";
            String responseBody = response.body() == null ? "" : response.body().string();
            JsonElement element = JsonParser.parseString(responseBody);
            if (element == null || !element.isJsonObject()) return "";
            JsonElement token = element.getAsJsonObject().get("token");
            return token != null && token.isJsonPrimitive() ? token.getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static RelayStatus parseStatus(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return null;
            JsonObject object = element.getAsJsonObject();
            long used = longValue(object, "monthTokensUsed");
            long limit = longValue(object, "monthTokensLimit");
            boolean overLimit = object.has("overLimit") && object.get("overLimit").isJsonPrimitive()
                    && object.get("overLimit").getAsBoolean();
            return new RelayStatus(used, limit, overLimit);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long longValue(JsonObject object, String key) {
        try {
            if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsLong();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static OkHttpClient client() {
        return OkHttp.client().newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    public static final class RelayStatus {

        private final long monthTokensUsed;
        private final long monthTokensLimit;
        private final boolean overLimit;

        private RelayStatus(long monthTokensUsed, long monthTokensLimit, boolean overLimit) {
            this.monthTokensUsed = monthTokensUsed;
            this.monthTokensLimit = monthTokensLimit;
            this.overLimit = overLimit;
        }

        public long getMonthTokensUsed() {
            return monthTokensUsed;
        }

        public long getMonthTokensLimit() {
            return monthTokensLimit;
        }

        public boolean isOverLimit() {
            return overLimit;
        }
    }
}
