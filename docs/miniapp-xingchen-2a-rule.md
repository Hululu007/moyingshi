# 移植阶段 2a：HkRule 按星辰重写 + HkUaEnum / HkSettingConfig

> Recovery anchor：目标是把 hk 规则模型对齐星辰，只动 3 个 Java 文件 + 本文档；
> 验收是 3 条验收规则解析字段齐全、validate() 通过、getClassPairs() 数量与旧版
> classNames() 一致；不推、不编、不动版本记录空间。下一步由父代理验过后推与编。

## 对照来源

| 我方文件 | 星辰来源 | 说明 |
|---|---|---|
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRule.java`（重写，220 行 → 约 660 行） | `~/workspace/xingchen-ref/app/src/main/java/com/fongmi/android/tv/api/hk/HkRule.java`（656 行） | 包名 `com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`；其余为准 |
| `.../api/hk/HkUaEnum.java`（新建，56 行） | 星辰同目录 `HkUaEnum.java`（56 行） | 只改包名，全文照搬；构造器参数顺序 code/name/content 原样 |
| `.../api/hk/HkSettingConfig.java`（新建，17 行） | 星辰同目录 `HkSettingConfig.java`（17 行） | 只改包名，全文照搬；仅 `nowLoadRulesTime` 一位 |
| 移植计划 | `docs/miniapp-xingchen-port-plan.md` §阶段 2（L145–146）、文件映射 L34/L44/L54 | 本步是阶段 2 的 2a：只做规则模型，HkRuleManager/HkStore 是 2b |

星辰 HkRule 关键行号（供复核）：UA 常量 L20–21；`adBlockUrls` 字段 L102–108；
`transient enabled` L111、`baseUrl/chapterUrl` L117–118；`validate()` L121；
`resolvedUa()` L132、`hasSearch()` L146、`getClassPairs()` L151；`getTitle()` 脏数据处理 L184；
`getVersion()` int L204、`compareVersions()` L219、`VersionAdapter` L251；
`setVersion(int)` L275、`getVersionRaw()` L280、`getVersionLabel()` L285、`setVersionRaw()` L291；
`clearNav()` L386、`deriveSubRule()` L402、`copyRule()` L447；
`getAdBlockUrls/setAdBlockUrls` L517–521；`getBaseUrl` L628、`getChapterUrl` L636、`isEnabled` L644。

## 合并清单

### 从星辰采用

- 全部字段与 `@SerializedName`，含新增的 `adBlockUrls`（L107–108）、
  `transient enabled=true`（L111）、`transient baseUrl/chapterUrl`（L117–118）。
- `version` 字段采用星辰的 `VersionAdapter`（数字/布尔/null 都收敛为 String，L251 起）；
  旧文件头“不需要 VersionAdapter”的注释已删（已过时）。
- 方法：`resolvedUa()`、`hasSearch()`、`getClassPairs()/getAreaPairs()/getYearPairs()/getSortPairs()`
  （按 `&` 配对、去重、url 可空）、`clearNav()`、`deriveSubRule()`、`copyRule()`、
  `compareVersions()`、`getVersion()` 返回 int、`getVersionRaw()/getVersionLabel()`、
  `setVersion(int)/setVersionRaw(String)`、`getAdBlockUrls/setAdBlockUrls`、
  `isEnabled/setEnabled`、`getBaseUrl/setBaseUrl`、`getChapterUrl/setChapterUrl`、
  `getTitle()` 的 `"undefined"`/`"null"` 脏数据处理、`isJsSearchRule()`、
  全部字段的 get/set（含新增的 `setAuthor`、`setClassUrl/setAreaUrl/setYearUrl/setSortUrl` 等）。
- `HkUaEnum`：AUTO/MOBILE/PC 三项与 `getByName/getByCode` 全文照搬，仅改包名。
- `HkSettingConfig`：全文照搬，仅改包名。

### 保留墨影视自有能力（星辰没有）

- `parse(String)`、`toJson()`（走 `App.gson()`）、`sourceJson` transient 字段 + get/set、
  `isSearchable()`、`classNames()/classUrls()/classUrlFor(String)`。
- `deriveSubRule()` 不继承 `sourceJson`：它是父规则的原始 JSON，派生规则的
  url/find_rule/colType 已不同，沿用会是过期内容（文件内有注释）。
- 旧版用 `TextUtils.isEmpty` 的地方统一改用星辰的私有 `isEmpty()` 助手，行为一致；
  `android.text.TextUtils` 导入随之去掉，无其他影响。

## 两处有意偏差（不要“顺手”改回）

1. **`validate()` 不抛错**：保留墨影视的 `String validate()` 签名与三条中文提示
   （“规则缺少 title” / “规则缺少 url” / “规则缺少 find_rule”，通过返回 null）。
   星辰 L121 的 `validate()` 是抛 `IllegalArgumentException` 的同语义版本。
   保留原因：调用方 HkStore（`importJsonFile` 等三处，L574/589/613）与
   HkExecutor（六处，L84/125/309/380/474/643）都用 `String err = rule.validate()` 签名。
2. **`getFindRule()` 保留 null→"" 归一**：星辰是直返 null（L 约 360 起有长注释说明官形）。
   保留原因：现有调用方 HkExecutor:98 `evalFindRule(rule.getFindRule())` 与 :103
   `parseHomeListDetailed(..., rule.getFindRule(), ...)` 都不判 null；改直返 null 会把
   NPE 风险带进现版执行链。阶段 4 换 HkSelector 时再复核。

其他相关点：

- `isJsFindRule()` 继续委托给 `HkParser.isJsRule`（`HkParser.java:55`，
  非空 + trim 后 `js:` 开头，与星辰的直接判断同语义）；不引用 `HkSelector`（阶段 4 才有）。
- `getVersion()` 从 String 改成 int：已核对，mobile 源集里没有任何调用方
  （grep `.getVersion()` 零命中），安全。旧版 `getVersion()` 返回 `""` 的行为随之消失，
  无调用方受影响。

## 未做的事

- `HkRuleManager` / `HkStore` 改写是 2b，不在本步。
- `HkSelector` 是阶段 4，不在本步；本步不引用它。
- 不引入 LitePal 或任何新依赖；不改 gradle；TV/leanback 零改动；BaseLoader 不动；
  底栏仍 6 项（`menu_nav.xml` 未碰）；不碰任何其他 hk 文件。
- 不推、不编、不触发构建、不动版本记录空间。推和编由父代理验过之后办。

## 验收证据（代码级；本地无 JDK/Android SDK，编译靠 GitHub 构建验）

- 3 条验收规则（`~/workspace/miniapp/test-rules/`）的原始字段：
  奇优影院♔（version 数字 3，class 5/5）、童话（version 数字 0，class 4/4）、
  臻品fix（version 数字 0，class 5/5，find_rule 为 `js:` 开头）。
  数字版 version 须经 `VersionAdapter` 收敛为 String 后 `getVersion()` 读回 3/0/0；
  `validate()` 三条均通过；`getClassPairs()` 数量与旧版 `classNames()` 一致（5/4/5）。
- grep 确认：3 个文件内无 `com.fongmi` 残留；`git status` 只显示本步 4 个文件；
  无 leanback 改动。
- 提交前已逐行重读 3 个文件（本地唯一检查手段）。

## 提交

- Guard 会话：`miniapp-xingchen-2a-rule`
- 提交信息：`feat(hk): 移植阶段 2a：HkRule 按星辰重写 + HkUaEnum/HkSettingConfig`
- 提交 hash 与 recovery tag：见 guard finish 输出（记录于此提交后由父代理补记）。
