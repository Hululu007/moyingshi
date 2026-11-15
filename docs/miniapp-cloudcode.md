# 海阔小程序第 7 步之四：云口令导入全分流（云 1~10）

> Recovery anchor（任务 `miniapp-cloudcode`，按仓库 AGENTS.md §5）
>
> - 目标：管理页加“云口令导入”入口，贴入 `云N oooole/...` 口令（支持整段
>   分享文本、多行多条），按云号分流取回规则 JSON，走第 2 步已有的
>   `importFiles/importOne` 链路落盘并登记；格式错不发请求；取回失败 /
>   规则不合格有明确提示、不崩。本地做完、本地提交，不推、不编、不触发
>   构建、不动版本记录空间。
> - 分支/HEAD：`main` / `6d12bde1`（第 7 步之三子页面 + require，本地超前
>   origin 三笔，未推）；开始前 `git status` 干净，无与本步无关的脏文件。
> - 计划状态：第 1–6 步 + hk 修复（025212ce）+ 第 7 步之一搜索（4efe5fb9）
>   + 之二导出（40de793a）+ 之三子页面/require（6d12bde1）已在 main 上；
>   本步 = 设计评审（本文档）+ 实现，见下文“实现清单”。
> - 当前文件/符号：`api/hk/HkCloudExport`（云 2 上传 `uploadTextDb`，口令
>   `云2oooole/apidb/<key>`）/ `api/hk/HkStore.importJsonText`（粘贴 JSON
>   → 写缓存文件 → `importFiles` → `importOne` → `importJsonFile`，
>   内含 `HkRule.validate()`）/ `ui/hk/HkMiniappActivity.kt`（四按钮 +
>   已导入列表，`runExport/runSync/showPasteDialog` 均为主线程回调 +
>   后台线程模式）/ `lab_strings.xml`（hk_miniapp_* 字符串）。
> - 已验证：本地逐行重读 + `git diff --check`；GitHub 构建（编译检查）未做，
>   本次不推不编，按任务要求留待用户说“推”之后。
> - 未验证（需要一次构建 + 真机 + 三条验收规则）：管理页云口令导入真机
>   流程、云 2 自闭环（导出的口令再导回来）、云 5 / pasteme 系实测取回、
>   格式错 / 取回失败 / 规则不合格的提示。
> - 未解决的风险：见下文“已知缺口与风险”（最大一项：`i22.H()` 本体未反
>   编译成功，云 1/3/4/6~10 走 pasteme 系是报告归纳、不是逐路证实）。
> - 下一个动作：guard finish（本地提交，不推不编）→ 向父代理汇报；
>   用户说“推”后推上去走 GitHub 构建，再拿三条验收规则真机验。

## 目标与范围

- 做：管理页（`HkMiniappActivity`）加“云口令导入”按钮 + 输入框（多行，
  每行一条，也支持直接粘一整段分享文本；自动读剪贴板预提取口令填进去，
  与魔影 `bc0` case 2 同款行为）；按口令里的云号分流取回规则 JSON，
  走 `HkStore.importJsonText`（第 2 步的 `importFiles/importOne` 链路）
  落盘并登记；取回的 JSON 走正常规则校验（`HkRule.validate()`，在
  `importJsonFile` 里已做，不合格抛错、报规则错）。
- 分流：云 2 textdb、云 5 cmd.im、其余云 1/3/4/6~10 走 pasteme 系。
- 不做：云口令上传（第 6 步之二已做云 2 上传；云 5 上传 `i22.i()` 可读但
  不在本步，导出仍只走云 2）、首页内嵌、`webRule://`、TV/leanback、
  `BaseLoader`、底栏（`menu_nav.xml` 仍 6 项）、￥ 旧格式规则导入（`i22.S()`
  的 `home_rule` 文本格式，第 2 步已明确不做；取回内容若是 ￥ 格式，
  `HkCloudImport.normalizePasteme` 会按 `i22.S()` 把其中 JSON 段抽出，
  抽不出则报规则错——不新增 ￥ 解析器）。
- 不变的行为：管理页四按钮（文件/目录、粘贴 JSON、同步、导出）、已导入
  列表、点行进列表页、行末删除彻底卸载、搜索/列表/详情/播放/导出一行不动。
  只改手机端。
- 安全声明（任务文档 + 输入框下方提示 + 导入成功提示里写清楚）：
  **云口令只是公开短链、不是加密**，任何人拿到口令都能取回同一份规则；
  不要用云口令传带账号密码的规则。取回内容要经过 textdb / cmd.im /
  pasteme 等第三方服务，发送方和接收方都看得到（报告 §5 同款结论）。

## 最佳实践研究（AGENTS.md §7 设计门）

### 证据表

| 证据 | 来源 | 等级 | 支撑的结论 | 适用性/注意 |
|---|---|---|---|---|
| 逆向报告 §2.3 云口令导入流程 | `~/workspace/user/files/__________8_3azg.md` L161–164 | 高（反编译源码） | 弹输入框（提示“每行一条，例：云6oooole/xxxxxx/yyy\n直接粘一整段分享文本也可以”，自动读剪贴板、`extractCloudCodes()` 预提取）→ `extractCloudCodes(文本)` → 后台线程 `ty1(codes, yw2, 1)`（`"hk-cloud"`）→ 每条 `i22.H(口令)` 取回 → `i22.O(g22, json)` 落盘 → `addSite()` → `eg.f(sites)` → `warmUp()` | 本步照此顺序：对话框 + 剪贴板预提取 + 后台线程 + 逐条取回 + 落盘登记；`warmUp()` 咱们的 `HkStore` 里还没有（第 2 步未做），本步不补，见“已知缺口” |
| 逆向报告 §2.4 云口令分流表 | 同上 L173–190 | 高（归纳句）/ 中（分流细节） | 口令格式 `云<1~10>oooole/<路径>`；云 2 = textdb、云 5 = cmd.im、其余走 pasteme 系；`i22.H()` 本体未反编译成功，具体分流表无法完整还原 | 分流方向（2→textdb、5→cmd.im、其余→pasteme）按此表；**逐路的取回细节见下面三行** |
| 逆向报告 §7.11 `i22.c/d` 正则 | 同上 L5881–5882 | 高（反编译源码） | `c = ^云([1-9]\|10)oooole(/.*)$`（校验整条口令）；`d = 云([1-9]\|10)oooole/?\S+`（从一段文本里提取口令） | 本步 `HkCloudImport` 原样照搬两个正则；`extractCloudCodes` 照搬 `HkSiteAdapter` L824–838（`d` 提取 + `G()` 规范化 + 去重） |
| 逆向报告 §7.11 `i22.G(String)` | 同上 L5929–5937 | 高（反编译源码） | 口令在 `oooole` 后缺 `/` 时补一个 `/`（`云6oooolexxx` → `云6oooole/xxx`） | `extractCloudCodes` 里照搬 |
| 逆向报告 §7.11 `i22.h()` 云 2 上传 | 同上 L6408–6445 | 高（反编译源码） | key = `"xc" + epoch秒 + "_" + abs(title.hashCode())`；value = 规则 JSON UTF-8 的 Base64（NO_WRAP）；POST `https://api.textdb.online/update/`；返回 `云2oooole/apidb/<key>` | 第 6 步之二 `HkCloudExport.uploadTextDb` 已直译；本步取回侧与之对称：口令里的 `apidb/<key>` 去前缀得 key，value 是 Base64，要解码 |
| textdb.online 官方 API 文档 | https://api.textdb.online/（2026-11-15 打开核对） | 高（官方文档） | 读取：`GET https://textdb.online/<key>`，返回纯文本（`text/plain`），始终 200，不存在则内容为空；创建/更新：`GET/POST https://textdb.online/update/?key=...&value=...`；记录 30 天未访问/更新自动清理；无密码保护 | 云 2 取回端点**不在逆向报告里**（报告只有 `update/` 上传端），按官方文档补齐；上传用 `api.textdb.online/update/`、读取用 `textdb.online/<key>`，两个域名都是官方文档写的 |
| 逆向报告 §7.11 `i22.r()` 云 5 取回 | 同上 L6686–6720 | 高（反编译源码） | 先 `d` 提取、`c` 校验，云号为 5 时取 path 去掉首个 `/` 后按 `[\s/]` 切第一段作 id；`GET https://cmd.im/<id>`，UA `Mozilla/5.0`；解析 `class="test_box"[^>]*>(.*?)</div>`（DOTALL），去标签、反转义 HTML 实体（`&amp; &lt; &gt; &quot; &#39;`）；空则 null；异常吞掉返回 null | 本步 `fetchCmdIm` 按此逐行直译；唯一改动：失败不吞异常，抛带原因的 `IllegalArgumentException`（调用方要给用户明确提示，魔影的吞异常是 UI 侧只报“导入失败”的根因之一） |
| 逆向报告 §7.11 `i22.s()` pasteme 取回 | 同上 L6721–6745 | 高（反编译源码） | `GET https://pasteme.tyrantg.com/api/getContent/<id>`，header `referer: https://pasteme.tyrantg.com/`、`cookie: `（空）、UA `Mozilla/5.0`；成功时把 body 交给 `o()` 解析 | 本步 `fetchPasteme` 按此直译；id 的取法见下一行 |
| 逆向报告 §7.11 `i22.o()/T()/S()` 内容规范化 | 同上 L6615–6633（`o`）、L6197–6220（`T`）、L6164–6196（`S`） | 高（反编译源码） | `o()`：响应 JSON 里 `return_code` 缺失或为 0 且 `data` 非空 → 取 `data` 字符串 → `T()`；`T()`：先 `S()`（纯 JSON 且含 `col_type`+`find_rule` 直接返回；含 `￥` 且第二段是 `home_rule`/`home_rule_v2` 时取第 3 段起的 JSON），否则处理 `base64://...@...@<base64>`（解码后含 `{`/`}` 才认）；都不中返回 null | 本步 `normalizePasteme` 按 `o()+T()+S()` 直译（`e()` 是 Base64 解码，见 L6371；`￥` 段只抽 JSON、不新增旧格式解析器） |
| 逆向报告 §7.7 `bc0` 云口令对话框 | 同上 L4290–4410 | 高（反编译源码） | 无口令可提取 → toast“没有识别到云口令（需 云1~云10 开头）”，不发请求；有口令 → 新线程 `"hk-cloud"` 跑导入 | 本步提示文案与线程名照搬；格式错（零条提取）时不发请求 |
| 逆向报告 §7.1 `HkSiteAdapter.importCloudCodes` | 同上 L862–892 | 高（反编译源码） | 逐条 `H(str.trim)` → `H` 返回 `h22{g22, json}` → `O(g22, json)` 落盘 → `addSite()`；单条失败记 `lastError`、记日志、继续下一条；最后 `eg.f(sites)` 写回 + `warmUp()` | 本步 `importCloudCodes` 照此结构：逐条取回 → `HkStore.importJsonText`（含落盘 + 登记）→ 单条失败记 `lastError` 继续；多条时成功数与首条失败原因一起报 |
| 仓库现有实现：`HkStore.importJsonText` | `api/hk/HkStore.java:565` | 高（本地代码） | 写 `cache/hk_import/hk_paste_<时间>.json` → `importFiles` → `importOne` → `importJsonFile`（`HkRule.parse` + `validate()`，不合格抛错；JSON 数组逐条）→ `saveAndRegister`（落盘 + `LocalSource.mergeSites` 登记） | 取回的 JSON 直接走这条链，不另写落盘/登记逻辑；`importJsonText` 返回 0 时 `HkStore.lastError()` 里有原因 |
| 仓库现有实现：`HkCloudExport` | `api/hk/HkCloudExport.java` | 高（本地代码） | 云 2 上传（`i22.h()` 直译）；独立 `OkHttpClient`（connect 15s / read 20s）；`FormBody` + UA `Mozilla/5.0` | 取回侧复用同一套超时/UA 约定；不改 `HkCloudExport`（上传与取回分属两个类，与魔影 `h/i` 上传、`r/s` 取回分开的写法一致） |
| 仓库现有实现：管理页对话框模式 | `ui/hk/HkMiniappActivity.kt:showPasteDialog/runExport` | 高（本地代码） | `WebHtvAlertDialogBuilder` + `AppCompatEditText` + `setOnShowListener` 改写确定按钮（先校验再关框）；后台 `Thread` + `runOnUiThread` 回调；导出中按钮禁用防重复点击 | 云口令对话框照同一模式；确定按钮先 `extractCloudCodes`，零条时 toast 不关框、不发请求 |

### 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 管理页保持四按钮、无云口令导入 | 零风险 | 第 6 步之二导出的口令导不回来，自闭环断在接收方；用户 2026-11-15 18:07 亲口说“后边的工程一次做完”；第 7 步之四就是这一项 | 否决 |
| B. 照搬上游 | `i22.H()` 全套直译 | 与魔影行为最接近 | `H()` 本体 1141 条指令被跳过、反编译失败，**没有源码可照搬**；只能按周边 `r()/s()` + 文档拼，拼出来的还是方案 C，改名而已 | 不可行（无源码） |
| C. 窄化适配（推荐） | 新 `HkCloudImport`：`extractCloudCodes`（`c/d/G` 直译）+ `fetch` 分流（云 2 按官方文档取回 + Base64 解码、云 5 按 `r()` 直译、其余按 `s()+o()+T()` 直译，id 取整段 path）+ `importCloudCodes` 逐条走 `HkStore.importJsonText`；管理页加按钮 + 对话框（剪贴板预提取、零条不发请求） | 改动集中在 mobile 一个新文件 + 管理页 + 布局一行 + 4 条字符串；云 2/云 5 有可读源码或官方文档背书；其余 8 路共用 `s()` 这一条可读方法；失败逐条报原因、不崩；不碰 `BaseLoader`、搜索/列表/详情/播放/导出、TV、底栏 | 云 1/3/4/6~10 各路的“是否都走同一条 pasteme 路径、id 是否含多段”无法从 `H()` 证实（证据等级见分流表）；`warmUp()` 未接（`HkStore` 里没有）；textdb 30 天清理、pasteme/cmd.im 服务不可控 | **采用** |
| D. 只做云 2 + 云 5 | 分流表只实现有确凿证据的两路，其余云号报“暂不支持” | 证据最干净，零外推 | 任务明确要求“云口令导入全分流（云 1~10）”；其余 8 路的取回方法 `s()` 本身是可读的，只是不确定分流归属——报不支持等于把已知的取回能力藏起来；魔影分享文本示例就是 `云6oooole/...`，最常见的恰恰在“其余”里 | 否决（退化为半个功能；C 的外推部分已在文档里逐路标明证据等级） |

### 推荐

采用方案 C。理由：云 2 取回有官方文档（读取端点 + 30 天清理 + 空内容语义）
与上传端对称，云 5 取回有 `r()` 全文可读，pasteme 取回有 `s()+o()+T()`
全文可读——三路取回方法本身都是高证据等级，只有“`H()` 把哪几朵云分给
` s()`”这一层是中证据（报告归纳句 + `H()` 缺失），已在分流表里逐路标明，
不把推断写成定论；入口与落盘登记全部复用第 2 步已验证的链路；回滚 = 删新
文件 + 恢复按钮，搜索/列表/详情/播放/导出零牵连。

### 分流表（逐路证据等级）

| 云号 | 服务 | 取回做法 | 证据等级 | 依据 |
|---|---|---|---|---|
| 云 2 | textdb | 口令 `云2oooole/apidb/<key>` → 去 `apidb/` 前缀得 key → `GET https://textdb.online/<key>`（纯文本，200，空 = 不存在/已清理）→ 内容是 Base64 → 解码得规则 JSON | 高（上传 `h()` 可读 + 官方文档取回端点） | 报告 L6408–6445；textdb.online 官网 API 文档（2026-11-15 核对） |
| 云 5 | cmd.im | 口令 path 去首个 `/` 后按 `[\s/]` 切第一段作 id → `GET https://cmd.im/<id>` → `class="test_box"` div 内容，去标签、反转义 HTML 实体 | 高（`r()` 全文可读） | 报告 L6686–6720 |
| 云 1 | pasteme | id = path 去首个 `/` 后的整段（如 `xxxxxx/yyy` 原样传）→ `GET https://pasteme.tyrantg.com/api/getContent/<id>` → `o()+T()` 规范化 | 中（取回方法 `s()` 可读；“云 1 走 pasteme”是报告归纳，未逐路证实） | 报告 L6721–6745、L6615–6633、L6197–6220；分流归属见 L182–188 |
| 云 3 | pasteme | 同上 | 中 | 同上 |
| 云 4 | pasteme | 同上 | 中 | 同上 |
| 云 6 | pasteme | 同上（魔影分享示例就是 `云6oooole/xxxxxx/yyy`，两段 path 原样传给 `getContent/`） | 中 | 同上；示例见报告 L162 |
| 云 7 | pasteme | 同上 | 中 | 同上 |
| 云 8 | pasteme | 同上 | 中 | 同上 |
| 云 9 | pasteme | 同上 | 中 | 同上 |
| 云 10 | pasteme | 同上（正则 `([1-9]\|10)` 含 10，`extractCloudCodes` 与 `c` 校验都认 `云10`） | 中 | 同上；正则见报告 L5881–5882 |

外推说明（诚实标注）：`H()` 本体缺失，所以“其余 8 路是否都走同一条
`https://pasteme.tyrantg.com/api/getContent/<id>`、id 是否含多段 path”无法
证实。本步的实现选择是：8 路共用 `s()` 的唯一可读形态（id = 整段 path，
含 `/` 原样拼接，与 `s(str)` 的 `concat(str)` 一致）；如果真机实测某路
404 / 取回内容不对，修法是按那一路的真实口令形态调整 id 截取（只改
`fetchPasteme` 的 id 取法，不动分流表与对话框）。

## 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-cloudcode.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkCloudImport.java` | 新建 | 云口令提取 + 全分流取回 + 逐条导入（`importCloudCodes`） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt` | 改 | 加“云口令导入”按钮 + 对话框（剪贴板预提取、零条不发请求、导入中禁用按钮） |
| `app/src/mobile/res/layout/activity_hk_miniapp.xml` | 改 | 加第 5 个按钮 `btnCloudCode`（同款 outlined MaterialButton，导出按钮之下） |
| `app/src/main/res/values/lab_strings.xml` | 改 | 加 4 条字符串（见下） |

### 关键契约

- **提取**（`HkCloudImport.extractCloudCodes(String text)`，纯函数、无网络，
  照搬 `HkSiteAdapter.extractCloudCodes` + `i22.c/d/G`）：
  1. 文本空 → 空列表。
  2. `d = 云([1-9]|10)oooole/?\S+` 全文找；每条过 `G()`（`oooole` 后缺 `/`
     补 `/`）；去重保序。
  3. 返回的每条都满足 `c = ^云([1-9]|10)oooole(/.*)$`（`G()` 保证了 `/`，
     `d` 保证了前缀；`fetch` 里仍会再校验一次，防御直接调 `fetch` 的调用方）。
- **取回**（`HkCloudImport.fetch(String code)`，后台线程调用，失败抛
  `IllegalArgumentException` 带真实原因，不返回 null、不吞异常）：
  1. `c` 校验不过 → `"云口令格式不对：<口令前 20 字>…"`（不发请求）。
  2. 云号 = group(1)，path = group(2)（以 `/` 开头）。
  3. 云 2：path 须以 `/apidb/` 开头，否则 `"云2口令格式不对（应为 云2oooole/apidb/<key>）"`；
     key = 去前缀后按 `[\s/]` 切第一段；`GET https://textdb.online/<key>`
     （UA `Mozilla/5.0`，connect 15s / read 20s）；非 2xx → `"取回失败（HTTP <码>）"`；
     body 空白 → `"云口令对应的内容不存在或已被清理（textdb 超过 30 天未访问会自动清理）"`；
     body 是 Base64（上传时写的就是 Base64）→ `Base64.NO_WRAP` 解码成 UTF-8；
     解码失败且 body 以 `{` 开头 → 直接当 JSON 用（防御 textdb 里存的就是原文）；
     解码失败且不是 JSON → `"取回的内容不是规则 JSON"`；解出后 trim，以 `{` 开头
     才返回，否则 `"取回的内容不是规则 JSON"`。
  4. 云 5：id = path 去首个 `/` 后按 `[\s/]` 切第一段（`r()` 直译）；空 →
     `"云5口令格式不对"`；`GET https://cmd.im/<id>`（UA 同上，15s/20s）；
     非 2xx → `"取回失败（HTTP <码>）"`；body 里找
     `class="test_box"[^>]*>(.*?)</div>`（DOTALL）；找不到 → `"云口令对应的内容为空或已失效"`；
     找到后去标签、反转义 `&amp; &lt; &gt; &quot; &#39;`（`r()` 直译）；
     结果空白 → 同上句；返回前 trim。
  5. 其余（1/3/4/6/7/8/9/10）：id = path 去首个 `/` 后的整段（`s()` 直译：
     `getContent/`.concat(id)，多段 path 原样传）；空 → `"云口令格式不对"`；
     `GET https://pasteme.tyrantg.com/api/getContent/<id>`，header
     `referer: https://pasteme.tyrantg.com/`、`cookie: `（空串，`s()` 原样）、
     UA `Mozilla/5.0`，15s/20s；非 2xx → `"取回失败（HTTP <码>）"`；
     body 交给 `normalizePasteme`（`o()+T()+S()` 直译，见下）；返回 null →
     `"云口令对应的内容为空或已失效"`。
  6. 网络异常（超时/断网/DNS）直接抛，调用方展示 `e.getMessage()`。
- **内容规范化**（`normalizePasteme(String body)`，`o()+T()+S()` 直译）：
  1. body 解析成 JsonObject；`return_code` 缺失或为 0、且 `data` 非空 →
     取 `data` 字符串 trim；否则返回 null。
  2. `S()`：以 `{` 开头且含 `col_type` + `find_rule` → 直接返回；含 `￥` 且
     第二段是 `home_rule`/`home_rule_v2` → 取第 3 段起拼回的串，`{` 开头且含
     `col_type` 才返回，否则 null。
  3. `base64://`：取 `base64://` 之后按 `@` 切，≥3 段时解码第 3 段（去空白，
     Base64 解码），解出含 `{` 和 `}` 才返回，否则 null。
  4. 以上都不中 → 返回 null（调用方报“内容为空或已失效”）。
  5. 注意：`S()` 的纯 JSON 分支要求含 `col_type` + `find_rule`；但 `data`
     本身就是完整规则 JSON 时（最常见），第 1 步取 `data` 后先判断：trim 后
     以 `{` 开头 → 直接返回（不要求 `col_type`，校验交给下游 `validate()`，
     缺字段时报的才是规则错、不是取回错）。这是对 `o()` 返回 `T(strTrim)`
     的忠实实现：`T()` 先调 `S()`，`S()` 的纯 JSON 分支返回整串；本步把
     “纯 JSON 直接返回”放在 `S()` 之前，与 `T()` 的行为一致（`S()` 含
     `col_type` 的判断只影响它是否“认”这串 JSON，不影响 `T()` 对普通 JSON
     的返回——`S()` 返回 null 时 `T()` 才会走 `base64://` 分支；普通 JSON
     无 `base64://` 时 `T()` 返回 `strTrim` 本身，见报告 L6197–6220：
     `if (iIndexOf < 0) return strTrim;`）。实现时按 `T()` 原样写：先 `S()`，
     `S()` 非空则返回；否则含 `base64://` 则解码分支；否则返回 trim 后的原串。
- **逐条导入**（`HkCloudImport.importCloudCodes(List<String> codes)`，后台线程
  调用，照搬 `HkSiteAdapter.importCloudCodes` 结构）：
  1. 空列表返回 0（调用方已在对话框里拦过零条，这里是防御）。
  2. 逐条：`fetch(code)` → `HkStore.importJsonText(json)`（第 2 步链路：
     写缓存 → `importFiles` → `importOne` → `importJsonFile` →
     `HkRule.validate()` + 落盘 + 登记）；`importJsonText` 返回 0 →
     失败，原因取 `HkStore.lastError()`；`fetch` 抛错 → 原因取 `e.getMessage()`。
  3. 单条失败记 `lastError`（最后一条失败的原因）、记日志、继续下一条，
     不中断、不崩；成功数累加返回。
  4. 不调 `warmUp()`（`HkStore` 里没有；魔影调它是为了预热前 5 条规则，
     列表页打开时自然会加载，缺失不影响导入正确性）。
- **管理页对话框**（`HkMiniappActivity.showCloudCodeDialog()`，照搬 `bc0`
  case 2 + 本页 `showPasteDialog` 的模式）：
  1. `AppCompatEditText` 多行（min 5 / max 12，12sp，等宽字体），hint =
     `hk_miniapp_cloud_code_hint`（“每行一条，例：云6oooole/xxxxxx/yyy\n
     直接粘一整段分享文本也可以”，`bc0` 同款文案）。
  2. 打开时读剪贴板（try/catch 包住，与 `bc0` 一致）：有内容则
     `extractCloudCodes` 预提取，非空时把口令逐行填进输入框。
  3. 点“导入”：先 `extractCloudCodes(输入)`；零条 → toast
     `hk_miniapp_cloud_code_none`（“没有识别到云口令（需 云1~云10 开头）”，
     `bc0` 同款文案），**不关框、不发请求**；非空 → 关框、禁用按钮、
     后台线程 `importCloudCodes` → 主线程收尾：全部成功报
     `onImported(added)`（“已导入 N 条小程序”+ 重载配置 + 刷新列表）；
     部分成功报 `hk_miniapp_cloud_code_partial`（成功数 + 最后一条失败原因，
     同样重载配置 + 刷新列表）；0 条成功报 `hk_miniapp_import_error` +
     `HkCloudImport.lastError()`。
  4. 输入框下方用对话框的 `setMessage` 补一句公开短链提醒（与导出分享文本
     首行同一句话：云口令是公开短链，不是加密，拿到口令的人都能取回这条规则。
     ——复用 `hk_miniapp_export_share_note` 字符串，不新增）。
  5. 对话框用 `WebHtvAlertDialogBuilder`（与粘贴对话框同一构建器，自带不透明
     背景；不改窗口尺寸、不调 `setHeight`，按 skill 的 dialog 教训）。
- **字符串**（`lab_strings.xml`，main 源集，新增 4 条）：
  `hk_miniapp_cloud_code`（云口令导入）、
  `hk_miniapp_cloud_code_hint`（每行一条，例：云6oooole/xxxxxx/yyy\n直接粘一整段分享文本也可以）、
  `hk_miniapp_cloud_code_none`（没有识别到云口令（需 云1~云10 开头））、
  `hk_miniapp_cloud_code_partial`（已导入 %1$d 条，部分失败：%2$s）。

### 不做的（明确边界）

- 云 5 上传（`i22.i()` 可读，但任务定导入；导出仍只走云 2）、多条规则打包
  成一个口令、￥ 旧格式规则的完整支持（只抽其中 JSON 段）、`warmUp()`、
  首页内嵌、`webRule://`、TV/leanback、`BaseLoader`、底栏。

## 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. `HkCloudImport` 只在 mobile 源集；main 源集只动 `lab_strings.xml` 的
   4 条新增字符串；`BaseLoader` 未动；TV/leanback 源集零改动；
   `menu_nav.xml` 仍 6 项。
2. 正则与 `i22.c/d` 一字不差；`G()` 补 `/` 逻辑与报告一致；云 5 取回与
   `r()` 逐行对齐（id 截取、`test_box` 正则、去标签、5 个 HTML 实体反转义）；
   pasteme 取回与 `s()+o()+T()` 对齐（URL、三个 header、return_code/data、
   `S()`/`base64://` 分支）。
3. 云 2 取回与官方文档一致：读端点是 `textdb.online/<key>`（不是
   `api.textdb.online/update/`）、key 去 `apidb/` 前缀、value 是 Base64
   要解码、空内容对应“不存在/已清理”。
4. 格式错（零条提取）不发请求、不开线程、不关框，有明确 toast；
   取回失败 / 规则不合格逐条报真实原因、不崩、不中断后续口令；
   导入中按钮禁用防重复点击。
5. 无 `Json.safeListElement` 式返回类型误用、无泛型原始类型；对话框用
   `WebHtvAlertDialogBuilder`（自带不透明背景），不改窗口尺寸；布局只加
   一个同款按钮，根布局不动。
6. 取回的 JSON 走 `HkStore.importJsonText`（第 2 步链路），`validate()`
   不合格报规则错；落盘 + 登记与文件/粘贴导入同一条路。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入）：

7. 云 2 自闭环：管理页导出奇优影院♔得口令 `云2oooole/apidb/xc…`，再用
   “云口令导入”贴回同一条口令 → 提示已导入 1 条，列表里仍是同一条规则
   （重复导入不产生重复条目：`mergeSites` 同 key 替换）。
8. 云 5 / pasteme 实测：拿一条真实云 5 口令、一条真实云 6 口令各导一次，
   取回成功、列表里出现对应规则；失败时 toast 里是真实原因（HTTP 码 /
   内容为空 / 规则缺少字段），不崩。
9. 格式错：输入 `云11oooole/xxx`、`hello`、空输入 → toast“没有识别到云口令
   （需 云1~云10 开头）”，无网络请求、不崩；输入整段分享文本（含标题 +
   口令混排）→ 只提取出口令导入。
10. 搜索/列表/详情/播放/导出零回归：管理页多一个按钮不影响其余四按钮、
    点行进列表页、行末删除、导入/同步；其余页面照旧。

## 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（`HkCloudImport.java`、任务文档）随 revert 一起消失；管理页恢复
  成四按钮；`lab_strings.xml` 的 4 条新增字符串一起回去——注意：回滚后
  云口令导入整体消失，搜索/列表/详情/播放/导出不受影响（它们不走这条链路；
  导出的口令仍可用文件/粘贴方式导回，只是没了口令一键导入）。
- 局部回滚：只删 `btnCloudCode` 按钮与 `showCloudCodeDialog` 接线：
  管理页恢复原样，`HkCloudImport` 变成不可达代码，下次构建不受影响。

## 已知缺口与风险

- **最大证据缺口：`H()` 本体缺失**。云 1/3/4/6~10 走 pasteme 系是报告
  §2.4 的归纳句，不是逐路源码；`H()` 内部是否对某些云号用不同域名/参数
  （例如有的云号走另一 pasteme 域名、或 id 截取方式不同）无法证实。
  本步按 `s()` 的唯一可读形态实现（id = 整段 path 原样拼接）；真机实测
  某路失败时，按那一路的真实口令形态只改 `fetchPasteme` 的 id 取法。
- **云 2 取回端点不在报告里**：报告只有 `update/` 上传端；取回端点
  `textdb.online/<key>` 来自官方文档（2026-11-15 核对），不是报告原文。
  若官方文档与魔影实际行为有出入（例如魔影取回走 `api.textdb.online`
  的另一路径），以真机实测为准调整。
- **第三方服务不可控**：textdb / cmd.im / pasteme 在线与否、限流与否不受控；
  textdb 记录 30 天未访问自动清理，旧口令可能取回空内容（有明确提示，
  不崩）。魔影同款风险（评估报告 §4.3 第 3 条）。
- **云口令不是加密**：任何人拿到口令都能取回同一份规则；不要用云口令传带
  账号密码的规则。对话框 message 与任务文档里都写清楚。
- **`warmUp()` 未接**：`HkStore` 里没有预热方法，导入后不预热；列表页打开时
  自然加载，不影响导入正确性。以后补预热时再接。
- **key 碰撞是魔影同款行为**：云 2 导出 key = `xc + epoch秒 + abs(hash)`，
  同一标题同一秒重复上传会覆盖同一 key；取回侧只认 key，不校验标题，
  覆盖后旧口令取回的是新内容（与魔影行为一致，本步不改）。
- **取回失败不重试**：魔影 `H()` 调用方也不重试；失败就报原因，用户可再点一次。
- **多条口令部分失败时只报首条失败原因**：`lastError` 记最后一条失败的
  原因；成功数照常报。用户可对失败的口令单独再导一次。

## 实现清单

- [x] 设计评审（本文档）
- [x] `HkCloudImport`：`extractCloudCodes` + `fetch`（云 2/云 5/pasteme）
  + `normalizePasteme` + `importCloudCodes` + `lastError`
- [x] `HkMiniappActivity`：云口令按钮 + `showCloudCodeDialog()`（剪贴板预提取、
  部分成功报 `hk_miniapp_cloud_code_partial`）
- [x] `activity_hk_miniapp.xml`：`btnCloudCode`
- [x] `lab_strings.xml`：4 条新字符串
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）

实现中纠正的（提交前）：

- 初版部分成功时只报成功数、失败原因只进日志；已改成部分成功时报
  `hk_miniapp_cloud_code_partial`（成功数 + 最后一条失败原因），并补
  `reloadLocalConfig()` + `refreshList()`，与全成功路径一致。
- 其余实现与设计一致，无需纠正：逐行重读未发现返回类型误用（`fetch` 返回
  `String`、无 `Json.safeListElement` 误用）、泛型原始类型（`List<String>`、
  `Map` 未直接用）、悬空 import、布局 id 与 binding 不一致；`Base64`
  宽容解码与报告 `i22.e()` 的 flag 顺序（DEFAULT/URL_SAFE/NO_WRAP）一致；
  `OkHttpClient` 超时 15s/20s 与 `i22.h()/i()` 一致；`BaseLoader`、
  TV/leanback、`menu_nav.xml` 未动。
