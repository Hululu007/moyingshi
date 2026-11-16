# 移植阶段 3a：引擎支撑层（CookieJar/SSL/拦截器/桥接/HkHttp/Req/Crypto/Parser + Rhino 1.7.15）

> Recovery anchor：目标是移植星辰 hk 引擎支撑层（小文件 + 依赖 + HkHttp 取数/展开方法），
> 只本地提交、不推、不编、不动版本记录空间；不碰 HkJsRuntime 本体与 HkEngine 门面（3b–3e）。
> 状态：已完成并本地提交（见末尾提交记录）。下一步：3b 接手 HkJsRuntime 移植（注意事项见 §3b 接手清单）。

## 对照来源

星辰仓库：`~/workspace/xingchen-ref`（HEAD 52450c0 附近；行数以任务书与实测为准）。
我方仓库：`~/workspace/projects/Mooyingshi`，分支 main，基线 b006689d。

| 我方文件 | 星辰来源 | 行数（星辰 → 我方） | 说明 |
|---|---|---|---|
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkCookieJar.java` | `app/src/main/java/com/fongmi/android/tv/api/hk/HkCookieJar.java` | 142 → 143 | 除包名外与原文一致（+1 行为文件头移植说明，下同） |
| `.../api/hk/HkSsl.java` | `.../api/hk/HkSsl.java` | 50 → 51 | 除包名外与原文一致 |
| `.../api/hk/HkContentTypePreInterceptor.java` | `.../api/hk/HkContentTypePreInterceptor.java` | 74 → 75 | 除包名外与原文一致 |
| `.../api/hk/HkContentTypeAfterInterceptor.java` | `.../api/hk/HkContentTypeAfterInterceptor.java` | 60 → 61 | 除包名外与原文一致 |
| `.../api/hk/HkHostClasses.java` | `.../api/hk/HkHostClasses.java` | 83 → 84 | 除包名外与原文一致 |
| `.../api/hk/HkImage.java` | `.../api/hk/HkImage.java` | 33 → 34 | 除包名外与原文一致；引用的 `HkJsRuntime` 在 3b 才建，本步为前向引用 |
| `.../api/hk/HkHttpHelper.java` | `.../api/hk/HkHttpHelper.java` | 915 → 916 | 除包名外与原文一致；其引用的 `HkHttp.client()/clientFor()/imageClient()/buildAutoDNSClient` 由本步 `HkHttp` 提供 |
| `.../api/hk/HkJsAdapterBridges.java` | `.../api/hk/HkJsAdapterBridges.java` | 884 → 531 | 见 §适配点第 3 条（删三桥） |
| `.../api/hk/HkHttp.java` | `.../api/hk/HkHttp.java` | 1510 → 1382 | 子集移植，见 §搬运范围 |
| `quickjs/src/main/java/com/fongmi/quickjs/bean/Req.java` | `quickjs/src/main/java/com/fongmi/quickjs/bean/Req.java` | 197 → 198 | 原文照搬（仅 +1 行文件头）；包名有意保留，见 §quickjs 选址 |
| `quickjs/src/main/java/com/fongmi/quickjs/bean/Cache.java` | `quickjs/src/main/java/com/fongmi/quickjs/bean/Cache.java` | 32 → 33 | 原文照搬；Parser 的缓存依赖 |
| `quickjs/src/main/java/com/fongmi/quickjs/utils/Crypto.java` | `quickjs/src/main/java/com/fongmi/quickjs/utils/Crypto.java` | 128 → 129 | 原文照搬；依赖 catvod `Util.md5`（已核对存在） |
| `quickjs/src/main/java/com/fongmi/quickjs/utils/Parser.java` | `quickjs/src/main/java/com/fongmi/quickjs/utils/Parser.java` | 497 → 498 | 原文照搬；依赖 catvod `UriUtil.resolve` 与 jsoup（quickjs 模块已有） |
| `gradle/libs.versions.toml` | — | — | 仅 rhino `1.7.14` → `1.7.15` 一行 |
| `docs/miniapp-xingchen-2b-manager.md` | — | — | 只改 §4 一句：“阶段 7 改走外部登记簿根”→“阶段 3”（见 §文档小修） |

生成方式：`/tmp/xc3a/adapt.py` 从星辰原文机械变换（包名替换 + 下列适配点），
`/tmp/xc3a/verify.py` 用 difflib 逐文件核对；原文副本在 `/tmp/xc3a/*.java`（临时目录，不随仓库走）。

## 适配点（每处差异的唯一原因）

1. **包名**：全部 hk 文件 `com.fongmi.android.tv.api.hk` → `com.github.moyingshi.osc.tv.api.hk`；
   每个文件在 package 行后加一行文件头移植说明（来源 + 适配摘要）。
2. **HkHttp.client() 委托**（任务书硬性要求）：星辰 `client()` 整段（HK_AUTO_DNS、
   trust-all SSL、charset 头、ContentType 前后拦截器、CookieJar、10s 超时、dnsEnd 诊断、
   Brotli 反射加载、代理选择器链）**未搬**，改为 `return HkHttpClient.client();`（阶段 1 成果），
   不另建 client；取数 `client` 静态字段一并删除。`client()` 的 javadoc 如实记了这笔差别。
3. **HkJsAdapterBridges 删三桥**：本仓库无 XPopup（`com.lxj.xpopup`）与 HikerView
   （`com.example.hikerview`）依赖，且任务书禁引入新依赖，故删
   `BottomPopupBridge`、`CenterPopupBridge`、`BookmarkFolderBridge` 三个桥类及配套
   `listArg/listenerArg/boolArg` 与 `tryCreate` 里的三个分支、四个相关 import
   （含 `java.lang.reflect.Proxy`，只被 `listenerArg` 用）；类 javadoc 的“七个已知父类”改成“四个”，
   hikerPop.js 的类列表里三桥的提及改成“另三桥因本仓库无对应第三方依赖而略去”。
   其余分发核（BridgeSelf/callJs/dVoid/dBool/dInt/dFloat/dObj/superFn/regSupers）与
   RecyclerView 四桥（Adapter/ViewHolder/TouchCallback/SmoothScroller）与原文一致。
   `tryCreate` 命中这三类时回 `null` 走旧穿透（与“不认识的类”同路，即 Rhino 原生 JavaAdapter，
   ART 上仍会抛 `UnsupportedOperationException`——hikerPop.js 的弹窗类规则在本步之后仍跑不起来，
   待后续决定是否引入 XPopup 依赖；见 §遗留）。
4. **HkHttp.imageClient() 改派生**：星辰从 `new OkHttpClient.Builder()` 自建，改成从
   `HkHttpClient.client().newBuilder()` 派生（继承 trust-all SSL、charset 头、OkProxySelector、
   Brotli 拦截器）；因此删了重复的 `sslSocketFactory/hostnameVerifier` 装配与 Brotli 反射 try 块、
   删了 `SourceGate/GlobalProxy` 选择器行与整段“按源定向”注释；保留 HK_IMAGE_DNS、
   15s 超时、45s callTimeout、dispatcher maxRequestsPerHost=48、CookieJar。
   W1 注释里“独有的代理选择器（SourceGate/GlobalProxy）”一语改述为“独有的代理选择器”（注释也不留禁引标识）。
5. **HkHttp.applyDoh() 适配而非跳过**：星辰 `XingChenDoh.currentDoh(context)` 在我方不存在，
   但查到等价物——我方 `Setting.getDoh()`（`app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java:161`）
   + catvod `Doh.objectFrom(...)`（`catvod/src/main/java/com/github/catvod/bean/Doh.java:38`，
   空串回空 Doh、`getUrl()`/`getHosts()` 齐全），与 `Startup.java:38` 的
   `OkHttp.dns().setDoh(() -> Doh.objectFrom(Setting.getDoh()))` 同口径；且星辰
   `XingChenDoh.currentDoh` 的语义就是“空值/空 URL 即系统 DNS（回 null）”，故改成
   `Doh d = Doh.objectFrom(Setting.getDoh()); if (d != null && TextUtils.isEmpty(d.getUrl())) d = null;`，
   其余（新建 DnsOverHttps、`hkDoh` 换入、`dnsAuto` 刷新、`initAutoDns`）照搬；
   星辰侧的旧键（`xingchen.doh_provider`）迁移不搬（我方无此旧键）。
   `client = null` 复位随 `client()` 委托一并去掉（只剩 `imageClient = null`）。
   局限：`applyDoh` 之后 `hkDoh` 只影响图片 client（HK_IMAGE_DNS）与 `dnsAuto` 开关，
   不影响取数 client（委托的 `HkHttpClient.client()` 不装自定义 Dns）——见 §遗留第 2 条。
6. **HkHttp.noteHkHost() 留空壳**：星辰经 `SourceGate`/`ReadingProxyGate` 做按源归属登记，
   我方无此类，方法体改成空实现（保留 `to/toRaw/get/getBytes` 五个调用点，待代理门控移植时再填）；
   javadoc 同步改述。调用点一字未动。
7. **HkHttp.clientFor() 直回本体 + 删 explicitProxy()**：星辰按 `SourceGate`/`ReadingProxyGate`
   裁决显式 proxy 派生 client（治连接池污染），我方无此类，改成 `return client();`；
   javadoc 同步改述；`explicitProxy()` 整方法删除；随之删了 `java.net.Proxy` 与
   `java.net.InetSocketAddress` 两个不再使用的 import。
8. **其余 import 调整**：`com.fongmi.android.tv.App` → `com.github.moyingshi.osc.tv.App`
   （`App.get()` 已核对存在）；新增 `android.text.TextUtils` 与
   `com.github.moyingshi.osc.tv.setting.Setting`；`com.google.common.net.HttpHeaders` **保留原样**——
   catvod `build.gradle:27` 有 `api libs.guava`，经 `implementation project(':catvod')` 可达 app，
   无需换成 okhttp Headers 或字符串常量。
9. **HkHttp 子集外的跳过**：除上述 2/4/5/6/7 条外，星辰 `HkHttp.java` 其余内容**全部搬了**，
   包括 DoH 整套（见下）、`to/toRaw`（各仅一个重载，原文如此）、`callClient/getRequest/rawBodyOf/
   applyThreadTag/getPostBody/getJsonBody/getFormBody/getFormDataBody/formParamsOf`、
   `expandUrl`（2 重载）/`expandSearchUrl`（2 重载）/`applyOps/safe`、`Enhancement/parseEnhancement`、
   `get`（2 重载）/`getBytes/getPlain/getEnhanced`、`logQmSendIfNeeded/tailHash4`、
   `defaultUa()`。没有为跳过而删的星辰方法（`client()` 除外，见第 2 条）。

## HkHttp 搬运范围（星辰行号）

- 已搬：约 1–71 行（常量/`defaultUa()`）+ 约 100–810 行 DoH 整套 + 812–874 行 `imageClient()`
  （适配见上）+ 875–940 行 `client()`（改委托）+ 941–1010 行 `noteHkHost/clientFor`（适配见上）
  + 1009–1510 行 `to/toRaw/callClient/getRequest/rawBodyOf/applyThreadTag/getPostBody/
  getJsonBody/getFormBody/getFormDataBody/formParamsOf/expandUrl/expandSearchUrl/applyOps/safe/
  Enhancement/parseEnhancement/get/getBytes/getPlain/getEnhanced`。
- DoH 整套已搬的明细（catvod 有 `com.github.catvod.bean.Doh`，且 `okhttp-dnsoverhttps` 在
  `libs.versions.toml:151` 的 okhttp bundle 里、经 catvod `api libs.bundles.okhttp` 可达 app，
  故 DoH 不跳过）：`HK_DNS`（星辰自注“保留，现无链使用，不要动它的行为”，照搬）、`hkDoh`、
  `AUTO_DOMAIN/AUTO_DOH`（含 `pasteme.tyrantg.com` 阿里 DoH 种子）、`registerAutoDomain/
  autoDomainValue/autoDoh`、`HK_AUTO_DNS/lookupAuto/stripAnyLocal/hasUsableAddress`、
  `dnsAuto/AUTO_DNS_CHECK_URL/AUTO_DNS_BUNDLED/AUTO_DNS_REFRESH_MS/AUTO_DNS_INITED/
  AUTO_DNS_BY_VALUE/ALI_AUTO_DNS/buildAliDoh/cappedDns`、`shouldUseAutoDNS/autoDnsEnabled`、
  `buildAutoDNSClient`（2 参与 3 参两个重载）/`autoDnsFor`、`OFFICIAL_DNS_BY_VALUE/
  fakeEndpointDoh/officialDnsFor`、`initAutoDns/maintainAutoDomain/autoDnsFile/
  reseedAutoDomain/loadEffectiveAutoDns/loadAutoDNS/hasParsableAutoDnsLine/fetchAutoDnsText/
  readTextFile/writeTextFile`（名单文件 `filesDir/plugins/hk/rules/dns0.txt`，与 HkStore 的
  `plugins/hk` 根一致）、`HK_IMAGE_DNS/isAutoDnsHost`、`applyDoh`（适配见上）。
- 跳过的段落：只有 `client()` 原文体、`imageClient()` 的选择器/Brotli/SSL 装配行、
  `noteHkHost()` 体、`explicitProxy()`、`XingChenDoh` import 与 `applyDoh` javadoc 里的一处提及
  （均在 §适配点列出原因）；无其他整段跳过。`HK_DNS` 虽无链使用但照搬，未删。

## quickjs 选址决定

- 新建的 `com.fongmi.quickjs` 类放在 **quickjs 独立模块**（`quickjs/src/main/java/com/fongmi/quickjs/...`），
  不放 `app/src/main/java/...`。理由：`settings.gradle` 已 `include ':quickjs'`，app 以
  `implementation project(':quickjs')` 依赖它；quickjs 模块是标准 `src/main/java` 布局，
  无 sourceSets 定制，新文件自动编入；且星辰的 quickjs 类本来就在 quickjs 模块里，
  与上游 webhtv quickjs 模块的原包名 `com.fongmi.quickjs` 对位。
- 包名**保持 `com.fongmi.quickjs` 不改**：这是上游 webhtv quickjs 模块的原包名，
  星辰 `HkHttp/HkHttpHelper` 按此包名引用 `com.fongmi.quickjs.bean.Req`；改写会断链。
  与我方外部 AAR `com.whl.quickjs` 包名不同，不冲突；与我方已有的
  `com.github.moyingshi.osc.tv.quickjs.bean.Req`（另一套）包名不同，不冲突。
- 只搬了 HkJsRuntime/HkHttpHelper 链路上需要的 4 个类（Req/Cache/Crypto/Parser）；
  Parser 的 import 只有 `Cache`（`com.fongmi.quickjs.bean.Cache`，已一并搬），
  未搬整个 quickjs 模块。Req 依赖的 catvod `Json.toMap`（`Json.java:89-93`）已核对存在。

## ToastUtil / SourceGate / DoH 的处理

- **ToastUtil**：本步不新建。星辰 `HkJsRuntime.java` 里 3 处 `ToastUtil.show(...)`
  （`:3881`、`:7141`、`:12306`，均为 `ToastUtil.show(App.get(), msg, Toast.LENGTH_SHORT)` 形），
  在 3b–3d 移植 `HkJsRuntime` 时改走我方 `Notify`（`app/src/main/java/com/github/moyingshi/osc/tv/utils/Notify.java`，
  `show(String)`/`show(int)`/`show(Notification)` 已核对存在；`ToastUtil.show(App, msg, LENGTH_SHORT)`
  对应 `Notify.show(msg)`）。
- **SourceGate / GlobalProxy / ReadingProxyGate**：我方全仓库 grep 零命中（任务书已核对，本步复核仍为零）。
  处理：`HkHttp` 的三处（`client()` 选择器行、`imageClient()` 选择器行、`noteHkHost/clientFor/
  explicitProxy`）分别按 §适配点第 2/4/6/7 条处理；无代理时直连等价（阶段 1 已论证，
  `HkHttpClient` 装 `OkHttp.selector()`）；有代理时用户在 ProxySetting 等处配的规则经
  `OkProxySelector` 生效。`HkHttpHelper` 的 `resolveClient` 经 `HkHttp.clientFor(url)` 走，
  同口径。代理门控本身（按源定向/显式 proxy 派生/连接池污染治理）未搬，属后续工作。
- **DoH**：见 §HkHttp 搬运范围——`com.github.catvod.bean.Doh` 我方有，
  `okhttp-dnsoverhttps` 经 catvod 可达，故 DoH 名单/AUTO_DNS 机制整套搬了；
  只有取值入口 `XingChenDoh` 不存在，`applyDoh` 按 §适配点第 5 条改走本地 Setting。

## 文档小修

- `docs/miniapp-xingchen-2b-manager.md` §4（有意偏差第 4 条）一句：
  “hkzip 包内 require 在阶段 7 改走外部登记簿根之前走网络取回”→“在阶段 **3** 改走外部登记簿根之前”。
  只改这一句，别处未动（该文档第 202 行附近“在阶段 7 前走网络”一句未动——它说的是 require 走网络的
  时间窗，与本句归属不同，留给父代理裁定是否联动改）。

## 验收证据（代码级；本地无 JDK/Android SDK，编译靠最后统一构建验）

- difflib 逐文件对照（`/tmp/xc3a/verify.py`，`+`/`-` 行数，不含文件头行则更少）：
  纯移植 7 个文件各 `+2 -1`（包名行 + 文件头行）；quickjs 4 个文件各 `+1 -0`（仅文件头行）；
  `HkJsAdapterBridges.java +5 -358`（删三桥及配套方法，diff 已逐行读过，只剩 §适配点第 3 条）；
  `HkHttp.java +34 -162`（diff 已逐行读过，只剩 §适配点第 2/4/5/6/7/8 条）。
  两份 diff 全文在 `/tmp/xc3a/diff_HkHttp.java`、`/tmp/xc3a/diff_HkJsAdapterBridges.java`（临时目录）。
- 禁引 grep：对 13 个新文件 grep `com\.fongmi\.android\.tv|SourceGate|GlobalProxy|XingChenDoh|
  ReadingProxyGate` **零命中**（`com.fongmi.quickjs` 包名是有意保留的例外，不在禁引之列；
  `HkHttpHelper` 的注释里提到的 `HkHttp.client()` 等均为同包类名）。
- `git diff --check` 通过；`gradle/libs.versions.toml` 的 diff 只有 rhino 一行
 （`1.7.14` → `1.7.15`）；2b 文档的 diff 只有 §4 一句。
- 括号平衡：13 个新文件 `{`/`}` 与 `(`/`)` 计数与星辰原文一致
  （`Parser.java` 的 `(`/`)` 457/455 与原文完全相同，是正则字符串字面量所致，非错误）。
- 逐行重读：13 个新文件已逐行重读；`HkHttp` 的改动区（imports/`applyDoh`/`client()`/
  `imageClient()`/`noteHkHost`/`clientFor`）与 `HkJsAdapterBridges.tryCreate` 已单独通读确认结构完整。
  修复过一处脚本切片 bug：`noteHkHost` 替换时 `'    }'` 子串误中 catch 行的 8 空格缩进，
  残留三行导致方法体断裂——已改成按独占行 `'\n    }\n'` 定位并重跑、重验（现 `HkHttp.java` 1382 行，方法体为空壳正确）。
- 依赖可达性已核对：`Logger`（orhanobut 2.2.0，经 catvod `api libs.logger`）、Gson、
  jsoup、`okhttp-dnsoverhttps`、Guava `HttpHeaders`、`SpiderDebug.log(tag, msg, args...)`、
  `Json.safeObject/toMap`、`Util.md5`、`UriUtil.resolve`、`OkHttp.selector()`、`App.get()`、
  `Setting.getDoh()` 均存在；quickjs 模块 `implementation project(':catvod')` + `libs.jsoup` 已有。

## 遗留与已知缺口（未解决，待后续步骤）

1. **CookieJar 与 ContentType 拦截器尚未接进任何 client**：`HkCookieJar`、`HkContentTypePreInterceptor`、
   `HkContentTypeAfterInterceptor` 三个类已新建，但 `HkHttp.client()` 按任务书委托给
   `HkHttpClient.client()`，而 `HkHttpClient` 未装它们（阶段 1 已记“留给阶段 3”）；取数请求目前
   无持久化 Cookie、无 ContentType 前后处理、无 10s 超时、无 HK_AUTO_DNS（系统默认解析）。
   需后续步骤决定接线方式（改 `HkHttpClient` 或在 `HkHttp` 另建取数 client——后者与本步“不另建 client”
   的任务书要求冲突，需父代理裁定）。
2. **`applyDoh` 的效力范围收窄**：`hkDoh` 只影响图片 client 与 `dnsAuto` 开关，不影响取数 client
   （同上，委托的 client 不装自定义 Dns）。用户改 DoH 设置后，规则取数仍走系统解析；
   图片与自动 DNS 名单（`shouldUseAutoDNS`/`buildAutoDNSClient`）正常。
3. **XPopup 两桥与收藏夹适配器桥缺失**：`hikerPop.js` 里 `new JavaAdapter(BottomPopupView/
   CenterPopupView/BookmarkFolderAdapter, ...)` 的规则在本步之后仍走 Rhino 原生 JavaAdapter，
   ART 上抛 `UnsupportedOperationException`。待后续决定是否引入 XPopup 依赖（任务书禁新依赖，本步未引）。
4. **前向引用，统一构建前不可单独编译**：`HkImage` 引用 3b 才建的 `HkJsRuntime`；
   `HkJsAdapterBridges.tryCreate` 由 3b 的 `HkJavaAdapterFn` 调用（本步无调用方）；
   `HkHttpHelper`/`HkHttp` 互引同包已闭环。编译验证留待最后统一构建。
5. **2b 文档第 202 行**“在阶段 7 前走网络”一句未动（见 §文档小修），是否联动改由父代理裁定。

## 3b 接手清单

- `HkJsRuntime` 移植时：3 处 `ToastUtil.show(App.get(), msg, Toast.LENGTH_SHORT)` 改走 `Notify.show(msg)`；
  `HkJavaAdapterFn` 里命中 `RecyclerView.Adapter/ViewHolder`、`ItemTouchHelper.Callback`、
  `LinearSmoothScroller` 时调 `HkJsAdapterBridges.tryCreate(...)`（包内静态方法，已就绪），
  命中弹窗两桥/收藏夹适配器时 `tryCreate` 回 `null`（维持旧穿透，见遗留第 3 条）。
- `HkEngine` 门面（3e）如需在启动/改 DoH 设置时调用 `HkHttp.applyDoh(context)`，直接调即可
  （签名 `boolean`，失败回 false 不抛错）；如需自动 DNS 名单维护，调 `HkHttp.initAutoDns(context)`
  （每进程只实跑一次，内部走后台线程）。
- `HkHttp.client()` 是委托，`HkHttpHelper.fetch0` 经 `HkHttp.clientFor(url)` 取 client，
  超时/禁重定向派生逻辑已在 `HkHttpHelper.resolveClient` 里；per-request `dns` 选项经
  `HkHttp.buildAutoDNSClient(dns, url, client)` 落到 `officialDnsFor`，已就绪。
- `HkImage.setCurrentTitle/setCurrentRuntime` 由 `HkEngine` 创建/包裹运行时调用（原文如此）；
  `HkHostClasses.installFileUtilChain` 由 `HkJsRuntime` 每次 API 注册时调用（原文如此）。

## 提交记录

- Guard 会话：`miniapp-xingchen-3a-support`（standard；基线 b006689d）。
- 提交：`feat(hk): 移植阶段 3a：引擎支撑层（CookieJar/SSL/拦截器/桥接/HkHttp/Req/Crypto/Parser + Rhino 1.7.15）`。
  提交 hash 与 recovery tag 见父代理收到的汇报（不另起一笔文档提交来补录）。
