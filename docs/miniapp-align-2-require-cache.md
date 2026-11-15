# 对齐第 2 项：require 缓存 + 坏内容回退旧缓存

> Recovery anchor — 目标：给海阔小程序的 `require`/`rc` 补官方同款行为：好内容写 MD5 文件缓存，取到坏内容（HTML/418/空/isJsCode 失败）时静默回退磁盘旧缓存，无缓存才报清楚错；只做这一项，不碰其余 4 项；本地提交、不推、不编。
> 验收：① 好内容取到后写缓存，下次命中不发请求；② 缓存存在时取坏（418 HTML）静默回退旧库、规则继续跑；③ 无缓存 + 取坏仍是清楚报错（含 HTTP 状态码）；④ 3 条验收规则零回归（它们不走 http require，见 §4）。
> 当前状态：main = 4022e7bc（第 1 项本地未推），工作区干净；本文档先写，代码未改；下一步：guard start → 改 HkEngine → Node 转写校验 isJsCode 决策表 + 逐行重读 → guard finish 提交 + recovery tag。

- 完成句：墨影视海阔小程序的 `require`/`rc` 按官方行为补上 MD5 文件缓存 + isJsCode 校验 + 坏内容静默回退旧缓存，本地提交完成。
- 允许路径：`docs/miniapp-align-2-require-cache.md`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`。
- 保护：TV/leanback 不动，BaseLoader 不动，底栏仍 6 项（`app/src/mobile/res/menu/menu_nav.xml`，提交前核对）；不推、不编、不动版本记录空间；不碰其余 4 项（第 1 项已做完的也不改）。
- 本地时间：2026-11-15 22:44 CST；预计：文档 20 分钟 + 实现 25 分钟 + 校验提交 20 分钟，约 23:50 前完成。
- 最便宜的决定性验证：Node 逐字转写 `isJsCode`/`isGoodLib` 决策表（真库 / 418 HTML / 空 / 小写 doctype / 纯导出对象）+ 逐行重读 diff + menu 6 项核对；本地无 JDK，编译等推上去之后的 GitHub 构建；真机行为（缓存回退实际生效）只能等推上去编好后在手机上验。

## 1. 证据（官方实现，读透再动手）

来源：`~/workspace/hk-ref/ANALYSIS.md` §5.1/§8 + `~/workspace/hk-ref/src/sources/com/fongmi/android/tv/api/hk/HkJsRuntime.java`（行号为 jadx 常规版输出）。频率：`require(` 在 4255 条合集里出现 14661 次。

### 1.1 require0（`HkJsRuntime.java:24405`，权威实现）

- 缓存目录：`<hkRoot>/libs/<md5(url)>.js` + `<md5>.json` 版本 meta（`m45.g(url)` = md5）。
- 有效期判定（`:24457`，`i` = 小时数，`i2` = 版本要求，默认 0）：`file.exists() && (i >= 0 ? i != 0 && requireVersion >= i2 && now - lastModified <= i * 3600000 : requireVersion >= i2)`。第 1 项已按此实现 `isCacheFresh`，本项不动它。
- 取库走 `requestForCode(url, options)`（`:24456`）。有缓存文件时（`:24458-24471`）：
  ```java
  if (z && !isJsCode(fileContent)) {
      log("url: " + strProxyRequire + "获取的内容被判定为非JS代码", null);
      fileContent = readFileContent(strM);            // 回退磁盘旧缓存
  } else if ((!z && strProxyRequire.contains("hiker.nokia.press") && (TextUtils.isEmpty(fileContent) || fileContent.length() < 3 || fileContent.contains("非法猎鹰"))) || giteePoisoned(strProxyRequire, fileContent)) {
      fileContent = readFileContent(strM);            // 同样回退旧缓存
  } else {
      if (TextUtils.isEmpty(fileContent)) {
          throw new Exception("获取远程依赖失败：".concat(strProxyRequire));
      }
      writeFileContent(strM, fileContent);            // 好内容才写缓存
      updateDescription(strM2, strTryGetString, i2); // 更新版本 meta
  }
  ```
  无缓存文件时（`:24472-24476`）：空内容 → 抛 `"获取远程依赖失败："`；非空 → 写缓存 + 更新 meta。
- `z` 参数：`require`（`:8359`）与 `requireCacheEval`/`rc`（`:24490`）都传 `true`，`fc`（fetchCode，`:2555`）传 `false`。**我们的 `require()`/`rc()` 走的正是 `z=true` 那条路，所以 `isJsCode` 校验适用；`fc` 我们没实现，不用管。**

### 1.2 requestForCode（`HkJsRuntime.java:24372`）：只认 2xx

```java
String string = getString(ScriptableObject.getProperty(scriptable, "error"));
if ((TextUtils.isEmpty(string) || "null".equals(string)) && String.valueOf((int) toDouble(ScriptableObject.getProperty(scriptable, "statusCode"))).startsWith("2")) {
    return getString(ScriptableObject.getProperty(scriptable, "body"));
}
return null;
```

即：**非 2xx（418）直接回 `null`**，不把拦截页当内容。`null` 进 `require0` 后：`isJsCode(null)` 为 false → `z && !isJsCode` 成立 → 回退旧缓存；无缓存时 `TextUtils.isEmpty(null)` 成立 → 抛 `"获取远程依赖失败："`。这就是 418 时官方“静默回退”的完整链条。

### 1.3 isJsCode（`HkJsRuntime.java:4174`，逐字照抄的判定标准）

```java
private boolean isJsCode(String str) {
    if (TextUtils.isEmpty(str)) {
        return false;
    }
    String strTrim = str.trim();
    String[] strArr = {"<!DOCTYPE", "<html", "<?xml"};
    for (int i = 0; i < 3; i++) {
        if (strTrim.startsWith(strArr[i])) {
            return false;
        }
    }
    String[] strArr2 = {"</html>", "</rss>"};
    for (int i2 = 0; i2 < 2; i2++) {
        if (strTrim.endsWith(strArr2[i2])) {
            return false;
        }
    }
    String[] strArr3 = {"var ", "let ", "function", "eval(", "call(", "eval (", "call (", " => ", ")=>"};
    for (int i3 = 0; i3 < 9; i3++) {
        if (strTrim.contains(strArr3[i3])) {
            return true;
        }
    }
    List<String> list = this.methodList;
    if (list != null) {
        Iterator<String> it2 = list.iterator();
        while (it2.hasNext()) {
            if (strTrim.contains(it2.next())) {
                return true;
            }
        }
    }
    return false;
}
```

即：空 → 否；trim 后以 `<!DOCTYPE` / `<html` / `<?xml` 开头 → 否；以 `</html>` / `</rss>` 结尾 → 否；含 `var ` / `let ` / `function` / `eval(` / `call(` / `eval (` / `call (` / ` => ` / `)=>` 任一 → 是；再查 `methodList`（已注册 API 方法名表，`:406` 声明、`:3226/:3231` 填充）含任一方法名 → 是；其余 → 否。**注意官方的前缀检查是大小写敏感的**（只判大写 `<!DOCTYPE`）。

### 1.4 giteePoisoned（`HkJsRuntime.java:3419`）与 loadLibLocal（`:9248`）

- `giteePoisoned`：URL 含 `gitee.com/` 且内容为空/不足 3 字符/含 `"The Repository is invisible"` → 判毒，回退旧缓存。它在 `require0` 里走 `!z` 分支（`fc` 路），我们的 `z=true` 路不直接触发；但 gitee 隐身页本身是 HTML，`isJsCode` 同样判否，所以**不必另写一份 gitee 检查**（见 §3.5）。
- `loadLibLocal`（`:9248`）：从本地缓存读库时也过 `isJsCode`，非 JS 的缓存直接拒掉（`return null`）。`require0` 的“有效期内命中”分支本身不复检缓存内容；我们的缓存自 f0c10fb7 的 HTML 守卫起从未写过坏内容（守卫早于缓存基建），所以本项**不给“有效期内命中”加复检**，与 `require0` 一致。

### 1.5 版本 meta

`getRequireVersion`（`:3407`）读 `<md5>.json` 的版本，`updateDescription`（`:25375`）写版本。我们 `HkStore.saveRcCache` 写 meta `{url, fetchedAt}`、不写版本，`isCacheFresh` 把“无版本信息”按通过计——第 1 项已定的做法，本项不动。

## 2. 现状（缺在哪，4022e7bc）

`HkEngine.require(String url, Object params, int cacheHours)`（`HkEngine.java:1121` 起，http(s) 分支约 `:1137-1165`）：

- 新鲜缓存（`isCacheFresh`）命中 → 直接用，不发请求（第 1 项已做，本项不动）。
- 未命中时 `httpFetch` 抓下来（`HkEngine.java:1317`，非 2xx 也返回 body，`lastHttpCode` 记录状态码，902c1e8c 已做）：
  - 空 → 抛 `"require 取数失败（HTTP <码>，空内容）："`；
  - trim 后小写以 `<!doctype` / `<html` 开头 → 抛 `"require 取到 HTTP <码>（不是 JS 脚本，可能是拦截/错误页）："`（f0c10fb7 的守卫）；
  - 否则 `HkStore.saveRcCache(u, code)` 写缓存再 eval。
- **缺口（第 1 项刻意留下的口子，见该方法注释）**：取到坏内容时直接抛错，**不回退磁盘上已有的旧缓存**；没有 `isJsCode` 校验（只有 HTML 开头检查）。
- 已有基建（不用另起一套）：`HkStore.rcCacheFile(u)` → `plugins/hk/libs/<md5(url)>.js`，`HkStore.rcCacheMetaFile(u)` → 同名 `.json`，`HkStore.saveRcCache(u, code)` 写 `.js` + meta，`HkStore.readTextFile(File)` 读文件（失败回 null，`:356`）。

## 3. 方案

只改 `HkEngine.java` 的 `require()` http(s) 分支 + 新增两个小方法；`HkStore.java` 不动。

### 3.1 `isJsCode`：逐字照官方 `:4174` 移植

```java
static boolean isJsCode(String code) {
    if (TextUtils.isEmpty(code)) return false;
    String t = code.trim();
    String[] badStarts = {"<!DOCTYPE", "<html", "<?xml"};
    for (String s : badStarts) {
        if (t.startsWith(s)) return false;
    }
    String[] badEnds = {"</html>", "</rss>"};
    for (String s : badEnds) {
        if (t.endsWith(s)) return false;
    }
    String[] markers = {"var ", "let ", "function", "eval(", "call(", "eval (", "call (", " => ", ")=>"};
    for (String m : markers) {
        if (t.contains(m)) return true;
    }
    return false;
}
```

`methodList` 分支无等价物：我们没有集中注册的 API 方法名表。真实库总含上述 9 个标记之一（`var `/`let `/`function`/`=>`），该分支略过（见 §3.5）。

### 3.2 `isGoodLib`：新取到的内容是否可用

```java
private boolean isGoodLib(String code) {
    if (TextUtils.isEmpty(code)) return false;
    if (lastHttpCode >= 0 && (lastHttpCode < 200 || lastHttpCode >= 300)) return false; // 照 requestForCode，只认 2xx
    String lower = code.trim().toLowerCase(Locale.ROOT);
    if (lower.startsWith("<!doctype") || lower.startsWith("<html")) return false;       // 保留 f0c10fb7 的小写兜底
    return isJsCode(code);
}
```

- 非 2xx 即判坏：照 `requestForCode`（§1.2），418 拦截页不再靠“HTML 开头”碰运气才判坏。
- 保留 f0c10fb7 的小写 `<!doctype`/`<html` 兜底：官方 `isJsCode` 只判大写 `<!DOCTYPE`，小写 doctype 且结尾又不是 `</html>`/`</rss>` 的页面会漏进 marker 检查（拦截页内联脚本里常有 `function`）；完整 HTML 页官方靠 `</html>` 后缀已能拦住。JS 库不可能以 `<!doctype`/`<html` 开头，多这一层只会把更多 HTML 判坏，**不会误伤真库**。

### 3.3 `require()` 取后分支：坏 → 回退旧缓存，无缓存才报错

```java
code = httpFetch(u, null, "GET", null, DEFAULT_TIMEOUT_MS);
if (!isGoodLib(code)) {
    // 取坏：静默回退磁盘旧缓存（官方 require0 :24458-24461 同款行为），
    // 不论该缓存是否过期；无缓存才报清楚错（902c1e8c 的报错原样保留）。
    String fallback = readStaleLibCache(u);
    if (!TextUtils.isEmpty(fallback)) {
        Log.i(TAG, "require fallback to cached lib (bad fetch, HTTP " + lastHttpCode + "): " + u);
        code = fallback;
    } else if (TextUtils.isEmpty(code)) {
        throw new HkException(lastHttpCode >= 0
                ? "require 取数失败（HTTP " + lastHttpCode + "，空内容）：" + u
                : "require 取数失败（空内容）：" + u);
    } else {
        throw new HkException(lastHttpCode >= 0
                ? "require 取到 HTTP " + lastHttpCode + "（不是 JS 脚本，可能是拦截/错误页）：" + u
                : "require 取到的不是 JS 脚本（可能是拦截/错误页）：" + u);
    }
} else {
    HkStore.saveRcCache(u, code);
}
```

回退读到的旧库直接 eval（照官方 `require0` 回退分支：不复检旧缓存内容）。回退不更新缓存文件的 mtime/meta——它本来就是旧版，下次仍会先试抓新版，抓不到再回退。

### 3.4 `readStaleLibCache`：读磁盘上已有的库缓存（不论是否过期）

```java
/** 坏内容回退用：读磁盘上已有的库缓存（不论是否过期），没有/读失败/空返回 null。 */
private static String readStaleLibCache(String url) {
    File cached = HkStore.rcCacheFile(url);
    if (cached == null || !cached.exists()) return null;
    return HkStore.readTextFile(cached);
}
```

### 3.5 取舍与说明（有意的偏离，不是漏抄）

- **无缓存 + 首次取坏，我们不写缓存、直接报错**；官方 `require0` 的无文件分支是不做 `isJsCode` 检查就写缓存的（`:24472-24476`：非空即写）。那是官方的 quirk——首次取到 HTML 也会写进缓存，之后 `loadLibLocal` 再拒掉。本项任务定的是“无缓存 + 取坏保持清楚报错，不编造内容”，所以我们比官方更严：**坏内容永远不进缓存**。
- **`giteePoisoned` 与 `hiker.nokia.press` / `非法猎鹰` 特判不另写**：前者页面是 HTML，`isJsCode` 已判否；后者是 `!z`（`fc` 路）专属特判，我们不实现 `fc`。都不影响本项目标。
- **不编代理/镜像，不替规则作者换托管站，不改规则本身**（任务定死）。
- `Bridge.require` → `engine.require(url, params)`（cacheHours `-1`，永久有效）、`Bridge.requireCache` → `engine.require(url, null, cacheHours)`，两条路都走同一个改动后的分支，无需另改。

## 4. 验收

| # | 验收 | 本地怎么核查（无 JDK、无真机） |
|---|---|---|
| ① | 好内容取到后写缓存，下次命中不发请求 | 代码级：好内容分支仍调 `saveRcCache`；新鲜命中分支（第 1 项）未动。`isGoodLib` 决策表经 Node 转写校验（真库样本须判好）。 |
| ② | 缓存存在时取坏（418 HTML）静默回退旧库、规则继续跑 | 代码级：`!isGoodLib` → `readStaleLibCache` 非空 → `code = fallback` 后正常 eval，不抛错。决策表：418 HTML 样本须判坏。 |
| ③ | 无缓存 + 取坏仍是清楚报错（含 HTTP 状态码） | 代码级：`readStaleLibCache` 为 null/空时走原有两条 `throw`（文案、状态码原样）。决策表：空内容、HTML 样本在无缓存分支的预期行为是抛错（代码路径核对）。 |
| ④ | 3 条验收规则零回归 | `~/workspace/miniapp/test-rules/` 的 3 条规则（奇优影院♔、童话、臻品fix）经 grep 不含 http(s) 库 `require(`/`rc(` 调用（仅 README 提到其他规则的 require），根本不进改动分支；改动只影响 `require()` 的 http(s) 取后分支，其余路径（子页面、require.json 清单、新鲜缓存命中）一字未动。 |

Node 转写校验的决策表（`isJsCode` 逐字转写 + `isGoodLib` 的状态/小写兜底）：

- 真库样本（含 `var ` / `function` / `=>`）→ 好；
- gitcode 418 HTML（`<!DOCTYPE html>…</html>`，含内联 `function`）→ 坏（前缀拒掉，先于 marker 检查）；
- 小写 `<!doctype html>` 拦截页 → 坏（小写兜底；不以 `</html>` 结尾时纯官方 `isJsCode` 会因内联 `function` 漏判，见 §3.2）；
- `<?xml …` / 以 `</rss>` 结尾 → 坏；
- 空 / 空白 → 坏；
- 仅 `$.exports = {...}` 无任何标记的片段 → 坏（**与官方一致**：官方 `isJsCode` 同样判否；真实库极少如此，见 §6）；
- 含 `let ` / `call(` / `)=>` 的压缩库 → 好。

另核对：`menu_nav.xml` 仍 6 项；`git diff` 逐行重读（返回类型、泛型、三元表达式类型一致是已知翻车点——本项三元只出现在原有报错文案里，未新增）。

## 5. 回滚

- 单一原子提交 + recovery tag；回滚即 `git revert <本提交>`（只改 `HkEngine.java` 一处 + 本文档，revert 干净）。
- 回滚锚点：`4022e7bc`（第 1 项，本地未推）。

## 6. 诚实限定（写进发版说明，不要承诺更多）

- 这项只能让**“曾经取到过一次的库”**在以后被拦时继续用。用户手机上现在没有缓存过的库，gitcode 继续拦的话，西瓜影音/鱼鱼/海W剧汇头一次还是出不了数据——**这是官方也一样的路子**（ANALYSIS.md §8：全新安装、无缓存的设备与我们一样拿不到库）。
- 首次取库成功的唯一办法仍是 gitcode 当时放行（或用户在放行时打开过一次规则，让库落进缓存）。
- `isJsCode` 是启发式判定：极少数不含 9 个标记任一的真库（如纯 `$.exports = {...}`）会被判坏——官方同样判坏，行为一致；真机上若遇此类库，回退/报错是符合官方的行为，不是我们的 bug。
- 本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机回退实际生效（缓存文件存在 + 418 时规则继续跑）只能等编好后在手机上验。

## 7. 验收记录（2026-11-15 22:44+ 本地做完）

- 提交：`（guard finish 后填）`；recovery tag：`（随后填）`；回滚锚点 4022e7bc。
- Node 转写校验 `/tmp/miniapp-align-2-check.js`：**30 项全过**（exit 0）。覆盖：真库（var/function、let、eval(、call(、`(a,b)=>`）判好；纯 `$.exports`、`(e=>e.data)` 无标记判坏（与官方一致）；418 HTML（含内联 function）判坏；小写 doctype 判坏（小写兜底；对照项证实纯官方 `isJsCode` 对不以 `</html>` 结尾的小写 doctype 样本确实漏判）；`<?xml` 开头、`</rss>` 结尾、空/空白/null、404/301 状态判坏；取后分支三场景：好内容→eval-fetched、418/空/200非JS+有缓存→eval-fallback、无缓存→throw-empty/throw-notjs。
- 校验中小插曲（实现本身无错，都是校验脚本期望写错）：① `(e=>e.data)` 不含 `)=>`/` => `，官方同样判否，改期望为坏；② 小写 doctype 完整页以 `</html>` 结尾，官方靠后缀已拦住，漏判只发生在不以 `</html>` 结尾的样本上，改样本并修正文档 §3.2/§4 表述。实现代码一次写成，未返工。
- diff 逐行重读：只改 `HkEngine.java` 一处（+74/−16），三元表达式沿用原有两条报错文案（双分支均为 String），无新增泛型；`git diff --check` 干净；`menu_nav.xml` 仍 6 项。
- 验收 ④：3 条验收规则经 grep 确认不含 `require(`/`rc(` 调用（各 0 次），不进改动分支；子页面/require.json 清单/新鲜缓存命中路径一字未动。
- 未在本地验（无 JDK/Android SDK、无真机）：编译等推上去之后的 GitHub 构建；真机回退实际生效（有缓存 + 418 时规则继续跑）等编好后手机上验；用户手机现无缓存库，gitcode 续拦时首次仍无数据（见 §6，与官方一致）。

## 8. 不做（第 1、3、4、5 项，勿碰）

- 第 1 项（config/initConfig 进程内共享 + rc 小时数，4022e7bc 已做完）：不动。
- 第 3 项 refreshPage 真实现、第 4 项 batchFetch 改形状、第 5 项 storage/getItem/setItem 落盘：不动。
- 不推、不编、不动版本记录空间；TV/leanback、BaseLoader 不动。
