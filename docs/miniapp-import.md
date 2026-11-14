# 海阔小程序第 2 步：导入

## Recovery anchor

- [x] 地基提交 f9f6b234 还在 main 上（Rhino + HkRule/HkStore/HkEngine/HkExecutor/HkParser）
- [x] 实验室“海阔小程序”分组 371009f4 已在 main 上（占位页，本步已替换成真页面）
- [x] 本步提交（见 finish 结果的 hash；本地提交，不推不编）

## 目标

把一条海阔规则 JSON（或 .zip/.hkzip 规则包）导进来：规则体存到 `filesDir/plugins/hk/<标题>.json`，
站源条目登记到 `localSource/sites.json`（名字 `标题⁽ʰᵏ⁾┃[类型]`，api `hk://<标题>`，type=3），
导完出现在本地源列表和实验室“本地源”分组里。点一条 hk:// 的源先弹“小程序列表页还在做”
（列表页是第 3 步），不崩、不进蜘蛛空页。

## 入口

- 实验室页“海阔小程序”分组 → HkMiniappActivity（管理页，mobile 专属）
- 管理页三按钮：导入文件/目录（SAF）、粘贴 JSON（弹窗）、同步已装规则（syncSites）
- 下面是已导入规则列表（本地源里登记的 hk:// 站源，标题⁽ʰᵏ⁾┃[类型]）

## 导入链路（i22 对照）

| 步骤 | 逆向报告里的对应物 | 咱们的实现 |
|---|---|---|
| 单个 JSON 规则文件 | `i22.C(file)` + `importOne` | `HkStore.importJsonFile`：读文本 → `HkRule.fromJson` → `validate()` → `save` → `siteOf` |
| JSON 数组 | `i22.J(str)` | `importJsonFile` 里先判断数组：逐条解析，坏的跳过记日志 |
| .zip / .hkzip | `i22.A(file)` | `HkStore.importZip`：找 rule.json（根目录优先，其次任意 `*/rule.json`，最后唯一的其它 .json，排除 require.json）→ 走 JSON 链路；require.json 重写 file 路径到 `plugins/hk/libs/` 存 `config/<标题>.json`；libs.zip 里的 .js 解到 `plugins/hk/libs/` |
| 失败重试 | `importFiles`：失败 → `mirrorToCache` → 再试一次 | `HkStore.importOne`：第一次失败 → `mirrorToCache(file)` 复制到 `cache/hk_import/` → 再调一次；还失败记 `lastError` |
| 批量 | `HkStore.importFiles(List<File>)` | 逐个 importOne，返回成功登记的条数；≤20 条时本来要 warmUp 预热——第 3 步才有列表页，暂不做 |
| 同步已装规则 | `i22.z()` / `syncSites()` | `HkStore.syncSites`：枚举 `plugins/hk/*.json`，缺失的站源条目重新登记，返回新增条数 |
| 粘贴 JSON | 弹窗 → 写 `cache/hk_paste_<时间>.json` → 走 importFiles | `HkStore.importJsonText`：写缓存文件 → `importFiles` |
| 站源注册 | `HkSiteAdapter.siteOf(g22)` + `eg.f(sites)` | `HkStore.siteOf(HkRule)` + 新方法 `LocalSource.mergeSites(List<Site>)`（在 RAW 列表上合并：同 key 替换、新 key 追加；不要用 getSites()+saveSites() 往返，会把 .php 的 localhost URL 写回成当前端口） |

## SAF 接线（HkImportPicker.kt，改编自用户给的参考实现）

参考实现：`~/workspace/user/files/SAF______2_zx4g.kt (1)`（HkImportPicker，com.example.hkminiapp，
OpenMultipleDocuments 多选文件 / OpenDocumentTree 选目录递归 + DocumentFile，复制到 cache/hk_import，
只收 .json/.zip/.hkzip，后台调 importFiles，主线程回调）。

改编点：
- 对接 HkStore.importFiles（i22），不造 HkSiteAdapter 门面
- 复制 + 收集也在后台线程（参考实现里目录收集在主线程）
- 文件选择也按扩展名过滤（参考实现只在目录路径过滤了扩展名）
- 缓存文件名用 nanoTime，避免同一毫秒同名覆盖
- 取消选择器不回调
- 依赖：`androidx.documentfile:documentfile:1.0.1`（版本目录 + app/build.gradle）

## 实验室接线（main 源集，不能引用 mobile 的类）

- `GROUP_MINIAPP` 分组点开 → 隐式 intent（action `${applicationId}.hk_miniapp`，resolveActivity 守卫）
  进 HkMiniappActivity；分组本身仍在 `render()` 里留着
- 分组页内嵌列表：`showMiniappList()`，本地源里过滤 hk:// 站源（字符串判断，不碰 mobile 类）；
  点一条弹“小程序列表页还在做”；行末删除只删站源条目（规则文件还在，同步会登记回来）
- `onRefresh()` 在小程序分组时刷新内嵌列表；`onResume()` 回来时刷新

## 兜底

- mobile `LocalSourceDialog.onItemClick`、mobile `SiteDialog.onTextClick`：hk:// 先弹
  `R.string.hk_miniapp_list_pending`（小程序列表页还在做）再 return，不切站源、不进蜘蛛空页
- leanback 的 SiteDialog 不动（TV 上没有导入入口，不可能有 hk 条目）
- 不动 BaseLoader

## 列表来源说明（与任务简报的偏差）

任务简报说列表“来自 HkStore.enumerate”，实际用的是本地源里登记的 hk:// 站源。原因：
删一条站源条目后，那行必须消失；如果按 HkStore 枚举，规则文件还在（魔影行为：删条目不删文件，
同步会重新登记），那行会赖着不走。管理页的行末删除是彻底卸载（条目 + 规则文件一起删），
本地源列表里的删除仍是只删条目（原有行为）。

## 不做的（第 7 步长尾 / 以后）

- 云口令导入（云 1–10）
- warmUp 预热（等第 3 步列表页）
- 旧版 ￥ 分隔的 home_rule 文本格式（i22.S()）
- `require` 执行（第 2 步只保证 require.json/libs.zip 落盘位置 + 路径改写）
- `LocalSource.scan()` 把 hk .json 登记成 csp_FileSpider 的问题（已知，另议）

## 验收（第 3 步列表页出来前，只能验到导入 + 登记 + 兜底）

用 `~/workspace/miniapp/test-rules/` 里的 3 条规则：奇优影院♔.json、童话.json、臻品fix.json。
阅动漫.hkzip 是第 7 步的测试用例，第 2 步不要拿它测（框架型规则，要子页面 + require 才能跑）。

1. 实验室 → 海阔小程序 → 导入文件 → 选 奇优影院♔.json → 提示“已导入 1 条小程序”
2. 列表里出现 `奇优影院♔⁽ʰᵏ⁾┃[影视]`；本地源列表和实验室“本地源”分组里也出现
3. 粘贴 JSON：把 童话.json 的内容贴进去 → 导入成功
4. 同步已装规则：先在本地源列表里删掉一条小程序条目（只删条目），再点同步 → 它回来
5. 点任意一条小程序 → 弹“小程序列表页还在做”，不崩
6. 管理页行末删除 → 条目 + 规则文件都没了，再点同步也不会回来

## 已知缺口与风险

- 第 2 步只验导入链路；规则能不能跑（find_rule 执行）要等第 3 步列表页 + 真机
- `LocalSource.scan()` 仍会把 plugins/hk 外的 hk .json 当 csp_FileSpider 登记（另议，见上）
- 导入的规则文件如果标题含非法字符，HkStore.save 会规范化文件名（非法字符 → `_`，超 80 截断）；
  站源 api 里的标题是原标题，`HkStore.fileFor(title)` 用同一套规范化，所以读写一致
- .zip 里没有 rule.json 时报错“压缩包里没有找到 rule.json”；require.json/libs.zip 解包失败不阻断主规则导入
