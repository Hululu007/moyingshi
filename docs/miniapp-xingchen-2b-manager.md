# 移植阶段 2b：HkRuleManager 核心（存储/导入）+ HkStore 瘦身成门面

> Recovery anchor：目标是移植星辰 HkRuleManager 核心（存储/导入），把 HkStore
> 瘦身成门面（public 签名不变、调用方零改动），接缝文件零改动；不碰云口令/导出
> （阶段 6）与 HkCache；不推、不编、不动版本记录空间。下一步由父代理验过后推与编。

## 对照来源

| 我方文件 | 星辰来源 | 说明 |
|---|---|---|
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRuleManager.java`（新建，1314 行） | `~/workspace/xingchen-ref/app/src/main/java/com/fongmi/android/tv/api/hk/HkRuleManager.java`（2058 行） | 移植星辰行号 48–1163 与 1887–2058；包名改为墨影视侧 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkStore.java`（834 行 → 约 640 行） | 同上（接管方）+ 原 HkStore 自有能力 | 瘦身成门面，public API 与旧版逐项比对一致 |
| 本文档 | 移植计划 `docs/miniapp-xingchen-port-plan.md` §阶段 2 | 本步是阶段 2 的 2b：只做管理器核心 + 门面 |

行号说明：任务书写作“约 48–1160 行”，实测星辰 `importJsRule` 方法体在
1160–1163 行（`saveRule`/`return`/`}` 在 1161–1163），故实际取到 1163；
第二段任务书写作 1888–2058，`getRules` 的 javadoc 在 1887，故实际取自 1887。
云口令/导出区实为星辰 1165–1886 行（任务书写作 1166–1888，含首尾空白/注释行差），
留待阶段 6。

## 搬运范围（星辰行号）

已搬（两段）：

- 约 48–1163 行：单例 `get()`、目录（`getDir/getDataDir/getConfigDir/
  getRequireFilesDir/getGlobalLibsDir/getExternalDataDir/getExternalData2Dir`）、
  `loadRuleJson/getInstalledRule`、存量迁移 `migrateLegacyHkZipAssets`、规则 config
  读写（`getRuleConfigFile/loadRuleConfig/saveRuleConfig`）、`ParsedRule/HkZipData`、
  `parseRuleJson + coerceRuleStringFields + fastJson*` 收敛层、`parseJson`、
  `saveStamp/saveRule/deleteSameTitleFiles`、`importJson/importFile/parseZip/
  importZip`、`parseHkZip/saveHkZipAssets/importHkZip`、`isHkZipPackage/
  isCollectionPackage/collectionCleanName/extractCollectionPackage`、
  `isJsShell/executeHkShellText/copyShellEntry/readStream`、
  `parseJsonList/importJsonList`、`isJsRuleText/parseJsRule/importJsRule`。
- 1887–2058 行：`getRules`、`logRuleError`、`getRule`、`ruleModified`、`delete`、
  `deleteRecursive`、`setEnabled/isEnabled/getEnabledRules`、`safeFileName`。

未搬（阶段 6）：星辰 1165–1886 行云口令取件与导出——`isCloudCode`、
`parseByCloudCode`、`fetchPasteRawText`、`importByCloudCode`、`fetchCloudJson`、
`fetchCloudContent`、`fetchNoteMsRaw`、`extract*`、`unwrapShareText`、
`buildShareCommand`、`exportAsCloudCode/exportAsCmdImCode/exportAsFile/
exportAsHkZip/exportAsZip` 等。新文件对应位置只有一句注释
`// 阶段 6：云口令取件与导出（HkRuleManager.java:1166-1888）在此补`，无空方法占位。
云口令常量（`CLOUD_CODE_PATTERN/CLOUD_CODE_FIND/normalizeCloudCode` 与
`TEXTDB_*/PASTEME_*/CMDIM_BASE`）也随消费者一起留给阶段 6，不在本步留死代码。
`parseJsonList` 里原来调用它们的云口令/分享包装分发块已删，非 `{`、`[` 开头的
文本改由 `parseJson` 按「内容不是规则 JSON」报错，阶段 6 补回分发。

## HkStore 门面清单

委托给 `HkRuleManager`（签名不变）：`dir/configDir/dataDir/fileName/fileFor`、
`save/save(rule)`、`read/readRaw/exists/lastModified/delete/list`、
`saveConfig/readConfig`、`importFiles/importOne/importJsonFile/importZip/
importJsonText/syncSites`。

保留在本类的自有能力：rc 缓存（`rcCacheFile/rcCacheMetaFile/saveRcCache`）、
kv/myvar（`readItems/saveItems`、`readMyVars/saveMyVars`）、公共 KV
（`publicKvFile/readPublicItems/savePublicItems`）、require 清单
（`readRequireManifest/resolveLibFile`）、`dataFile/readDataFile/writeDataFile/
deleteDataFile`、站源映射（`siteOf/typeNameOf/isMiniapp/titleOf/withoutMiniapps/
SCHEME`）、`importCacheDir/libsDir`、`lastError/lastError()`、`readTextFile`。

门面自有逻辑（不在 manager 里）：`importOne` 的 `mirrorToCache` 失败重试；
导入成功后登记站源（`registerSites`，manager 本身不登记站源）；`importJsonText`
先写 cache 文件再走 `importFiles` 的旧链路；`readConfig` 缺失时回 `null`
（manager 的 `loadRuleConfig` 缺失时回 `"{}"`，门面先判 `getRuleConfigFile`
是否为 null 再委托，保持旧契约）。

删掉的旧实现（已被 manager 接管，均为 private、无外部引用）：`parseFile`、
`findRuleEntry/readZipEntry/rewriteRequirePaths/extractLibs/saveAndRegister`。
`MAX_NAME_LEN` 常量随 `fileName` 委托一并去掉（实现只留 `safeFileName` 一份）。

## 适配点逐条核对

1. **包名**：星辰包改为 `com.github.moyingshi.osc.tv.api.hk`；`App` 用我们自己的
   `App`（`App.get()` 在 `app/src/main/java/.../App.java:87` 存在，`App.gson()` 是
   普通 `new Gson()`，与 manager 内 `new Gson()` 等价）。
2. **HkJsRuntime 引用（阶段 3 才有，2b 不得引用）**：`get()` 里星辰 :104-107 的
   `HkMediaCacheCleaner.scheduleClean(...)` 整段跳过（清理是阶段 7 按需）；
   `extractCollectionPackage`（星辰 :919）与 `copyShellEntry`（星辰 :996）里的
   `getFilePath(...)` 改走本类新增的 `resolveHikerFilesPath`（见下）；新文件
   grep 零 `com.fongmi`、零 `HkJsRuntime/HkSelector/HkHttp/HkMediaCacheCleaner`
   引用（含注释）。
3. **`resolveHikerFilesPath`**（新增，manager 内私有静态）：星辰规则引擎
   `getFilePath` 的 2b 等价实现——`hiker://files/` 剥前缀拼外部 Documents 根，
   `file://` 只剥前缀，其余原样返回；另加星辰没有的防 `..` 越界（解析后逃出根
   目录回落到 basename）。`saveHkZipAssets` 本身不调 `getFilePath`（它直接用
   `getExternalDataDir/getExternalData2Dir`），任务书该处行号为约数，实际两个
   `getFilePath` 调用点就是上述 :919 与 :996。
4. **`.hkshell` 纯 COPY**：`executeHkShellText/isJsShell/copyShellEntry` 已核实是纯
   文件拷贝（只处理 `COPY` 开头、切段恰为 3 段的行），不是 shell 命令执行，可以搬。
   调用点核实：星辰同款注释写明只在用户点中 `.hkshell` 并过确认面后由浏览面调用，
   合集导入时自动执行已随官形摘除；**本仓库目前暂无任何调用方**（合集浏览面是后续
   阶段的活），方法是先移植到位、未接线，不存在自动执行链路。
5. **`getRules()` 去重语义**：与旧 `HkStore.list()` 已核对一致（mtime 最新胜出、
   平局标准文件名优先、空标题用文件名回退）；新增行为保留：按 SharedPreferences
   写 `enabled`、解析失败写 `files/hk_rule_error.log`。
6. **外部目录**：`getExternalDataDir/getExternalData2Dir` 用
   `ctx.getExternalFilesDir(DOCUMENTS)` 照搬；与 HkStore 内部存储的公共 KV
   （`plugins/hk/rules/_item00.json`）是两回事，互不影响。
7. **`saveStamp` 与 `HkSettingConfig.nowLoadRulesTime`**：星辰 manager 内只有
   `SAVE_STAMP`（:536-539）；`nowLoadRulesTime` 的写点在星辰
   `event/rule/OnArticleListRuleChangedEvent` 构造器里（不在 manager 内）。
   我们暂无该事件类，故在 manager 的 `saveRule/delete` 里与 `SAVE_STAMP`
   递增并列写 `nowLoadRulesTime = System.currentTimeMillis()`，把官事件构造的
   副作用落在同样的变更点上；事件类来了之后可再挪过去。
8. **`parseRuleJson` coerce 收敛层**：只碰 HkRule 的 String 字段、跳过
   `@JsonAdapter` 的 `version`，照搬。`HkRule.parse()`（我们现有的）保持原样供旧
   调用方用，manager 内部走 `parseRuleJson`——两条解析入口并存的原因：旧入口
   是“解析失败回 null”的静默契约（`HkRule.parse` 被 `HkExecutor` 等多处按 null
   分流消费），新入口是“收敛 + 校验 + 抛人话错误”的导入契约，两者语义不同，
   不能合并不留。
9. **`parseJson` 的 `validate()`**：星辰 `validate()` 是抛 `IllegalArgumentException`
   的版本，我们的 `HkRule.validate()` 是 2a 定下的 `String` 签名（通过回 null）。
   manager 内适配为 `String error = rule.validate(); if (error != null) throw
   new IllegalArgumentException(error);`，错误文案不变。
10. **sites.json 格式不变，不迁移用户已装规则**；`HkStore.save()` 原来的“同标题异
    文件删除”由 `manager.saveRule/deleteSameTitleFiles` 承接，行为不变。
11. **无新依赖**：未引入 LitePal 或任何新依赖，未改 gradle；未动 `HkEngine/
    HkExecutor/HkListFragment` 等执行链文件。

## 有意偏差（不要“顺手”改回）

1. **`safeFileName` 由 private 改为 public**：星辰为 private；改为 public 只为让
   `HkStore.fileName()` 委托、保持一份实现。逻辑一字未动。
2. **`resolveHikerFilesPath` 加防 `..` 越界**：星辰 `getFilePath` 本身不设防；
   合集解压已在外层跳过含 `..` 的条目，这里再加一道（`.hkshell COPY` 的目标串
   来自用户确认后的文本，更需设防）。
3. **`delete()` 行为变强**：除规则文件外还删数据目录 `data/<标题>/` 与启用开关
   记录；文件本来就不存在时也回 `true`（旧版回 `false`）。唯一调用方
   `HkMiniappActivity.onDeleteClick` 不消费返回值；管理页删规则前已先调
   `LocalSource.removeByKeys` 删站源条目，顺序不变。
4. **`.hkzip` 导入落点改星辰口径**：`require.json` 落外部登记簿根
   （`Documents/rules/files/<标题>/require.json`）、libs 落外部 `Documents/libs/`、
   data.zip 落外部 `Documents/data/<标题>/`；旧版是 require.json 写 `config/`、
   libs.zip 解到内部 `plugins/hk/libs/`。后果：`HkStore.readRequireManifest`
   对新导入的 hkzip 包回 null（它只读 `config/`），`resolveLibFile` 随之回 null，
   hkzip 包内 require 在阶段 3 改走外部登记簿根之前走网络取回（`HkEngine` 既有
   回退）。`migrateLegacyHkZipAssets` 仍会把旧内部落点 `data/<标题>/libs/` 与
   `require.json` 迁到官根；但注意我们第 2 步的旧导入从未往 `data/<标题>/` 写过
   libs/require.json（它写的是内部 `libs/` 与 `config/`），所以对现存用户该迁移
   几乎总是空跑，不做存量数据迁移。
5. **导入报错文案变化**：单个无效规则旧版抛 `validate()` 原文或“数组里没有可用的
   规则”，新版经 `importJsonList/parseJsonList` 抛“没有合法规则，<第一条原因>”；
   云口令/分享包装文本误贴进粘贴框时，旧版报“不是有效的规则 JSON”，新版报
   “内容不是规则 JSON（开头为：…），可能是口令已失效或云端内容已更换”——阶段 6
   补回云口令分发后恢复正确分流。调用方只展示文案，不分支判断，无行为回归。
6. **`js:` 文本文件导入是新增能力**：旧 `importOne` 把 `.js`/js: 文本当 JSON 读会
   失败；新版 `importJsonFile` 先判 `isJsRuleText` 走 `importJsRule`（星辰口径，
   标题取文件名）。这是移植带来的能力，不是回归。
7. **`DATA_DIR` 常量保留**：星辰与本文件里都未被引用，照搬保留（私有常量，无害）。
8. **云口令常量随阶段 6 走**：星辰 48–1160 行区间内的云口令常量块未搬（其唯一
   消费者全在 1165–1886 行），本步不留死代码；阶段 6 连同方法一起补回。

## 接缝（核对结论：零改动）

- `HkMiniappActivity.kt`（管理页）：用的全是门面保留方法——`isMiniapp/
  importJsonText/syncSites/lastError/titleOf/delete`，零改动。
- `LabActivity.java` 海阔小程序分组：用 `LocalSource.isMiniapp/miniappTitle`
  （main 源集），根本不碰 `HkStore`，零改动。
- `LocalSourceDialog.java` 的 hk 分支：`HkStore.isMiniapp/titleOf` 保留，零改动。
- `LocalSource.mergeSites`：同步落点不变，`syncSites` 仍调它，零改动。
- 其余 `HkStore` 调用方（`HkEngine` 的 kv/dataFile/require、`HkWarmup.list()`、
  `HkListFragment/HkDetailFragment/HkSearchFragment` 的 `read`、`HkCloudImport/
  HkCloudExport`、`HkImportPicker`、`SiteDialog/SiteAdapter/VodFragment`）
  用的方法签名全部保留（与旧版逐项比对一致，见验收），零改动。

## 验收（代码级，本地无 JDK/Android SDK，编译靠 GitHub 构建验）

脚本 `/tmp/accept_2b.py`（模拟星辰逻辑的 Python 复写，非真机）：

- 3 条验收规则（`~/workspace/miniapp/test-rules/`）：奇优影院♔、童话、臻品fix
  走新解析链路（coerce + `validate()`）全部 PASS；旧链路同样 PASS，无回归。
- 4255 合集（`~/workspace/user/files/4255_____10_q31w.json`，4255 条）：旧严格
  解析 + 校验通过 4223 条，新 coerce + 校验通过 **4223 条，不比旧版低**；
  32 条两边同 fail（30 条缺 url、2 条缺 find_rule）；该合集里 0 条含非串 String
  字段，故 coerce 本轮救援 0 条——加宽容未更严，已验证。
- `getRules` 去重 + `saveRule` 同标题清理逻辑模拟 PASS：新 mtime 胜出、平局标准
  文件名胜出、保存后旧副本被清掉。
- `阅动漫.hkzip`（框架型规则）：`isHkZipPackage` 判真（内含 rule.json +
  require.json + libs.zip），`parseHkZip` 路径可解析出 1 条规则。
- grep：新文件零 `com.fongmi`、零 `HkJsRuntime/HkSelector/HkHttp/
  HkMediaCacheCleaner`（含注释）；`git diff --check` 通过；`git status` 只有
  3 个 scope 内文件（新 manager、新文档、改 HkStore），TV/leanback 零改动，
  `BaseLoader` 未动，底栏仍 6 项（未碰）。
- 逐行重读：新文件与星辰两段原文做 difflib 对照，差异只剩本文列出的适配点；
  过程中发现并修好 1 处行号切分错（见下）；两文件括号/花括号平衡检查通过；
  `HkStore` public API 与旧版逐项比对一致。

## 过程中修好的 1 处错

任务书“约 48–1160 行”是约数：星辰 `importJsRule` 方法体实际在 1160–1163 行，
第一版按 1160 切分把该方法拦腰截断（`saveRule/return/}` 丢失，`getRules` 直接
跟在半截方法后，无法编译）。逐行重读时发现，已修正为取到 1163 行、
`getRules` javadoc 自 1887 行起取；并以 difflib 全量对照确认无其他截断。

## 遗留与风险（给父代理）

- **编译未验**：本地无 JDK/Android SDK，两文件只做了逐行重读 + difflib 对照 +
  括号平衡 + 逻辑模拟；GitHub 构建是唯一编译检查，推上去后须看构建结果。
- **2a 文档 hash/tag 未回填**：已知小缺憾，留给父代理，不在本步修（任务要求）。
- **hkzip 包内 require 在阶段 3 前走网络**：见有意偏差第 4 条；`阅动漫` 这类
  框架型规则要 require 本地库才能跑通，完整验证等阶段 7。
- **合集浏览面与 `.hkshell` 调用方缺失**：`extractCollectionPackage/
  executeHkShellText/isCollectionPackage` 已移植但暂无调用方，合集包“只解压、
  让用户逐个点选”的浏览面是后续阶段的活；`.hkshell` 在那之前无执行入口。
- **云口令粘贴暂报错**：粘贴框误贴云口令在阶段 6 前按“内容不是规则 JSON”
  报错（非静默失败，文案可读）；`HkCloudImport` 的云口令链路本身不受影响
  （它先取回 JSON 再调 `importJsonText`）。
- **旧 `importOne` 的 null 入参边角**：`mirrorToCache(null)` 会在 catch 里再取
  `file.getName()` 抛 NPE——与旧版同一写法、同一缺陷，调用方 `importFiles`
  本就跳过 null 文件，未改。
