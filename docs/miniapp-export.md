# 海阔小程序第 6 步之二：导出基础版

> Recovery anchor（任务 `miniapp-export`，按仓库 AGENTS.md §5）
>
> - 目标：管理页勾选已导入的 hk 规则点“导出”→ 云 2 textdb 上传生成云口令
>   → 系统分享面板；未勾选 / 勾选的不是小程序 / 上传失败时有明确提示且
>   不崩；只改手机端，TV/BaseLoader/底栏不动。本地做完、本地提交，不推、
>   不编、不触发构建、不动版本记录空间。
> - 分支/HEAD：`main` / `4efe5fb9`（第 6 步之一搜索，本地超前 origin 一笔
>   025212ce，未推）；开始前 `git status` 干净，无与本步无关的脏文件。
> - 计划状态：第 1–5 步 + hk 修复（025212ce）+ 第 6 步之一搜索（4efe5fb9）
>   已在 main 上；本步 = 设计评审（本文档）+ 实现，见下文“实现清单”。
>   第 6 步另一半（搜索）已做完；云 5 cmd.im 不在本步（第 7 步云口令全
>   分流再做）。
> - 当前文件/符号：`api/hk/HkStore`（`readRaw/titleOf/isMiniapp/fileName` 已有，
>   无上传方法）/ `ui/hk/HkMiniappActivity.kt`（三按钮 + 已导入列表，
>   adapter `setCheckable(false)`）/ `ui/adapter/LocalSourceAdapter`
>   （`setCheckable/getSelected(): Set<String>/onSelectionChanged` 已有）/
>   `lab/LabActions.java:528`（现成 ACTION_SEND + createChooser 分享路子）/
>   `api/hk/HkExecutor.fetch`（OkHttp 5.4.0，`RequestBody.create(String,
>   MediaType)` 参数顺序已确认）。
> - 已验证：本地逐行重读 + `git diff --check`；GitHub 构建（编译检查）未做，
>   本次不推不编，按任务要求留待用户说“推”之后。
> - 未验证（需要一次构建 + 真机 + 三条验收规则）：管理页勾选导出真机流程、
>   textdb 上传实测、分享面板弹出、上传失败提示。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：guard finish（本地提交，不推不编）→ 向父代理汇报；
>   用户说“推”后推上去走 GitHub 构建，再拿三条验收规则真机验；
>   第 7 步长尾（云口令全分流、云 5 导出、内嵌、col_type 补齐等）等导出验完再做。

## 目标与范围

- 做：管理页（`HkMiniappActivity`）加“导出”按钮 + 开启列表多选框；勾选
  hk 规则 → 逐条走云 2 textdb 上传生成云口令（`云2oooole/apidb/<key>`）
  → 成功后走系统分享面板（`ACTION_SEND` + `createChooser`，复用
  `LabActions.java:528` 同款写法）；未勾选 / 勾选的不是小程序 / 规则文件
  读不到 / 上传失败时给明确提示，不崩、不假装成功。
- 不做：云 5 cmd.im 上传（`i22.i()` 可读，但任务定云 2；第 7 步全分流再做）、
  云口令导入（第 7 步）、多条规则打包成一个口令、导出普通本地源、首页内嵌、
  `webRule://`、TV/leanback、`BaseLoader`、底栏（`menu_nav.xml` 仍 6 项）。
- 不变的行为：管理页三按钮（文件/目录、粘贴 JSON、同步）、已导入列表、
  点行进列表页、行末删除彻底卸载、搜索/列表/详情/播放一行不动。只改手机端。
- 安全声明（任务文档 + 分享文本首行 + 成功提示里写清楚）：**导出是把规则
  公开给拿到口令的人；云口令只是公开短链，不是加密**，任何人拿到口令都能
  取回同一份规则；不要用云口令传带账号密码的规则。上传前不改规则内容，
  不往规则里写凭证。

## 最佳实践研究（AGENTS.md §7 设计门）

### 证据表

| 证据 | 来源 | 等级 | 支撑的结论 | 适用性/注意 |
|---|---|---|---|---|
| 逆向报告 §7.1 `HkSiteAdapter.export(activity, sites)` | `~/workspace/user/files/__________8_3azg.md` L804–824 | 高（反编译源码） | 空列表返回 false；取每条 `titleOf()` 去重 → `po1.l0(activity, 标题列表)` 分享面板 `bw1`（标题「导出：xxx」或「导出 N 个小程序」）→ `s54` case 25 处理分享：上传生成云口令 | 本步照此顺序：去重标题 → 上传 → 分享面板；`po1.l0/bw1/s54` 是魔影混淆类、无源码，用系统 `ACTION_SEND` + `createChooser` 窄化替代 |
| 逆向报告 §2.4 云口令分流表 | 同上 L173–190 | 高（反编译源码） | 云 2 = `https://api.textdb.online/update/`（POST key + value，value 是规则 JSON 的 Base64），生成 `云2oooole/apidb/<key>`；云 5 = cmd.im；其余走 pasteme 系 | 本步只做云 2；云 5 与其余分流是第 7 步 |
| 逆向报告 §7.11 `i22.h(标题)` 云 2 上传 | 同上 L6419–6445 | 高（反编译源码） | key = `"xc" + epoch秒 + "_" + Math.abs(title.hashCode())`；value = 规则 JSON UTF-8 字节的 Base64（flag 2 = NO_WRAP）；UA `Mozilla/5.0`；connect 15s / read 20s；响应 JSON `status==1` 才成功，返回 `云2oooole/apidb/<key>`；否则抛 `IllegalArgumentException("上传失败…")`（HTTP 码 / 服务器返回前 80 字） | 本步 `HkCloudExport.uploadTextDb` 按此逐行直译；失败信息原样带回 UI |
| 逆向报告 `fx2` case 5（本地源弹窗导出入口） | 同上 L8308–8330 | 高（反编译源码） | 未勾选 → “请先勾选要导出的小程序”；勾选的非小程序 → “勾选的源不是小程序，无法导出”；`export` 返回 false → “当前版本不支持导出小程序” | 本步三句提示文案照搬（第三句不适用：本步 export 一定可用，改为上传失败的真实原因） |
| 逆向报告 `i22.d(标题列表)` 导出标题 | 同上 L6270–6290 | 高（反编译源码） | 1 条 → 规范化标题 `N(title)`；多条 → `"N个小程序"`；规则文件都读不到 → 弹“规则文件都读不到，没有可导出的内容”返回 null | 分享面板标题照此：单条「导出：xxx」，多条「导出 N 个小程序」；文件读不到在上传前就报，不进分享 |
| 逆向报告 §5 安全边界 | 同上 L300–304 | 高（报告结论） | 云口令不是加密，只是短链，没有密码保护；规则内容经过第三方服务，发送方和接收方都看得到；不要用云口令传带账号密码的规则 | 见“目标与范围”的安全声明；分享文本首行同样提醒 |
| 仓库现有实现：`HkStore` | `api/hk/HkStore.java` | 高（本地代码） | `readRaw(title)` 读规则原文（不存在返回 null）、`fileFor/titleOf/isMiniapp`（`hk://` 判定与 `i94.w` 一致）、`fileName` 规范化 | 上传直接读 `readRaw` 的原文，不改内容、不重新序列化；标题去重用 `titleOf` |
| 仓库现有实现：`LocalSourceAdapter` | `ui/adapter/LocalSourceAdapter.java` | 高（本地代码） | `setCheckable(boolean)` 开关多选框；`getSelected(): Set<String>`（key 的集合副本）；`setItems` 时自动 `retainAll` 清掉已不存在的选中；`onSelectionChanged()` 回调 | 管理页把 `setCheckable(false)` 改成 `true` 即可勾选；选中 key 对到 `getItems()` 里的 Site 再按 `isMiniapp` 过滤 |
| 仓库现有实现：分享路子 | `lab/LabActions.java:528–531` | 高（本地代码） | `Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT, value); activity.startActivity(Intent.createChooser(share, "分享"))` | 本步复用同款写法，chooser 标题换成 `i22.d()` 式的导出标题 |
| 仓库现有实现：OkHttp 用法 | `api/hk/HkExecutor.java:632–650` | 高（本地代码） | `OkHttp.client()` 共享客户端；`RequestBody.create(String, MediaType)`（OkHttp 5.4.0 参数顺序：body 在前） | 上传用独立 `OkHttpClient`（connect 15s / read 20s，与 `i22.h()` 一致），不碰共享客户端的超时配置 |
| 仓库现有实现：管理页 | `ui/hk/HkMiniappActivity.kt` + `activity_hk_miniapp.xml` | 高（本地代码） | 三按钮 + 已导入列表（本地源里登记的 hk:// 站源）；点行进列表页；行末删除彻底卸载；导入/同步走后台线程 + 主线程回调 | 导出按钮加在同步按钮之下（同款 outlined MaterialButton）；上传走后台线程，分享与提示回主线程 |

### 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 管理页保持三按钮、无导出 | 零风险 | 第 6 步另一半就是导出；用户 2026-11-15 18:07 亲口说“后边的工程一次做完”；搜索已做完，导出是第 6 步收尾 | 否决 |
| B. 照搬上游 | `export()` → `po1.l0` 分享面板 `bw1` → `s54` case 25 全套直译 | 与魔影行为最接近 | `po1/bw1/s54` 全是混淆名、无源码，分享面板内部行为（先分享再上传还是先上传再分享、面板里选什么）只能猜；云 5 上传也要一起做才算“照搬”，超出本步范围；工作量数倍于本步 | 否决（云 5 留第 7 步） |
| C. 窄化适配（推荐） | 新 `HkCloudExport.uploadTextDb(title)`（`i22.h()` 直译）+ 管理页“导出”按钮 + 多选框（`setCheckable(true)`）+ 成功后系统 ShareSheet；未勾选/非小程序/读不到文件/上传失败均 toast 真实原因 | 改动集中在 mobile 两个新/改文件 + 布局一行 + 8 条字符串；上传逻辑与魔影 `i22.h()` 逐行对齐，生成的口令格式与魔影互通（接收方云口令导入是第 7 步）；不碰 `BaseLoader`、不碰搜索/列表/详情/播放、不碰 TV、不碰底栏 | 云 5 不做（第 7 步）；分享文本格式是咱们定的（报告 `s54` case 25 本体无源码，只有“上传并生成云口令”的结论）；多条导出是逐条上传、逐条口令，不是打包成一个口令 | **采用** |
| D. 只分享规则 JSON 文本，不上传 | 勾选后把规则原文直接 `ACTION_SEND` 出去 | 不用联网，零第三方服务依赖 | 与魔影云口令互通的目标背离，接收方无法一键导入；大规则 JSON（几十 KB）直接分享体验差；任务明确要求“云 2 textdb 上传生成云口令 + 系统分享” | 否决 |

### 推荐

采用方案 C。理由：上传逻辑有 `i22.h()` 全文可读的源码可逐行直译，口令
格式与魔影一致；入口复用管理页现有列表 + `LocalSourceAdapter` 现成的
多选能力，只改一行开关；分享复用项目里现成的 `ACTION_SEND` 路子；回滚
= 删新文件 + 恢复按钮与开关，搜索/列表/详情/播放零牵连。

## 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-export.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkCloudExport.java` | 新建 | 云 2 textdb 上传（`i22.h()` 直译）+ 分享文本拼装（面板标题用字符串资源） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt` | 改 | 加“导出”按钮 + 开启多选框 + 导出流程（后台上传 → 主线程分享/提示） |
| `app/src/mobile/res/layout/activity_hk_miniapp.xml` | 改 | 加第 4 个按钮 `btnExport`（同款 outlined MaterialButton，同步按钮之下） |
| `app/src/main/res/values/lab_strings.xml` | 改 | 加 8 条字符串（见下） |

### 关键契约

- **上传**（`HkCloudExport.uploadTextDb(String title)`，后台线程调用，
  失败抛异常、调用方展示原因，不返回 null、不假装成功）：
  1. 标题空 → `IllegalArgumentException("规则标题为空")`。
  2. `HkStore.readRaw(title)` 为 null → `IllegalArgumentException("规则不存在：" + title)`
     （与 `i22.h()` 的 `qi.m("规则不存在：" + str)` 同款文案；上传前不改
     规则内容，不往规则里写凭证）。
  3. value = `Base64.encodeToString(json.getBytes(UTF_8), Base64.NO_WRAP)`
     （报告 flag 2 = `NO_WRAP`，不换行）。
  4. key = `"xc" + System.currentTimeMillis() / 1000 + "_" + Math.abs(title.hashCode())`
     （与 `i22.h()` 一字不差；同一标题同一秒重复上传会覆盖同一 key，
     魔影同款行为，见“已知缺口”）。
  5. 独立 `OkHttpClient`（connect 15s / read 20s，与 `i22.h()` 一致）；
     POST `https://api.textdb.online/update/`，`FormBody`（key + value），
     header `User-Agent: Mozilla/5.0`。
  6. 非 2xx → `IllegalArgumentException("上传失败（HTTP " + code + "）")`；
     body 空 → `"上传失败：服务器无响应"`；`try (Response)` 关响应。
  7. 解析响应：`JsonParser.parseString(body)` 是 JsonObject 且
     `status == 1` → 返回 `"云2oooole/apidb/" + key`；否则
     `"上传失败：服务器返回异常（" + 前 80 字 + "）"`（与 `i22.h()` 同款
     截断逻辑）。解析本身抛错同样走这条，不崩。
  8. 网络异常（超时/断网/DNS）直接抛，调用方展示 `e.getMessage()`。
- **分享标题**（管理页里用字符串资源拼，`i22.d()` 对应物）：
  1 条 → `hk_miniapp_export_share_title_one`（“导出：%1$s”）；多条 →
  `hk_miniapp_export_share_title_many`（“导出 %1$d 个小程序”）。
- **分享文本**（`HkCloudExport.shareText(Map<String, String> codes)`，
  `LinkedHashMap` 保序，标题 → 云口令）：
  首行 = `hk_miniapp_export_share_note` 的文案（“云口令是公开短链，不是加密，
  拿到口令的人都能取回这条规则。”），随后每行一条 `"标题：云口令"`。
  分享文本格式是咱们定的（报告 `s54` case 25 本体无源码）；口令本身
  与魔影格式一致，接收方第 7 步云口令导入时能认。
- **管理页导出流程**（`HkMiniappActivity.runExport()`）：
  1. `selected = adapter.getSelected()` 为空 → toast
     `hk_miniapp_export_select_first`（“请先勾选要导出的小程序”，魔影
     `fx2` case 5 同款文案），直接返回。
  2. 在 `adapter.getItems()` 里取 key 在 selected 中的 Site，再按
     `HkStore.isMiniapp(site)` 过滤（列表里本来就全是 hk:// 站源，这层
     是防御：万一将来列表混进普通本地源，普通源绝不触发导出）；
     过滤后为空 → toast `hk_miniapp_export_not_miniapp`（“勾选的源不是
     小程序，无法导出”，魔影同款文案）。
  3. 标题去重（保序，`HkSiteAdapter.export` 同款逻辑）→ 后台线程逐条
     `uploadTextDb(titleOf)`；导出中 `btnExport.setEnabled(false)` 防
     重复点击，结束恢复。
  4. 主线程收尾：成功 ≥ 1 条 → `ACTION_SEND`（`text/plain`，
     `EXTRA_TEXT = shareText`）+ `createChooser(share, shareTitle)`，
     弹系统分享面板让用户自己选发到哪；失败的条数与首条失败原因一起
     toast（`hk_miniapp_export_partial`）；0 条成功 → 只 toast
     `hk_miniapp_export_failed`（真实原因），不弹分享、不假装成功。
  5. 点行仍进列表页（`onItemClick` 不变）；行末删除仍是彻底卸载；
     `onSelectionChanged()` 保持空实现（多选框只为导出服务，不需要
     选中计数 UI）。
- **字符串**（`lab_strings.xml`，main 源集，新增 8 条）：
  `hk_miniapp_export`（导出）、`hk_miniapp_export_select_first`（请先勾选
  要导出的小程序）、`hk_miniapp_export_not_miniapp`（勾选的源不是小程序，
  无法导出）、`hk_miniapp_export_failed`（导出失败：%1$s）、
  `hk_miniapp_export_partial`（已导出 %1$d 条，%2$d 条失败：%3$s）、
  `hk_miniapp_export_share_title_one`（导出：%1$s）、
  `hk_miniapp_export_share_title_many`（导出 %1$d 个小程序）、
  `hk_miniapp_export_share_note`（云口令是公开短链，不是加密，拿到口令的
  人都能取回这条规则。）

### 不做的（明确边界）

- 云 5 cmd.im 上传（`i22.i()` 可读，但任务定云 2；第 7 步云口令全分流再做）、
  云口令导入（第 7 步）、多条规则打包成一个口令、导出普通本地源、
  导出时改规则内容 / 写凭证、TV/leanback、`BaseLoader`、底栏。

## 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. `HkCloudExport` 只在 mobile 源集；main 源集只动 `lab_strings.xml` 的
   8 条新增字符串；`BaseLoader` 未动；TV/leanback 源集零改动；
   `menu_nav.xml` 仍 6 项。
2. 上传逻辑与报告 `i22.h()` 逐行对齐：key 生成式、Base64 NO_WRAP、
   POST 地址与表单字段、UA、15s/20s 超时、`status==1` 判定、失败文案。
3. 上传前不改规则内容：只调 `HkStore.readRaw` 读原文，无 `save` 调用。
4. 无 `Json.safeListElement` 式返回类型误用、无泛型原始类型；本步无 dialog，
   布局根布局不透明（管理页布局不动根背景，只加一个同款按钮）。
5. 未勾选 / 勾选非小程序 / 规则文件读不到 / 上传失败四条分支都有明确
   toast 且不抛、不弹分享；导出中按钮禁用防重复点击。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入）：

6. 管理页勾选奇优影院♔一条点导出：textdb 上传成功，弹出系统分享面板，
   分享文本里有一行 `奇优影院♔：云2oooole/apidb/xc…`，首行有公开短链提醒。
7. 勾选三条验收规则一起导出：分享文本里三行口令，标题各不相同。
8. 未勾选点导出：toast“请先勾选要导出的小程序”，不弹分享、不崩。
9. 断网（或 textdb 不可达）时点导出：toast 导出失败 + 真实原因，不崩、
   不弹分享。
10. 搜索/列表/详情/播放零回归：管理页多选框出现不影响点行进列表页、
    行末删除、导入/同步三按钮；其余页面照旧。

## 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（`HkCloudExport.java`、任务文档）随 revert 一起消失；管理页恢复
  成三按钮 + `setCheckable(false)`；`lab_strings.xml` 的 8 条新增字符串
  一起回去——注意：回滚后导出功能整体消失，搜索/列表/详情/播放不受影响
  （它们不走这条链路）。
- 局部回滚：只删 `btnExport` 按钮与 `runExport` 接线、把 `setCheckable`
  改回 `false`：管理页恢复原样，`HkCloudExport` 变成不可达代码，下次
  构建不受影响。

## 已知缺口与风险

- **textdb 是第三方服务，不可控**：在线与否、限流与否不受控；某天不可用
  时导出会失败（有明确提示，不崩）。魔影同款风险（评估报告 §4.3 第 3 条）。
- **key 碰撞是魔影同款行为**：key = `xc + epoch秒 + abs(title.hashCode())`，
  同一标题同一秒内重复上传会覆盖同一 key；跨标题 hashCode 碰撞理论上
  可能，概率极低，且与魔影行为一致，本步不改。
- **分享文本格式是咱们定的**：报告 `s54` case 25 本体无源码，只有“上传
  并生成云口令”的结论；本步的“首行提醒 + 每行 `标题：云口令`”是按
  `i22.a()` 的分享前缀风格定的，口令本身与魔影格式一致。
- **上传失败不重试**：魔影 `h()` 也不重试；失败就报原因，用户可再点一次。
- **多条导出部分失败时分享已成功的子集**：失败的条只在 toast 里报数 +
  首条原因，不进分享文本；用户可对失败的条单独再导一次。
- **管理页多选框常驻可见**：`setCheckable(true)` 后每行都带勾选框（与本地源
  弹窗同款）；点行仍进列表页，勾选框点选不触发进页（`LocalSourceAdapter`
  的 checkbox 自身消费点击，第 2 步以来弹窗里就是这样用的）。

## 实现清单

- [x] 设计评审（本文档）
- [x] `HkCloudExport.uploadTextDb` + `shareText`（分享标题改用字符串资源
  `hk_miniapp_export_share_title_one/many`，不在 Java 里硬编码）
- [x] `HkMiniappActivity`：导出按钮 + 多选框 + `runExport()`
- [x] `activity_hk_miniapp.xml`：`btnExport`
- [x] `lab_strings.xml`：8 条新字符串
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）

实现中纠正的（提交前）：

- `HkCloudExport` 初版有 `shareTitle()` 与 `newCodeMap()`：前者与字符串
  资源重复（Android 惯例是标题进资源），后者未被调用，两个都删了；
  分享标题改由管理页用 `hk_miniapp_export_share_title_one/many` 拼。
- 未用的 `TAG` 字段已删；其余实现与设计一致，无需纠正：逐行重读未发现
  返回类型误用、泛型原始类型、悬空 import、布局 id 与 binding 不一致；
  `Base64.NO_WRAP` 与报告 flag 2 一致；`FormBody`/`RequestBody` 用法与
  仓库已有 OkHttp 5.4.0 用法一致。
