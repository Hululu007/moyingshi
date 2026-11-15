# 对齐第 1 项：config / initConfig 改进程内共享 + rc 缓存小时数兑现

> Recovery anchor — 目标：按官方实现把 `config`/`initConfig` 从“每求值新建”改成“进程内按规则标题共享”，并让 `rc(url, hours)` 的小时数真正控制库缓存有效期；只做这一项，不碰其余 4 项；本地提交、不推、不编。
> 验收：① preRule 里 `initConfig` 设的值，同求值内 `find_rule` 读 `config.*` 可见；② 不同规则的 config 互不串（按标题隔离）；③ `rc(url, 24)` 有效期内不重抓、过期重抓，plain `require`（-1）永久有效；④ 3 条验收规则零回归（它们不用这些 API）。
> 当前状态：main = 902c1e8c，工作区干净；本文档先写，代码未改；下一步：guard start → 改 HkEngine/HkStore → Node 校验 JS 片段 + 逐行重读 → guard finish 提交 + recovery tag。

- 完成句：墨影视海阔小程序的 `config`/`initConfig` 按官方语义改为进程内按标题共享、`rc` 小时数兑现，本地提交完成。
- 允许路径：`docs/miniapp-align-1-config.md`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkStore.java`。
- 保护：TV/leanback 不动，BaseLoader 不动，底栏仍 6 项（`app/src/mobile/res/menu/menu_nav.xml`，提交前核对）；不推、不编、不动版本记录空间。
- 本地时间：2026-11-15 22:36+ CST；预计：文档 15 分钟 + 实现 30 分钟 + 校验提交 20 分钟，约 23:40 前完成。
- 最便宜的决定性验证：Node 跑 BOOTSTRAP 里 config/initConfig 的 JS 片段（mock `getMyVar`/`putMyVar`）验合并/accessor/隔离；rc 有效期逻辑逐行对照官方 `require0` 判定式核对（本地无 JDK，编译等 GitHub 构建）。

## 1. 证据（官方实现，读透再动手）

来源：`~/workspace/hk-ref/ANALYSIS.md` §4/§5 + `~/workspace/hk-ref/src/sources/com/fongmi/android/tv/api/hk/HkJsRuntime.java` + `~/workspace/hk-ref/assets-extracted/assets/plugin.js` + `~/workspace/hk-ref/src/sources/defpackage/tz1.java`。

### 1.1 有两个 initConfig，实际生效的是 JS 版

- Java 版（`HkJsRuntime.java:8194`，`lambda$registerApi$42`）：入参对象 → JSON → `tz1.W()` 写盘 `<filesDir>/plugins/hk/config/<标题>.json`（`tz1.java:512`，文件名经 `T()` 清洗，最长 80 字符）→ `scope.config = parseJson(...)`。失败抛 `"initConfig 保存失败：<标题>"`。
- JS 版（`assets/plugin.js:12-51`，在 scope 组装最后 eval，盖掉 Java 版）：
  ```js
  function initConfig(obj){
      let config = {}
      if(obj != null && "{}" != JSON.stringify(obj)){
          let _cfg = getMyVar('initConfig', '{}');
          if(_cfg && _cfg.length > 0){ config = JSON.parse(_cfg) }
          for(let key of Object.keys(obj)){ config[key] = obj[key] }
      }
      putMyVar('initConfig', JSON.stringify(config));
      windows.config = config;
  }
  windows.initConfig = initConfig;
  Object.defineProperty(windows, "config", {
      enumerable: true, configurable: true,
      get: function () {
          if(windows.config0 == null){
              let _cfg = getMyVar('initConfig', '{}');
              let config = {};
              if(_cfg && _cfg.length > 0){ config = JSON.parse(_cfg); }
              windows.config0 = config;
              return config;
          } else { return windows.config0; }
      },
      set: function(v) { windows.config0 = v; }
  });
  ```
  即：**合并**进 `getMyVar('initConfig')`，并置 `windows.config`。**不写盘。**

### 1.2 varMap 的键、config0 的作用、Java 版与 JS 版的关系

- `varMap` 是静态 `ConcurrentHashMap<String,String>`（`HkJsRuntime.java:337,559`），**进程内有效，进程死即丢，不落盘**。键是 `myVarKeyFor(key, null)` = `resolveRuleTitle(null) + "@" + key`（`HkJsRuntime.java:9996-10004`），即 **`"<规则标题>@<键>"`** ——按规则标题隔离就是键前缀隔离。`getMyVar('initConfig')` 的键就是 `"<标题>@initConfig"`。
- `config0` 是每个 fresh scope 上的惰性缓存：`config` getter 在 `config0 == null` 时才从 varMap 读一次 JSON 并缓存；setter 只写 `config0`，**不回写 varMap**。跨求值共享靠的不是 `config0`（每 eval 都是 fresh scope，`assembleFreshScope()`），而是 varMap。
- `loadConfigInto()`（`HkJsRuntime.java:9180`）在组装 scope 时读盘 `tz1.H(title)`（`config/<标题>.json`，缺失回 `"{}"`，`tz1.java:222`）并 `putProperty(scope, "config", …)`；但随后 `ensurePluginIn()` eval `plugin.js`，`defineProperty` 把 `config` 数据属性换成 accessor，**盘上的值在冷启动、preRule 尚未跑 `initConfig` 时是不可见的**——getter 只读 varMap。`loadConfig()`（`:9176`）在 `HkJsRuntime` 内**无调用方**。
- 实际链条：进程内第一次进首页/搜索 → preRule 跑 `initConfig({...})` → 值进 varMap → 同进程内之后所有求值读 `config.*` 都有值。进程重启后 preRule 再跑一遍重建。**磁盘 `config/<标题>.json` 是 Java 版的写入目标，但 Java 版在规则求值时被 JS 版盖掉，实践中基本休眠。**
- 我们已有 preRule 流程（`HkExecutor` 各路径 `prepare` 后都调 `evalPreRule()`），只需把 config 存储从 per-eval 改成 per-rule-process-wide。

### 1.3 rc / require 的缓存小时数（`require0`，`HkJsRuntime.java:24405`）

- 缓存目录 `<hkRoot>/libs/<md5(url)>.js` + `<md5>.json` 版本 meta（`m45.g(url)` = md5）。
- 有效期判定（`:24457`，`i` = 小时数，`i2` = 版本要求，默认 0）：
  ```java
  file.exists() && (i >= 0 ? i != 0 && requireVersion >= i2 && now - file.lastModified() <= i * 3600000
                           : requireVersion >= i2)
  ```
  即：`hours < 0`（plain `require` 传 `-1`，`:8359`）→ 只做版本检查，**永久有效**；`hours == 0` → 永不命中缓存；`hours > 0` → 版本通过且 `age <= hours*3600000`。
- `cacheHoursValue`（`:1290`）：`Integer`/`Double` → int，其余 → **24**；`requireCache`（`rc`）缺省第二参时也是 24（`:24487`）。
- 频率（`ANALYSIS.md` §7，4255 条合集全文子串统计）：`config.` 3240 次，`initConfig` 409 次，`rc(` 220 次。

## 2. 现状（错在哪，902c1e8c）

`HkEngine.java` BOOTSTRAP：

```js
var config = {};
function initConfig(o) { if (o && typeof o === 'object') { for (var k in o) { config[k] = o[k]; } } return config; }
function rc(url, hours) { return __hk.require(url); }
```

- `config` 每求值新建：preRule 里 `initConfig` 设的值只在同一次求值（同一 `HkEngine` 实例 / 同一 scope）内可见；首页 preRule 播种后，详情/搜索/懒加载是新的 `HkEngine` 实例，`config.*` 全丢。
- `rc` 第二参 `hours` 被忽略：每次重抓，无缓存。
- 另：`HkEngine` 已有 per-rule 的 `putMyVar`/`getMyVar` 后端——内存 `myVars` + 落盘 `data/<标题>/myvar.json`（`prepare` 时 `loadMyVars` 加载，`putMyVarValue` 立即写盘）。这是本项可直接复用的 per-rule 存储，等价于官方 varMap 的“标题 `@` 键”隔离（隔离单位相同：规则标题），只是多了落盘（见 §3.3 取舍）。

## 3. 方案

### 3.1 config / initConfig：照抄 plugin.js 的 JS 版，存储走现有 per-rule myVar

把 BOOTSTRAP 里 `var config = {}` + `initConfig` 两行换成 plugin.js 同款 IIFE（`windows` 即顶层 `this`，与官方 `(function(windows){...})(windows)` 同形；`getMyVar`/`putMyVar` 是 BOOTSTRAP 里已定义的全局函数）：

```js
(function(windows) {
  function initConfig(obj) {
    if (obj == null || typeof obj !== 'object') { return windows.config; }
    var merged = {};
    try { var s = getMyVar('initConfig', '{}'); if (s) { merged = JSON.parse(s); } } catch (e) {}
    var empty = true;
    try { empty = (JSON.stringify(obj) === '{}'); } catch (e) { empty = true; }
    if (empty) { return windows.config; }
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length; i++) { merged[keys[i]] = obj[keys[i]]; }
    putMyVar('initConfig', JSON.stringify(merged));
    windows.config = merged;
    return windows.config;
  }
  windows.initConfig = initConfig;
  try {
    Object.defineProperty(windows, "config", {
      enumerable: true, configurable: true,
      get: function() {
        if (windows.config0 == null) {
          var c = {};
          try { var s = getMyVar('initConfig', '{}'); if (s) { c = JSON.parse(s); } } catch (e) {}
          windows.config0 = c;
        }
        return windows.config0;
      },
      set: function(v) { windows.config0 = v; }
    });
  } catch (e) { log(e.toString()); }
})(this);
```

语义对照（与官方一致，除 §3.3 注明的两处）：

| 行为 | 官方 | 本实现 |
|---|---|---|
| `initConfig({...})` | 合并进 `getMyVar('initConfig')`（varMap，键 `<标题>@initConfig`），置 `windows.config` | 相同；存储是 `HkEngine.myVars`（键 `initConfig`，文件 `data/<标题>/myvar.json` 隔离，见 §3.3） |
| `initConfig` 非对象/空对象入参 | JS 字面：空对象分支不合并但仍 `putMyVar('{}')`（会清空已存值）；报告 §4.3 定性为“返回现有 config 且不抛错” | **按报告 §4.3**：直接 `return windows.config`，不写、不抛、不清空（见 §3.3） |
| 读 `config.*` | accessor getter：`config0` 为空时从 varMap 懒加载并缓存 | 相同（`config0` 是 scope 级属性，随 fresh scope 重建） |
| `config = {...}` 赋值 | setter 只写 `config0`，不回写 varMap（仅本 scope 可见） | 相同 |
| 直接改 `config.k = v` | 改 `config0` 对象，不回写 varMap；下次 fresh scope 重读 varMap，改动丢失 | 相同 |
| `getMyVar('initConfig')` 直接读 | 返回存的 JSON 字符串 | 相同（`putMyVar` 存的就是 JSON 字符串） |
| 冷启动、preRule 未跑时读 `config` | getter 读 varMap（空）→ `{}`；盘上 `config/*.json` 的值被 accessor 遮住、不可见 | getter 读 myVar（可能因 myvar.json 落盘而有值，见 §3.3）；preRule 每次求值前都跑，会重建/合并，行为一致 |

### 3.2 rc 缓存小时数兑现（本项范围；完整 require 缓存是第 2 项，不碰）

- JS：`function rc(url, hours) { return __hk.requireCache(url, hours); }`；`Bridge` 新增 `requireCache(String url, Object hours)`，hours 解析照 `cacheHoursValue`：`Number` → int，缺失/`Undefined`/非数字 → 24。
- Java：`HkEngine.require(url, params)` 内部走 `require(url, params, cacheHours)`；plain require 传 `-1`（与官方 `:8359` 一致），`rc` 传解析出的小时数。
- 缓存位置与命名照官方 `require0`：`plugins/hk/libs/<md5(url)>.js` + `<md5>.json` meta（`HkStore` 新增 `rcCacheFile()` / `rcCacheMetaFile()` / `saveRcCache()`，md5 小写 hex；写盘失败只记日志、不阻断 require）。
- 有效期判定照官方 `:24457`（版本检查：无版本信息，按 `0 >= 0` 通过；完整版本语义归第 2 项）：
  - `hours < 0` → 文件存在即有效（永久）；
  - `hours == 0` → 永不命中；
  - `hours > 0` → `now - file.lastModified() <= hours * 3600000`。
  命中且读出非空代码时直接 eval，不发网络；未命中时走现有 fetch + HTML/空内容守卫，成功后先写缓存再 eval。
- 边界（**不碰第 2 项**）：取到 HTML/418/空内容时仍按现有行为抛明确错，**不**回退旧缓存（回退是第 2 项）；不做 `isJsCode` 校验（第 2 项）；不做 `proxy`/`proxyRequire`（第 2 项）；`hiker://page/...` 子页面分支与 `require.json` 清单分支（本地 bundled 库优先）保持在缓存检查之前，不变。
- `requiredLibs` 同引擎去重不变；`$.require` 走 `__hk.require`（plain，-1 永久），与官方一致。

### 3.3 取舍与说明（不是第三种语义，是报告结论 + 现有后端决定的）

1. **不写 `config/<标题>.json`**。官方 JS 路径不写盘（Java 版写但被盖掉、实践休眠）；且在我们仓库里 `config/<标题>.json` 已是 `require.json` 清单的存放处（`HkStore.readRequireManifest()`/`saveConfig()`，导入时写入）——`initConfig` 若往同一文件写 JSON 对象，会毁掉清单、导致 `resolveLibFile` 失效。所以只按 JS 版实现，不写该文件。
2. **存储用现有 `putMyVar`/`getMyVar`（myvar.json 落盘），而非新建进程内-only 的 map**。隔离单位与官方相同（规则标题）；落盘是 `putMyVar` 自第 1 步就有的既定行为（“持久变量跨页可见”），不是本项新发明的语义。后果：进程重启后 `config` 在 preRule 重跑前也可读到上次的值（官方冷启动读不到，要等 preRule）；preRule 每次求值前都会重跑并合并，值相同，是官方行为的超集、无冲突。
3. **非对象/空对象入参不清空**（§3.1 表）：按报告 §4.3 的定性结论，不按 plugin.js 字面（字面会在 `initConfig()` 无参时把已存值写成 `'{}'`）。规则正常调用都是 `initConfig({...})`，此分支只影响异常调用。

## 4. 验收

- ① 同求值可见：Node 模拟——mock `getMyVar`/`putMyVar`（进程内 per-title map）+ 官方同款 preRule/find_rule 顺序（`initConfig({pako:'x'})` 后同 scope 读 `config.pako`）应得 `'x'`。
- ② 按标题隔离：Node 模拟——标题 A `initConfig` 后标题 B 的 `config` 为 `{}`，A 的仍在；键冲突（同 key 不同值）互不覆盖。
- ③ rc 小时数：对照官方判定式逐行核对 `isCacheFresh`（-1 永久 / 0 永不命中 / 24 小时边界 `age <= 24*3600000`）；`rc(url)` 缺省按 24；`require` 走 -1。真机网络行为（命中时不发请求）只能等真机/构建后验。
- ④ 零回归：3 条验收规则（`~/workspace/miniapp/test-rules/`：奇优影院♔/童话/臻品fix）经 grep 确认 0 次使用 `initConfig`/`config.`/`rc(`，且本次只改 BOOTSTRAP 里 config 两行 + require 缓存分支（清单/子页面分支不动）；`menu_nav.xml` 仍 6 项。
- 本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；提交前逐行重读改动（返回类型、泛型、三元表达式类型一致是已知翻车点）。

## 5. 回滚

- 单一原子提交 + recovery tag；回滚 = `git revert <提交>`（只动 HkEngine/HkStore 两处 + 本文档，revert 干净）。
- 若 GitHub 构建暴露 Rhino/类型问题：按“每处只允许一处小错修一轮，修不好记 blocked”的规矩处理，不连修。

## 7. 验收记录（2026-11-15 22:36+ 本地做完）

- ①② JS 语义：`/tmp/hk-align1-config-test.js` 从 `HkEngine.java` 原样抽出 BOOTSTRAP 文本块，在 Node vm 里按“每引擎一 fresh scope、myVar 按标题隔离跨 scope 共享”跑，**22 项全过**（同 scope 可见、合并非替换、标题隔离、非对象/空对象不清空、赋值/直接改属性只本 scope 可见、冷启动可读落盘值）。注意：这是 Node 语义校验，不是 Rhino 真跑；Rhino 上 `Object.defineProperty(this,…)` 与官方 plugin.js 同构（同款 IIFE + 同 Rhino 1.7.14），构建后仍需真机复核。
- ③ rc 小时数：`/tmp/hk-align1-fresh-check.js` 把我方 `isCacheFresh` 与官方判定式（`requireVersion=0`、`i2=0` 时版本检查恒真）在 hours ∈ {-1,-5,0,1,24,100} × age ∈ {0,0.5,1,23.99,24,24.01,100,10000} 小时网格上逐点对照，**全部等价**，24 小时整点边界（`<=`）命中。网络行为（命中时不发请求、过期重抓、-1 永久）只能等真机验。
- ④ 零回归：3 条验收规则 grep 确认 0 次使用 `initConfig`/`config.`/`rc(`；本次只改 BOOTSTRAP 里 config 三行 + `require` 缓存分支（子页面/清单分支不动）；`menu_nav.xml` 提交前核对仍 6 项（见提交时检查）。
- 未做：本地无 JDK/Android SDK，未编译；编译检查等推上去之后的 GitHub 构建。`git diff` 已逐行重读（返回类型、泛型、三元表达式：本次无新泛型/三元；重载 `require(String,Object)`/`require(String,Object,int)` 无歧义，`requireCache` 的 `null` params 经 `setImportParam` 的 null 守卫）。

## 8. 不做（第 2–5 项，勿碰）

- require 的 MD5 缓存 + `isJsCode` 校验 + 坏内容回退旧缓存（第 2 项；本项只做 rc/require 的有效期判定与写缓存）；
- `refreshPage` 真实现（第 3 项）、`batchFetch` 改形状（第 4 项）、`storage` 对象 + `getItem`/`setItem` 落盘确认（第 5 项）。
