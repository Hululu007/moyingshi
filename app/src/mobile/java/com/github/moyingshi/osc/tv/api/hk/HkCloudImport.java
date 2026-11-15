package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 小程序云口令导入：云 1~10 全分流取回规则 JSON，对应魔影小程序的 i22.H()（见逆向报告 §7.11）。
 * <p>
 * i22.H() 本体 1141 条指令被跳过、反编译失败，没有源码可照搬；本类按周边可读方法 +
 * 公开文档拼成分流实现，各路的证据等级见 docs/miniapp-cloudcode.md 的分流表：
 * 云 2 取回端点来自 textdb.online 官方文档（报告里只有上传端），云 5 取回是 i22.r() 的
 * 逐行直译，云 1/3/4/6~10 走 pasteme 系是 i22.s() 的逐行直译（分流归属是报告归纳，
 * 不是逐路证实）。
 * <p>
 * 取回的 JSON 不自己落盘：走 HkStore.importJsonText（第 2 步的 importFiles/importOne 链路，
 * 内含 HkRule.validate() + 落盘 + 站源登记），与文件/粘贴导入同一条路。
 * <p>
 * 云口令只是公开短链，不是加密：任何人拿到口令都能取回同一份规则；不要用云口令传带
 * 账号密码的规则（报告 §5）。这一点在管理页对话框里写清楚。
 * <p>
 * 只放 mobile 源集。fetch/importCloudCodes 是同步调用，必须在后台线程调。
 */
public class HkCloudImport {

    private static final String TAG = "HkCloudImport";

    /** 校验整条口令，对应 i22.c：^云([1-9]|10)oooole(/.*)$。 */
    private static final Pattern CODE_VALIDATE = Pattern.compile("^云([1-9]|10)oooole(/.*)$");

    /** 从一段文本里提取口令，对应 i22.d：云([1-9]|10)oooole/?\S+。 */
    private static final Pattern CODE_EXTRACT = Pattern.compile("云([1-9]|10)oooole/?\\S+");

    /** 云 5 cmd.im 取回：class="test_box" 的 div，对应 i22.r() 的正则（flag 32 = DOTALL）。 */
    private static final Pattern CMDIM_TEST_BOX = Pattern.compile("class=\"test_box\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    private static final String TEXTDB_READ_URL = "https://textdb.online/";
    private static final String CMDIM_URL = "https://cmd.im/";
    private static final String PASTEME_URL = "https://pasteme.tyrantg.com/api/getContent/";

    /** 最近一次云口令导入失败的原因（importCloudCodes 写，调用方读了展示）。 */
    private static volatile String lastError;

    /** 取最近一次云口令导入失败的原因，没有失败返回 null。 */
    public static String lastError() {
        return lastError;
    }

    /**
     * 从一段文本里提取云口令，照搬 HkSiteAdapter.extractCloudCodes + i22.c/d/G：
     * d 全文找，每条过 G()（oooole 后缺 / 补 /），去重保序。
     * 纯函数，不发请求；返回的每条都满足 c 的格式。
     */
    public static List<String> extractCloudCodes(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null || text.isEmpty()) return codes;
        Matcher matcher = CODE_EXTRACT.matcher(text);
        while (matcher.find()) {
            String code = normalizeCode(matcher.group());
            if (!codes.contains(code)) codes.add(code);
        }
        return codes;
    }

    /**
     * i22.G(String)：口令在 oooole 后缺 / 时补一个 /（云6oooolexxx → 云6oooole/xxx）；
     * 已有 / 或找不到 oooole 时原样返回。
     */
    private static String normalizeCode(String code) {
        int i = code.indexOf("oooole");
        if (i < 0 || i + 6 >= code.length() || code.charAt(i + 6) == '/') return code;
        return code.substring(0, i + 6) + "/" + code.substring(i + 6);
    }

    /**
     * 按口令里的云号分流取回规则 JSON（后台线程调用）。
     * 云 2 → textdb，云 5 → cmd.im，其余 → pasteme 系。
     * 失败抛 IllegalArgumentException（带真实原因）或网络异常，调用方展示原因；
     * 不返回 null，不吞异常（魔影 r() 吞异常是 UI 侧只报“导入失败”的根因之一）。
     */
    public static String fetch(String code) throws Exception {
        if (TextUtils.isEmpty(code)) throw new IllegalArgumentException("云口令为空");
        String trimmed = code.trim();
        Matcher matcher = CODE_VALIDATE.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("云口令格式不对：" + mask(trimmed));
        }
        int cloud = Integer.parseInt(matcher.group(1));
        String path = matcher.group(2);
        if (cloud == 2) return fetchTextDb(path);
        if (cloud == 5) return fetchCmdIm(path);
        return fetchPasteme(path);
    }

    /**
     * 云 2 textdb 取回：与第 6 步之二 HkCloudExport.uploadTextDb 的上传对称。
     * 口令是 云2oooole/apidb/&lt;key&gt;，去 apidb/ 前缀得 key；读取端点来自
     * textdb.online 官方文档（2026-11-15 核对）：GET https://textdb.online/&lt;key&gt;，
     * 返回纯文本（text/plain），始终 200，不存在则内容为空；value 是上传时写的
     * Base64，要解码成规则 JSON；记录 30 天未访问自动清理。
     */
    private static String fetchTextDb(String path) throws Exception {
        if (!path.startsWith("/apidb/")) {
            throw new IllegalArgumentException("云2口令格式不对（应为 云2oooole/apidb/<key>）");
        }
        String key = firstSegment(path.substring("/apidb/".length()));
        if (key.isEmpty()) throw new IllegalArgumentException("云2口令格式不对（应为 云2oooole/apidb/<key>）");
        String body = get(TEXTDB_READ_URL + key);
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("云口令对应的内容不存在或已被清理（textdb 超过 30 天未访问会自动清理）");
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) return trimmed;
        String decoded = decodeBase64Lenient(trimmed);
        if (decoded == null || !decoded.trim().startsWith("{")) {
            throw new IllegalArgumentException("取回的内容不是规则 JSON");
        }
        return decoded.trim();
    }

    /**
     * 云 5 cmd.im 取回，直译 i22.r()：
     * id = path 去首个 / 后按 [\s/] 切第一段；GET https://cmd.im/&lt;id&gt;；
     * 解析 class="test_box" 的 div，去标签、反转义 5 个 HTML 实体。
     */
    private static String fetchCmdIm(String path) throws Exception {
        String id = firstSegment(path.startsWith("/") ? path.substring(1) : path);
        if (id.isEmpty()) throw new IllegalArgumentException("云5口令格式不对");
        String body = get(CMDIM_URL + id);
        if (body == null) throw new IllegalArgumentException("云口令对应的内容为空或已失效");
        Matcher matcher = CMDIM_TEST_BOX.matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("云口令对应的内容为空或已失效");
        }
        String content = stripTagsAndUnescape(matcher.group(1));
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("云口令对应的内容为空或已失效");
        }
        return content.trim();
    }

    /**
     * 其余云 1/3/4/6~10 走 pasteme 系，直译 i22.s() + o() + T() + S()：
     * id = path 去首个 / 后的整段（s() 是 getContent/.concat(id)，多段 path 如
     * xxxxxx/yyy 原样传）；GET https://pasteme.tyrantg.com/api/getContent/&lt;id&gt;，
     * header referer / cookie（空串）/ UA 与 s() 一致；body 交给 normalizePasteme。
     * 注意：H() 本体缺失，“其余 8 路都走同一条 pasteme 路径、id 含多段”是外推，
     * 见 docs/miniapp-cloudcode.md 分流表；某路实测失败时只改这里的 id 取法。
     */
    private static String fetchPasteme(String path) throws Exception {
        String id = path.startsWith("/") ? path.substring(1) : path;
        if (id.isEmpty()) throw new IllegalArgumentException("云口令格式不对");
        OkHttpClient client = newHttpClient();
        Request request = new Request.Builder()
                .url(PASTEME_URL + id)
                .header("referer", "https://pasteme.tyrantg.com/")
                .header("cookie", "")
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalArgumentException("取回失败（HTTP " + response.code() + "）");
            }
            String content = normalizePasteme(response.body().string());
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalArgumentException("云口令对应的内容为空或已失效");
            }
            return content.trim();
        }
    }

    /**
     * pasteme 响应规范化，直译 i22.o() + T() + S()：
     * o()：响应 JSON 里 return_code 缺失或为 0、且 data 非空 → 取 data 字符串 → T()；
     * T()：先 S()，S() 非空则返回；否则含 base64:// 则解码分支；否则返回 trim 后的原串；
     * S()：纯 JSON（含 col_type + find_rule）直接返回；含 ￥ 且第二段是 home_rule /
     * home_rule_v2 时取第 3 段起的 JSON（只抽 JSON 段，不新增 ￥ 旧格式解析器）。
     * 以上都不中返回 null（调用方报“内容为空或已失效”）。
     */
    private static String normalizePasteme(String body) {
        if (body == null) return null;
        try {
            JsonElement parsed = JsonParser.parseString(body.trim());
            if (!parsed.isJsonObject()) return null;
            JsonObject obj = parsed.getAsJsonObject();
            if ((!obj.has("return_code") || obj.get("return_code").getAsInt() == 0)
                    && obj.has("data") && !obj.get("data").isJsonNull()) {
                String data = obj.get("data").getAsString().trim();
                if (data.isEmpty()) return null;
                return normalizePastemeContent(data);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** i22.T() + S()：见 normalizePasteme 的注释。 */
    private static String normalizePastemeContent(String text) {
        String trimmed = text.trim();
        String fromS = normalizeLegacyFormat(trimmed);
        if (fromS != null) return fromS;
        int base64Index = trimmed.indexOf("base64://");
        if (base64Index < 0) return trimmed;
        String[] parts = trimmed.substring(base64Index + 9).split("@");
        if (parts.length >= 3) {
            String decoded = decodeBase64Lenient(parts[2]);
            if (decoded != null && decoded.contains("{") && decoded.contains("}")) return decoded;
        }
        return null;
    }

    /** i22.S()：纯 JSON 直通；￥ home_rule 格式只抽其中 JSON 段。 */
    private static String normalizeLegacyFormat(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("col_type") && trimmed.contains("find_rule")) {
            return trimmed;
        }
        if (!trimmed.contains("￥")) return null;
        String[] segments = trimmed.split("￥", -1);
        if (segments.length < 3) return null;
        String kind = segments[1].trim();
        if (!"home_rule".equals(kind) && !"home_rule_v2".equals(kind)) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < segments.length; i++) {
            if (i > 2) sb.append("￥");
            sb.append(segments[i]);
        }
        String json = sb.toString().trim();
        if (json.startsWith("{") && json.contains("col_type")) return json;
        return null;
    }

    /**
     * 逐条导入云口令，照搬 HkSiteAdapter.importCloudCodes 的结构：
     * 逐条 fetch → HkStore.importJsonText（第 2 步链路，含 validate + 落盘 + 登记）；
     * 单条失败记 lastError、记日志、继续下一条；返回成功登记的条数。
     */
    public static int importCloudCodes(List<String> codes) {
        lastError = null;
        if (codes == null || codes.isEmpty()) return 0;
        int added = 0;
        for (String code : codes) {
            if (TextUtils.isEmpty(code)) continue;
            try {
                String json = fetch(code.trim());
                int count = HkStore.importJsonText(json);
                if (count <= 0) {
                    String error = HkStore.lastError();
                    lastError = TextUtils.isEmpty(error) ? "规则校验失败" : error;
                    Log.w(TAG, "cloud import fail code=" + mask(code) + " err=" + lastError);
                } else {
                    added += count;
                }
            } catch (Exception e) {
                lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                Log.w(TAG, "cloud import fail code=" + mask(code) + " err=" + lastError, e);
            }
        }
        Log.i(TAG, "cloud import codes=" + codes.size() + " added=" + added);
        return added;
    }

    /** GET 纯文本：魔影 r()/s() 用的 ky1.f() 共享客户端；这里用独立客户端，超时与 i22.h()/i() 一致（15s/20s）。 */
    private static String get(String url) throws Exception {
        OkHttpClient client = newHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalArgumentException("取回失败（HTTP " + response.code() + "）");
            }
            return response.body().string();
        }
    }

    private static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /** 取字符串按空白或 / 切的第一段（i22.r() 的 split("[\\s/]")[0]；空串返回 ""，不抛）。 */
    private static String firstSegment(String text) {
        if (text == null) return "";
        String[] parts = text.split("[\\s/]");
        return parts.length == 0 ? "" : parts[0];
    }

    /** i22.r() 的去标签 + 反转义（顺序与原文一致：先 &amp;，再其余四个）。 */
    private static String stripTagsAndUnescape(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", "").trim()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    /**
     * i22.e() 的 Base64 宽容解码：去空白，URL 安全字符 -/_ 换回 +//，补 = 到 4 的倍数，
     * 依次试 DEFAULT / URL_SAFE / NO_WRAP 三种 flag；都失败返回 null（不抛）。
     */
    private static String decodeBase64Lenient(String text) {
        if (text == null) return null;
        String cleaned = text.trim().replaceAll("\\s", "");
        if (cleaned.indexOf('-') >= 0 || cleaned.indexOf('_') >= 0) {
            cleaned = cleaned.replace('-', '+').replace('_', '/');
        }
        int remainder = cleaned.length() % 4;
        if (remainder == 1) return null;
        cleaned = cleaned.concat("====".substring(0, (4 - remainder) % 4));
        int[] flags = {Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP};
        for (int flag : flags) {
            try {
                return new String(Base64.decode(cleaned, flag), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 换下一个 flag 再试。
            }
        }
        return null;
    }

    /** 日志里不打完整口令：只留前 20 字（魔影 importCloudCodes 里 mask(str) 的同款做法）。 */
    private static String mask(String code) {
        if (code == null) return "";
        String trimmed = code.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20) + "…";
    }
}
