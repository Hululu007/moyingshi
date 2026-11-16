# 移植阶段 2c：HkCache 去留定案 + 阶段 2 收尾验收

> 任务：miniapp-xingchen-2c-cache-verify。阶段 2 最后一笔，只动本文档，不动代码。
> 完成句：HkCache 保留定案 + 阶段 2（2a+2b）收尾验收通过，证据与遗留写入本文档并本地提交。
> Recovery anchor：HEAD 50f3448d（2b，领先 origin 2 笔），工作区干净；本步只新增
> docs/miniapp-xingchen-2c-cache-verify.md；不推、不编、不触发构建、不动版本记录空间。
> 下一步：协调方按本文档 §5 遗留清单派阶段 3（含 require0 外部落点验收项）。

## 1. HkCache 去留：保留

**决定：保留 `HkCache.java`（127 行，页级 LRU），不动代码。去留改由阶段 5 替换
`HkListActivity` 时再定。**

### 1.1 读代码确认的使用面（不是两处，是三处）

| 调用方 | 用法 | 文件位置 |
|---|---|---|
| `HkListFragment.loadPage` | `get` 查缓存命中直接渲染、不发请求；成功非空后 `put` 写回 | `ui/hk/HkListFragment.java:276,312` |
| `HkWarmup` | 预热跑完首页第 1 页后 `put` 写缓存 | `api/hk/HkWarmup.java:122` |
| `HkMiniappActivity.onDeleteClick` | 删规则时 `invalidate(title)` 立即清该规则全部缓存页 | `ui/hk/HkMiniappActivity.kt:311` |

缓存键口径三处一致（规则标题 + 分类 + 页码），失效靠规则文件 mtime 比对
（`HkCache.get` 内判，文件被删时 `lastModified` 回 -1 即失效）+ 删除时立即
`invalidate` + LRU 满 600 条淘汰；0 条结果不存（防把空态粘住）。

### 1.2 保留的理由（逐条核对过，不是照抄预期）

1. **删掉要动的都是正在用的用户可见链路**：`loadPage` 的缓存命中分支
   （约 20 行，含 `seq` 防串位 + `consumePendingRefresh`）、`put` 调用、
   `HkWarmup` 的 `put`、`HkMiniappActivity` 的 `invalidate`——四处全拆才算
   删干净，拆完“列表页打开秒开 + 启动/导入后预热”这条已验证过的链路就没了，
   回归面纯新增，收益为零。
2. **没有更优的顺手方案**：缓存是页级内存 LRU，与规则存储/导入无关，并进
   `HkRuleManager` 是错层；留空壳类比直接保留更差（调用方一样要改，类还在）。
   星辰没有同名类不是删除理由——星辰的缓存角色由 `HkRuleManager/libs` +
   `HkFindDelivery` 承接（阶段 5 才有），现在没有承接方。
3. **与移植计划一致**：`docs/miniapp-xingchen-port-plan.md:121` 定
   “`HkCache.java`（127，页级 LRU）……阶段 2 确认去留”；`:95` 定阶段 5
   替换 `HkListActivity`（星辰 3268 行，对应我们的 `HkListActivity.java` 99 行
   + `HkListFragment.java` 461 行）——`HkListFragment` 被替换时 `HkCache` 的
   两个主要调用方（`loadPage`、`HkWarmup` 的写入口径）才有自然淘汰点。
4. **删除后的改法（已想好但不执行，供阶段 5 参考）**：`loadPage` 删缓存命中
   分支与 `put` 调用（`force` 参数随之无意义可并回两参版）；`HkWarmup` 删
   `put` 后只剩跑首页取数、结果丢弃——应整段停调（`warmUpOnStartup` /
   `warmUpAfterImport` 的调用点也要拆）；`HkMiniappActivity` 删 `invalidate`
   调用。阶段 5 做 `HkFindDelivery` 承接时按此清单核对。

## 2. 阶段 2 收尾验收（代码级；本地无 JDK/Android SDK，编译靠最后统一构建验）

验收基线：2a `0aba1f9e`、2b `50f3448d`；对比起点为 2a 之前 `38097b63`。
全部证据均为本步亲手重跑/重查，不是抄 2a/2b 文档的结论。

### 2.1 HkStore 门面 API 完整性：通过，零缺失、零变签名

- 旧版取 `git show 0aba1f9e^:…/hk/HkStore.java`，新版取 HEAD 同路径，逐行比对
  `public` 成员签名：**diff 为空，48 个 public 成员（方法 + `SCHEME` 常量）一个
  不少、签名全同**（含 `importFiles/importOne/importJsonFile/importZip/
  importJsonText/syncSites/readRequireManifest/resolveLibFile` 等全部导入/同步/
  require 清单入口）。
- 新类 `HkRuleManager`（2b 新建，1314 行）的 public API 另计，不在门面比对内；
  门面方法体改委托给它，但门面签名未动，调用方零改动。

### 2.2 三条验收规则：通过（新 manager 链路模拟）

重跑 `/tmp/accept_2b.py`（本步亲手跑，非抄结论）：

| 规则 | 旧链路（严格解析+校验） | 新链路（coerce+校验） | 被 coerce 挽救的字段 |
|---|---|---|---|
| 奇优影院♔.json | PASS | PASS | 无 |
| 童话.json | PASS | PASS | 无 |
| 臻品fix.json | PASS | PASS | 无 |

- `getRules` 去重 + `saveRule`/`deleteSameTitleFiles` 模拟：**PASS**
  （mtime 新者胜；同 mtime 时规范文件名胜；`saveRule` 后同标题只剩规范文件）。
- `阅动漫.hkzip` 导入分发模拟：条目 `rule.json` / `require.json` / `libs.zip`，
  `isHkZipPackage` = True，`parseHkZip` 路径可解析规则 1 条。

### 2.3 4255 合集复核：通过，不比旧版低

同一脚本重跑 `~/workspace/user/files/4255_____10_q31w.json`：

| 口径 | 数字 |
|---|---|
| 总条数 | 4255 |
| 旧严格解析 + 校验通过 | **4223** |
| 新 coerce + 校验通过 | **4223** |
| coerce 挽救的条数 | 0（合集里 0 条含非字符串 String 字段，coerce 层无物可救） |
| 新失败原因 | 缺 `url` 30 条、缺 `find_rule` 2 条（合计 32 条，旧版同样失败） |

结论：`new_pass (4223) >= old_pass (4223)` 成立；32 条失败是规则本身缺必填
字段，不是 2a/2b 回归。

### 2.4 grep 零回归（覆盖 2a+2b 两笔）：通过，附两处需点名的例外

范围：`git diff 38097b63..HEAD`（7 个文件：`HkRule.java`、`HkRuleManager.java`、
`HkSettingConfig.java`、`HkStore.java`、`HkUaEnum.java` + 2a/2b 两份文档）。

| 检查项 | 结果 |
|---|---|
| `com.fongmi`（代码） | 唯一 1 处：`HkRule.java:18` 文件头注释引用源类名 `com.fongmi.android.tv.api.hk.HkRule`（任务允许的例外，点名在此）。其余命中：`Core.java:9` / `App.java:45` 的 `import com.fongmi.hook.Hook` 是上游 fork 既有依赖，与 hk 移植无关，2a+2b 未动；2a/2b 文档内引用星辰源路径是文档说明，非代码 |
| `HkJsRuntime` / `HkHttp`（代码） | **0**（新增代码行中零引用；`HkStore.java:330` 的 `HkJsRuntime.publicKvFile` 提及与 `HkEngine` / `HkHttpClient` 内的同名提及均为 2a+2b 之前已有的注释/旧代码，未动） |
| `HkSelector`（代码） | **0 代码引用**；仅 3 处注释提及，均在 `HkRule.java`：`:30` 与 `:401`“阶段 4 换 HkSelector 时再复核”、`:166`“委托给 HkParser（HkSelector 阶段 4 才有）”——是阶段 4 待办的文字说明，不是引用 |
| leanback | **零改动**（`git diff … -- '*leanback*'` 为空；7 个改动文件全在 mobile hk + 文档） |
| `menu_nav.xml` | 未动，仍 **6 项**（vod / live / drama / following / local_source / setting） |
| `BaseLoader` | 未动（`git diff … --name-only` 无命中） |
| `sites.json` 格式 | **不变，无迁移代码**：2a+2b 代码 diff 中零 `sites.json` 命中；`HkStore.syncSites()` 仅把 `list()` 换成 `HkRuleManager.get().getRules()`，仍调 `LocalSource.mergeSites` 登记，落点与格式不变 |

### 2.5 `git diff --check`：通过

- 工作区：`git diff --check` 通过（本步写文档前工作区干净，写后仅新增本文档）。
- 2a+2b 区间：`git diff --check 38097b63..HEAD` 通过。

## 3. .hkzip 落点 / 读点错配（2b 已知行为变化，本步核实并立为阶段 3 验收项）

### 3.1 核实：错配确实存在，写读两端已定位到行

- **写点（新导入，2b 改后）**：`HkRuleManager.saveHkZipAssets`（`HkRuleManager.java:746`）
  → `require.json` 落外部 `Documents/rules/files/<标题>/require.json`
  （`getRequireFilesDir`，`:727`）；libs 落外部 `Documents/libs/`
  （`getGlobalLibsDir`，`:737`）；data.zip 落外部 `Documents/data/<标题>/`。
- **读点（现版，未改）**：`HkEngine.require`（`HkEngine.java:1597`）
  → `HkStore.resolveLibFile`（`HkStore.java:384`）→ `readRequireManifest`
  （`:366`）→ `readConfig`（`:152`）→ `HkRuleManager.getRuleConfigFile`，
  只读内部 `config/<标题>.json`。新导入的 hkzip 包的 require 清单在读点恒为
  null，`resolveLibFile` 随之回 null。
- **中间态影响（按新节奏不发版，用户看不到这个窗口）**：http(s) 库 URL 在清单
  miss 后走 `HkEngine` 既有回退——网络取回（`HkEngine.java:1598` 后分支），
  功能不断，只是丢了本地清单命中；非 http(s) 地址则报明确错。
- **存量不受影响**：旧第 2 步导入把 require.json 写内部 `config/`、libs 写内部
  `plugins/hk/libs/`，读点不变，继续命中；规则 `.json` 文件本身原地不动，
  无人删除。`migrateLegacyHkZipAssets` 只迁旧内部落点 `data/<标题>/libs/` 与
  `data/<标题>/require.json`（拷贝成功后删内部旧副本，拷贝失败保留原文件待
  下次重试）——而旧导入从未往 `data/<标题>/` 写过这两样，所以对现存用户该迁移
  几乎总是空跑，不做存量数据迁移（2b 文档 §4 已写明，本步核对代码一致）。

### 3.2 阶段 3 验收项（按任务指令列入，见 §5 第 2 条的归属疑问）

- 阶段 3 的 require0 须按星辰读外部落点：`require.json` 读
  `HkRuleManager.getRequireFilesDir(<标题>)/require.json`，libs 按 md5 直算读
  `HkRuleManager.getGlobalLibsDir()`；验收时用一条新导入的 .hkzip 包验证
  require 清单命中（不再走网络回退），并复核旧导入（内部 `config/` + 内部
  `libs/`）仍命中——两路落点在切换期内都要能读，不得只认新落点丢旧包。

## 4. 阶段 2 结论

- 2a（`0aba1f9e`）+ 2b（`50f3448d`）收尾验收**通过**：门面 API 零缺失零变签名、
  3 条验收规则新链路全过、4255 合集 4223 = 4223 不比旧版低、grep 零回归、
  `git diff --check` 通过。
- `HkCache` **保留**，去留改由阶段 5 替换 `HkListActivity` 时再定（§1）。
- 本步只产出本文档一个文件，未改任何代码；编译仍靠最后统一构建验（本地无
  JDK/Android SDK）。

## 5. 阶段 2 遗留清单（带给阶段 3 及以后）

1. **.hkzip 落点/读点错配**（§3）：新导入的包 require 清单/ libs 落外部，
   现版 `HkEngine.require` 只读内部；中间态靠网络回退顶着。阶段 3 require0
   改读外部落点时配对验收（§3.2）。
2. **归属疑问，需协调方确认**：2b 文档 `docs/miniapp-xingchen-2b-manager.md:133-142`
   §4 写的是“hkzip 包内 require 在**阶段 7**改走外部登记簿根之前走网络取回”，
   与本次任务说的“**阶段 3** 的 require0 按星辰读外部落点”不一致。本文按任务
   指令列为阶段 3 验收项；若协调方定为阶段 7，请改 2b 文档 §4 或本 §3.2 的
   阶段号，两处不要各说各话。
3. **HkCache 去留未决**：保留到阶段 5，`HkListActivity` 替换时按 §1.2 第 4 条
   的删除清单核对（`loadPage` 缓存分支 + `HkWarmup.put` + `HkMiniappActivity.invalidate`）。
4. **2b 已知行为变化（未在本步复核细节，沿用 2b 文档）**：导入报错文案变化
   （单个无效规则报“没有合法规则，<第一条原因>”）；`HkStore.delete` 文件本来
   就不存在时也回 `true`（旧版回 `false`）；`saveRule` 同标题异文件清理。
   阶段 3 起如动导入/删除链路，先复核这三条。
5. **4255 合集 32 条失败是规则本身缺字段**（缺 `url` 30、缺 `find_rule` 2），
   与 2a/2b 无关；如后续要收这 32 条，是规则补全工作，不是回归修复。
6. **编译未验**：本地无 JDK/Android SDK，2a+2b 的编译正确性只能靠最后统一
   构建验；本步的验收是代码级模拟 + 静态 grep，不能替代构建。
