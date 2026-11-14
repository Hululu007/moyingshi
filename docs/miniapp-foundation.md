# 小程序（海阔规则）地基验证 — 第 1 步

> Recovery anchor（任务 `miniapp-foundation`，按仓库 AGENTS.md §5）
>
> - 目标：证明海阔引擎这条路在墨影视里走得通（完整版 4–6 周的第 ① 步）。
>   验收 = Rhino 依赖 + 解释器模式引擎 + g22 规则模型 + i22 仓库基础 +
>   f22 首页 find_rule 执行骨架，全部在 mobile 源集，一个原子提交。
> - 分支/HEAD：`main` / `d172bbd0`（上游 beta 3990ef17 同步后）。
> - 计划状态：第 1 步已做完（本地，未推，未编）。下一步（第 2 步）：
>   `g22`+`i22` 补齐 + 导入（文件/粘贴 JSON/同步已装规则），再做列表页。
> - 当前文件/符号：
>   `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/` 下
>   `HkRule`（g22）、`HkCard`（uy1）、`HkStore`（i22 基础）、
>   `HkEngine`（Rhino 封装 + JS API 桥）、`HkParser`（b32 最小实现）、
>   `HkExecutor`（f22 首页骨架）；`gradle/libs.versions.toml`（rhino 1.7.14）、
>   `app/build.gradle`（implementation libs.rhino）。
> - 已验证：本地逐行重读 + `git diff --check`；GitHub 构建（编译检查）未做，
>   本次不推不编，按任务要求留待用户开口。
> - 未验证（需要一次构建 + 一条真实规则）：Rhino 1.7.14 在 R8 下能否正常
>   收缩/运行；find_rule 在真机上对真实规则的解析结果；POST/fyAll 等。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：等用户发 2–3 条常用影视类小程序规则（验收用），或用户说
>   “推”时推上去走 GitHub 构建验证编译。

## 做了什么（第 1 步的 5 点）

1. **Rhino 依赖**：`org.mozilla:rhino:1.7.14`（评估明确要求 1.7.14，不用
   1.8.0）。版本与 library 坐标进 `gradle/libs.versions.toml`，
   `app/build.gradle` 加 `implementation libs.rhino`（紧跟 jsoup 那行）。
   只加依赖，不动其它配置；`proguard-rules.pro` 不用改——仓库已有
   `-keep class com.github.moyingshi.osc.tv.** { *; }`，新 hk 类全在该包
   下自动 keep；解释器模式不加载 Rhino 的 Codegen（只有优化模式才反射
   加载它），R8 不会因此掉东西。若构建报 Rhino 相关问题再按报错加 keep。
2. **最小 Rhino 引擎封装**（`HkEngine`）：`HkContextFactory` 把
   `optimizationLevel` 固定为 -1（解释器模式，Android 上必须）。
   一次实例对应一次页面执行（preRule + find_rule 共用 scope），单线程用。
   注入的 JS API（`BOOTSTRAP`）覆盖列表解析真正用到的子集：输出
   （`setResult`/`setHomeResult`/`setSearchResult`/`setError`/`log`/`toast`）、
   请求（`fetch`/`post`/`getResCode`，同步，charset 按 Content-Type 猜）、
   DOM（`parseDom`/`pd`、`parseDomForHtml`/`pdfh`、`parseDomForArray`/`pdfa`）、
   编解码（`md5`/`base64Encode`/`base64Decode`）、变量
   （`putVar`/`getVar`/`putMyVar`/`getMyVar`/`getParam`）、域内变量
   （`MY_URL`/`MY_HOME`/`MY_RULE`/`MY_PAGE`/`MY_TYPE`/`MY_PARAMS`/
   `MOBILE_UA`/`PC_UA`）、`$` 工具（`$.toString`/`$.stringify`）。
   暂不支持的（`xpath`/`batchFetch`/`getCryptoJS`/`aesEncode`/`encodeStr`/
   `require`/`request` 等）在 BOOTSTRAP 里是会抛明确错误的桩。
   `setResult` 同时认 `img` 与 `pic_url`；JS 直接返回数组时也认（没调
   setResult 的兜底）。
3. **规则模型 g22**（`HkRule`）：按逆向报告 §3 / §7.9 的全部字段建
   （`@SerializedName` 原样保留），`validate()` 复刻 `a0()` 三条提示：
   缺 title →「规则缺少 title」；缺 url 且 find_rule 不是 JS →
   「规则缺少 url」；缺 find_rule →「规则缺少 find_rule」。
   `version` 字段直接用 String（Gson 的 `nextString()` 会把数字/布尔
   token 转字符串，不需要原版的自定义 TypeAdapter）。
4. **规则仓库 i22 基础**（`HkStore`）：`plugins/hk/` 读写——写规则 JSON
   （同标题换文件名时删旧文件）、按标题读、枚举（同标题取最后修改时间
   更新的那个；无 title 用文件名补）、`config/` 与 `data/<标题>/` 目录、
   `N(title)` 文件名归一化（非法字符 → `_`，超 80 截断，空 → unnamed）。
   读写用 `java.io` 流（minSdk 24，避开 `java.nio.file` 的 API 26 限制）。
   解析失败的文件在 `list()` 里跳过并记 Log（没写魔影的 `hk_rule_error.log`，
   列表页阶段再决定）。
5. **最小 find_rule 执行路径 f22 骨架**（`HkExecutor.home()` +
   `HkParser.parseHomeList()`）：校验 → 拼 URL（fy 占位符）→ OkHttp 抓页 →
   preRule 先跑 → `js:` 走 Rhino，否则走选择器解析，返回 `List<HkCard>`。
   URL 拼法：`[firstPage=N]`（第 1 页用 N）、`fypage@-1@*20@` 运算组
   （(page-1)*20）、`fyclass`/`fyarea`/`fyyear`/`fysort`、` ;get/POST/GBK/
   {headers}` 修饰符、编码按修饰符解码（默认 UTF-8）、headers 合并顺序
   firstHeader < 规则 ua < URL 修饰符。`class_url` 分两种形态：ID 式
   （模板是规则 url，fyclass 换 ID）与模板式（模板取对应项）。

## 选择器解析（b32 最小实现，`HkParser`）

按官方文档 + 社区 skill（`Djjywj/openhands-skills` 的 `js_api.md` /
`selector_syntax.md`，2026-11-14 抓取）重写，b32 本体无源码：

- 字段格式：`列表;标题;图片;描述;链接[;显示样式]`；`&&` 向下一层；
  `--` 排除第一个；`,n` 取第 n 个（0 起，负数倒数）；尾部 `Text`/`Html`
  取文字/inner HTML，其它尾部取属性（href/src 等 URL 属性自动补全）；
  `||` 或；`+` 拼接（单引号包字面量）；`.js:代码` 后处理（`input` 是原值）；
  `*` 用本行自己的链接/文字。
- 尾部是属性名还是标签名，靠“元素有没有这个属性”运行时判断（`pdfa` 同样
  按第一个匹配元素判断，避免把 `li`/`div` 误当成属性）。
- 列表项本身就是 `<a>` 时，字段里的 `a&&href` 也能命中（自身兜底）。
- `+` 只在“挨着引号”或“后一段含 `&&`”时切，避免误切 CSS 的 `div+p`。

## 已知缺口与风险（第 1 步故意没做 / 待真实规则验证）

- **没编过**：本地无 Android SDK；真正的编译检查只能等推上去走 GitHub 构建。
  但 Rhino API 用法已对 `org.mozilla:rhino:1.7.14` 的 sources jar 逐项核对过
  （`ContextFactory.enterContext()` / `setOptimizationLevel(-1)` /
  `initStandardObjects()` / `evaluateString` / `javaToJS` /
  `NativeJSON.stringify` / `NativeArray.get(int, Scriptable)` /
  `ScriptableObject.getIds()` 等均存在）；`RequestBody.create(byte[])`、
  `OkHttp.client(long)`、`OkHttp.toBody(ArrayMap)` 是仓库内已有用法；
  `java.util.Base64`（API 26+）已换成 `android.util.Base64`（minSdk 24 可用）。
- **POST 不支持**：`buildHomeRequest` 遇到 `;POST` 直接抛错（body 格式未核实，
  不静默发 GET）。首页 URL 绝大多数是 GET；搜索/详情阶段再核实 POST 体格式。
- **`fyAll` 未替换**：占位符含义未核实，留在 URL 里原样发出（会请求失败，
  失败信息里能看到）；筛选阶段核实。
- **`firstHeader` 格式是外推**：JSON 或 `key: value` 行两种都认，待真实规则验证。
- **字段级 `js:` 是外推**：整段 `js:` 有文档，字段级 `js:` 按同款语义实现
  （`input` = 本行 outer HTML），待真实规则验证。
- **`.js:` 代码里的 `;` 语句分隔符切不开**：字段按 `;` 切分（引号内保护），
  `.js:` 请写表达式，别写多语句。
- **XPath 不支持**：jsoup 没有 XPath 引擎；`xpath`/`xpa` 是抛错的桩。
- **`batchFetch`/`getCryptoJS`/`aesEncode`/`require` 等未实现**：同上，抛错的桩。
- **`encodeStr`/`decodeStr` 未实现**：语义（字符集转换方向）未核实，不猜。
- **`fetch` 的 `onlyHeaders`/`withHeaders`/`toHex` 等选项未实现**：只认
  headers/method/body/timeout。
- **没有做列表页/导入/搜索/详情/播放/云口令/导出**：那是第 2 步及以后的阶段。
- **规则是任意代码**：Rhino 里跑规则 JS 与魔影一致，安全结论同评估——
  别导来路不明的规则，尤其云口令。

## 资料来源

- 评估：`~/workspace/miniapp/ASSESSMENT.md`（实施顺序、两档工期、仓库核查）
- 逆向报告：`~/workspace/user/files/__________8_3azg.md`（§1–3、§6、§7.9 g22、
  §7.10 f22、§7.11 i22、§7.12 uy1、§7.18 w02）
- 官方文档：`https://docs.189.tyrantg.com/docs/hikerview/help_rules.html`
  （find_rule 格式、占位符、修饰符）
- 社区 skill：`Djjywj/openhands-skills` 的 `hikerview-rule-generator`
  （`js_api.md`、`selector_syntax.md`，2026-11-14 抓取）
- 仓库规矩：`~/workspace/custom-skills/mooyingshi_upkeep/SKILL.md`、
  仓库 `AGENTS.md`
