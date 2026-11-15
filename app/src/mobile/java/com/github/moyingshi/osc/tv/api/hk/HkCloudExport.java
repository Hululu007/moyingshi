package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Base64;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 小程序导出：云 2 textdb 上传生成云口令，对应魔影小程序的 i22.h()（见逆向报告 §7.11）。
 * <p>
 * 只做云 2（https://api.textdb.online/update/）；云 5 cmd.im（i22.i()）不在本步范围，
 * 第 7 步云口令全分流再做。上传前不改规则内容（只读 HkStore.readRaw 的原文），
 * 不往规则里写凭证。
 * <p>
 * 云口令只是公开短链，不是加密：任何人拿到口令都能取回同一份规则；
 * 不要用云口令传带账号密码的规则（报告 §5）。这一点在分享文本首行
 * （shareText）里写清楚。
 * <p>
 * 只放 mobile 源集。上传是同步调用，必须在后台线程调。
 */
public class HkCloudExport {

    private static final String TEXTDB_UPDATE_URL = "https://api.textdb.online/update/";

    /** 云口令前缀，对应 i22.h() 返回的 "云2oooole/apidb/" + key。 */
    private static final String CLOUD_CODE_PREFIX = "云2oooole/apidb/";

    /**
     * 把一条规则上传到云 2 textdb，返回云口令（"云2oooole/apidb/&lt;key&gt;"）。
     * 直译报告里的 i22.h()：
     * key = "xc" + epoch秒 + "_" + abs(title.hashCode())；
     * value = 规则 JSON UTF-8 字节的 Base64（NO_WRAP，不换行）；
     * POST FormBody（key + value），UA Mozilla/5.0，connect 15s / read 20s；
     * 响应 JSON status == 1 才成功。
     * 失败抛 IllegalArgumentException（带真实原因）或网络异常，调用方展示原因；
     * 不返回 null，不假装成功。
     */
    public static String uploadTextDb(String title) throws Exception {
        if (TextUtils.isEmpty(title)) {
            throw new IllegalArgumentException("规则标题为空");
        }
        String rawJson = HkStore.readRaw(title);
        if (rawJson == null) {
            throw new IllegalArgumentException("规则不存在：" + title);
        }
        String value = Base64.encodeToString(rawJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String key = "xc" + System.currentTimeMillis() / 1000 + "_" + Math.abs(title.hashCode());
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(TEXTDB_UPDATE_URL)
                .post(new FormBody.Builder().add("key", key).add("value", value).build())
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalArgumentException("上传失败（HTTP " + response.code() + "）");
            }
            String body = response.body().string();
            try {
                JsonElement parsed = JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    if (obj.has("status") && obj.get("status").getAsInt() == 1) {
                        return CLOUD_CODE_PREFIX + key;
                    }
                }
            } catch (Exception ignored) {
                // 解析失败同样走下面的“服务器返回异常”，不崩。
            }
            String detail = "";
            if (body != null) {
                String trimmed = body.trim().replace('\n', ' ').replace('\r', ' ');
                if (!trimmed.isEmpty()) {
                    if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80) + "…";
                    detail = "（" + trimmed + "）";
                }
            }
            throw new IllegalArgumentException("上传失败：服务器返回异常" + detail);
        }
    }

    /**
     * 分享文本：首行是公开短链提醒（云口令不是加密），随后每行一条 "标题：云口令"。
     * codes 按导出顺序（LinkedHashMap），标题 → 云口令。
     * 文本格式是咱们定的（报告 s54 case 25 本体无源码）；口令本身与魔影格式一致。
     * 分享面板标题用字符串资源 hk_miniapp_export_share_title_one/many
     * （i22.d() 对应物：1 条 → "导出：&lt;标题&gt;"，多条 → "导出 N 个小程序"）。
     */
    public static String shareText(Map<String, String> codes, String note) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(note)) sb.append(note).append("\n");
        if (codes != null) {
            for (Map.Entry<String, String> e : codes.entrySet()) {
                sb.append(e.getKey()).append("：").append(e.getValue()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
