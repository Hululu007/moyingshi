# 对齐第 5 项：storage 对象 + getItem/setItem 落盘确认

> Recovery anchor：目标是把海阔小程序的持久 KV 按官方补齐：`storage0`
> 对象（getItem/setItem/getPublicItem/setPublicItem/getVar/putVar/
> getMyVar/putMyVar，JSON 自动转换）+ 全局 `getPublicItem/setPublicItem/
> clearPublicItem` + `getItem/setItem` 语义对齐（落盘 `kv.json`、按标题
> 隔离、值类型与缺省值、三参跨规则读写）。只改
> `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`、
> `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkStore.java`（+
> 本文档）。本地提交、不推、不编、不动版本记录。验收：① 落盘路径/隔离/
> 类型/缺省值与官方一致 ② 同规则跨求值可读、不同规则互不串 ③ 第 1 项
> config 共享不受影响 ④ 3 条验收规则零回归（均不用 storage/getItem/
> setItem）。下一步：写代码 → 逐行重读 → guard finish 提交 + tag。

## 0. 完成句

`storage0` 对象与 `getPublicItem/setPublicItem/clearPublicItem` 按官方
plugin.js / HkJsRuntime 补齐；`getItem/setItem` 落盘改到每规则
`plugins/hk/data/<标题>/kv.json`（旧 `items.json` 只作一次性迁移读），
值类型与缺省值按官方 `mustBeString/getString` 对齐，三参跨规则读写
（未知规则读回 `""`、写是空操作）；myVar（第 1 项 config 共享）与 kv
仍是两套存储、互不干扰；3 条验收规则零回归。

- 允许路径：`docs/miniapp-align-5-storage.md`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkStore.java`
- 保护路径：HEAD 已含第 1–4 项（b7052638 等），一律不动；TV/leanback、
  BaseLoader、底栏（menu_nav.xml 6 项）不动；`myvar.json`（第 1 项）
  的读写路径不动。
- 当前时间：2026-11-15 23:15 CST；预计 60 分钟，约 2026-11-16 00:15 完成。
- 最廉价决定性验证：本地无 JDK/Android SDK，编不了；以逐行重读 diff +
  静态核查（返回类型/泛型/三元类型一致）+ node 校验 BOOTSTRAP JS 语法
  为准，编译检查等推上去后的 GitHub 构建。

## 1. 证据（先读透的权威依据）

### 1.1 官方 `storage0`（`~/workspace/hk-ref/assets-extracted/assets/plugin.js` 281–347 行）

- `toJsonStr(obj)`：null → `''`；非字符串 → `JSON.stringify(obj)`；
  字符串原样。
- `toJsonObj(a)`：falsy → 原样返回；以 `{` 开头 `}` 结尾或 `[` 开头 `]`
  结尾 → 试 `JSON.parse`，失败回原串；其余原样返回。
- `windows.storage0 = { getItem, setItem, getPublicItem, setPublicItem,
  getVar, putVar, getMyVar, putMyVar }`：读 = 全局函数 + `toJsonStr(默认值)`
  再 `toJsonObj`；写 = `toJsonStr(值)` 再调全局函数。**只有 `storage0`，
  没有 `storage`**（全文件只此一处出现 `storage`，第 308 行）。
- `storage0` 里没有 `clearPublicItem` / `hasVar` / `clearVar`（后两者是
  `globalMap0` 的，不补）。

### 1.2 官方全局 getItem/setItem（`HkJsRuntime.java`）

- 注册：`setItem` → `lambda$registerApi$31`（约 7893–7905 行，`i17=23`
  已核对），`getItem` → `lambda$registerApi$32`（约 7907–7920 行，
  `i18=24` 已核对）；公开签名 `getItem(key, def, ruleTitleKey)`（26316 行）。
- `mustBeString`（9896–9905 行）：`argsNativeObjectAdjust` 后不是 String
  → 记日志 + 返回 null。`argsNativeObjectAdjust`（829–872 行）把
  NativeObject 转成 LinkedHashMap、NativeArray 转成 ArrayList、
  ConsString 转 String——所以**直接调全局 `setItem(k, 对象/数字/布尔)`
  时值是 null，`kv.put(k, null)` 后 Gson 落盘丢 null，键等于不存在，
  下次 `getItem` 回默认值**。对象/数字能存活的唯一路径是经 `storage0`
  先 `JSON.stringify`。
- `getString`（3411–3417 行）：同款，非字符串 → null。所以直接调全局
  `getItem(k, 非字符串默认值)` 且键缺失时回 **null**（不是 `""`）；
  一参 `getItem(k)` 缺失时同样回 null。
- `setItemNow(key, value, title)`（25082–25109 行）：key 或 title 为空 →
  空操作；title == 当前规则 → 写内存 `this.kv` + `saveKv()`；否则规则
  存在（`tz1.A(title) != null`）才读该规则的 `kv.json` 改写，不存在 →
  空操作。
- `getItemNow(key, def, title)`（3266–3280 行）：key/title 为空 → 回 def；
  `kvMapForTitle(title)` 为 null（规则不存在）→ 回 **`""`**（不是 def）；
  键缺失 → 回 def。
- `kvFile()`（4531–4535 行）：`tz1.x(title)/kv.json`；`tz1.x`（`tz1.java`
  1280–1286 行）= `filesDir/plugins/hk/data/<T(title)>/`。
  `kvMapForTitle`（4537–4549 行）：当前规则回内存 `this.kv`，否则读盘。
- `readKvFile`（10622–10637 行）/ `writeKvFile`（25526–25537 行）/
  `saveKv`（24877–24887 行）：整文件 Gson `Map<String,String>`、UTF-8、
  写前 `mkdirs`；读失败回空 map。
- `checkSetItemSize`（1401–1412 行）：**只记日志、不拦截**——整表 UTF-8
  超 1MB 或单键/单值超 512KB 只 `x23.d`，照样写。
- `resolveRuleTitle`（24603–24605 行）：三参为空 → 当前规则标题；
  `currentRuleTitle()`（1773–1781 行）= 当前规则标题。
- 加载时卫生（`loadKv` 约 9214–9230 行）：值是毒串（`isKvPoison`）或
  字面量 `"undefined"` 的键一次性清掉并备份到 `kv-poison-backup/`，
  写 `kv-poison-cleaned.marker` / `kv-undefined-cleaned.marker`；
  解析失败把 `kv.json` 拷成 `kv.json.bad`。**本项不做**（见 §6）。

### 1.3 官方公共 KV（`HkJsRuntime.java`）

- `publicKvFile()`（10497–10499 行）：`getFilePath("hiker://files/rules/_item00.json")`；
  `getFilePath`（3090–3100 行）把 `hiker://files/` 映到外部
  Documents 目录，即 `<外部Documents>/rules/_item00.json`，**全规则共享
  一个文件**。
- `setPublicItem` → `lambda$registerApi$62`（约 8474–8482 行）：去
  tombstone、写内存 `publicKv`、调 `savePublicKv()`；`getPublicItem` →
  `lambda$registerApi$63`（约 8484–8490 行）：命中返回值、缺失回默认值
  （`mustBeString`，非字符串默认值 → null）；`clearPublicItem` →
  `lambda$registerApi$64`（约 8492–8500 行）：加 tombstone、删内存键、
  `savePublicKv()`。
- `savePublicKv()`（24890–24920 行）：先读盘合并（`putIfAbsent`，跳过
  tombstone 键），再整文件写回；`loadPublicKv()`（约 9266–9290 行）：
  文件不存在时从旧版公共文件迁移一次。
- **本项唯一有意偏离**：公共文件放内部 `filesDir/plugins/hk/rules/_item00.json`
  而不用外部 Documents——规则看不到路径差异；内部存储与其它 hk 数据
  同根、无外部存储不可用的边角。文件名 `_item00.json` 与官方一致。

### 1.4 规则合集 `~/workspace/user/files/4255_____10_q31w.json`（4255 条）

- 规则写的是 **`storage0.`，不是 `storage.`**：`storage.getItem(` /
  `storage.setItem(` 均为 **0 次**；`storage0` 出现 5110 次，其中
  `storage0.getItem(` 1999、`storage0.setItem(` 781、
  `storage0.getMyVar(` 1151、`storage0.putMyVar(` 1021、
  `storage0.getVar(` 53、`storage0.putVar(` 41、
  `storage0.getPublicItem(` 7、`storage0.setPublicItem(` 2。
- 全局 `getItem(` 3871 次、`setItem(` 2088 次（文本计数，含嵌套调用）；
  `getPublicItem(` 69、`setPublicItem(` 25、`clearPublicItem(` 5。
- 全局三参 `getItem/setItem`：**0 次真实使用**。之前扫到的 45 处三参
  `setItem` 全是某条规则自带的 `LocalStorage` 类方法
  `setItem(key, value, expiredTimeMS)`（本地过期缓存，与全局无关）；
  4 处三参 `getItem` 是嵌套逗号误读（如
  `getItem(HSCKStudy.hostKey, getMyVar(HSCKStudy.hostKey, ""))`）。
  三参只按官方代码实现，不以合集用量背书。
- 全局 `setItem` 第二参形态（1466 处分类）：字符串字面量 444、
  标识符/表达式 1009（多为 `String(...)` 包裹或变量）、对象字面量 **1**、
  `JSON.stringify(...)` 4、`storage0.getItem(...).xxx` 5。直接传对象
  极罕见，`mustBeString` 的 null 语义按官方照做无实际回归面。
- 典型 `storage0` 用法（与官方 JSON 转换一致才通）：
  `storage0.getItem("white", [])` 后 `.includes(url)`（要回真数组）、
  `storage0.setItem("CurrentConfig", configc)`（要把对象存成 JSON 串）、
  `storage0.getMyVar("path", [])` 后 `.join("/")`。

### 1.5 ANALYSIS.md 的说法核对

- §6.2 表：`getVar/putVar/getMyVar/putMyVar` = 静态 varMap、**进程内、
  不落盘**；`getItem/setItem` = 磁盘每规则 `kv.json`、跨进程。**两者是
  两套存储，不要合并**。
- 第 5 项行（354 行）：“`storage` 对象、`getPublicItem` 缺”——以代码为准
  是 `storage0` 对象缺 + `getPublicItem/setPublicItem` 全缺。

## 2. 现状（我们已有的实现）

- `HkEngine.BOOTSTRAP` 只有全局 `getItem(k, d)` / `setItem(k, v)`，
  **没有 `storage0`**、没有 `getPublicItem/setPublicItem/clearPublicItem`。
  规则调 `storage0.*` 会报 ReferenceError；调 `getPublicItem` 会报
  未定义。`storage0.getItem("white", [])` 这类要回真数组的用法全不通。
- `Bridge.getItem(String, Object)`：默认值 `Context.toString(def)`——
  非字符串默认值（如 `[]`）会被转成字符串而非官方的 null；
  一参版缺失回 `""` 而非官方的 null。
- `Bridge.setItem(String, Object)`：`Context.toString(value)`——对象值
  存成 `"[object Object]"`，数字存成 `"5"`；官方是存 null（键等于不存在）。
- `HkEngine.getItem/setItem`：后端是 `HkStore.readItems/saveItems`，
  文件名 **`items.json`**（官方 `kv.json`）；无三参跨规则读写；
  `setItem` 允许空 key（官方空操作）；无 `checkSetItemSize` 式日志。
- `HkStore`：`data/<标题>/items.json` + `data/<标题>/myvar.json`；
  无公共 KV 文件。
- 第 1 项的 config：`putMyVar/getMyVar` 内存 + `data/<标题>/myvar.json`
  （官方 varMap 是纯内存；我们因引擎按求值新建才落盘，这是第 1 项已定
  的适配，**本项不动**）。myVar 与 kv 已是两套文件，界线清楚。

## 3. 方案

### 3.1 `HkStore.java`

- `readItems(title)`：先读 `kv.json`；结果为空且旧 `items.json` 文件存在
  时读旧文件（一次性迁移读；下次 `setItem` 即写 `kv.json`，旧文件原地
  保留不删）。
- `saveItems(title, json)`：改写 `kv.json`。
- 新增公共 KV 后端：`publicKvFile()` =
  `new File(new File(dir(), "rules"), "_item00.json")`（写前 `mkdirs`）；
  `readPublicItems()`（缺失回 `""`）、`savePublicItems(json)`。
- 其余（`readMyVars/saveMyVars`、目录与文件名归一化）不动。

### 3.2 `HkEngine.java` —— BOOTSTRAP（JS 层，照官方 plugin.js）

- `getItem` / `setItem` 改透传三参：`function getItem(k, d, t) {
  return __hk.getItem(k, d, t); }`、`function setItem(k, v, t) {
  __hk.setItem(k, v, t); }`。
- 新增全局：`getPublicItem(k, d)` / `setPublicItem(k, v)` /
  `clearPublicItem(k)`（只全局，`storage0` 里不加 `clearPublicItem`，
  与官方 plugin.js 一致）。
- 新增 `toJsonStr` / `toJsonObj` + `var storage0 = { getItem, setItem,
  getPublicItem, setPublicItem, getVar, putVar, getMyVar, putMyVar }`，
  逻辑与官方 plugin.js 相同；两处为适配我们引擎的必要改写（见 §3.4）：
  `toJsonObj` 先判 `typeof a !== 'string'` 直接返回（我们的
  `getVar/getMyVar` 可能返回真对象，官方只返回字符串）；
  `{`/`[` 判断用 `charAt` 而不用 `startsWith/endsWith`（ES5 写法，
  免 Rhio ES6 方法依赖）。只用 `var` / `function`，不用 `let`/箭头函数。

### 3.3 `HkEngine.java` —— Bridge 与引擎（Java 层）

- `Bridge.getItem(String key, Object def)`：def 是 CharSequence →
  当字符串；null/Undefined/其它 → **null**（官方 `getString` 语义）；
  一参版调 `engine.getItem(key, null)`。
  新增 `getItem(String key, Object def, Object ruleTitle)` 三参版
  （title null/Undefined → 当前规则）。
- `Bridge.setItem(String key, Object value)`：值是 CharSequence →
  存 `value.toString()`；null/Undefined/数字/布尔/Scriptable →
  **删键**（官方存 null → Gson 落盘丢 null，键等于不存在；我们直接
  `remove`，可观测结果相同）。新增三参版（同款，title 透传）。
- `Bridge.getPublicItem/setPublicItem/clearPublicItem`：同款字符串规则
  （值非字符串 → 删键；默认值非字符串 → null）。
- `engine.getItem(key, def)`：key 为空 → 回 def（def 可为 null）；
  其余不变（缺失回 def）。
- 新增 `engine.getItem(key, def, ruleTitle)`：title 为空或等于当前规则
  → 走本规则缓存；规则不存在（`HkStore.exists`）→ 回 **`""`**；
  存在 → 现读该规则 `kv.json` 解析，命中返回值、缺失回 def。
  新增 `static parseItems(String json)`（`loadItems` 改用它，消重复）。
- `engine.setItem(key, value)` 改走 `setItem(key, value, null)`：
  空 key 空操作；value 为 null → 删键；title 为空或等于当前规则 →
  写本规则缓存 + `saveItems`；否则规则不存在 → 空操作，存在 → 现读
  现改现写该规则 `kv.json`。写时加官方同款只记日志的尺寸检查：
  单值 UTF-8 超 512KB 或整表 JSON 超 1MB 记 `Log.d`，不拦截。
- 新增公共 KV 引擎态：`publicItems` map + `publicItemsLoaded`，
  `loadPublicItems()`（首次访问现读文件）、`getPublicItem(key, def)` /
  `setPublicItem(key, value)`（value null → 删键，立即写盘）/
  `clearPublicItem(key)`。
- Bridge 类注释更新：持久 KV 一节补 `storage0` 与公共 KV 的说明。

### 3.4 有意偏离官方之处（均有原因，写进代码注释）

1. 公共文件放内部 `filesDir/plugins/hk/rules/_item00.json`，不用官方的
   外部 Documents（见 §1.3）。
2. `myvar.json` 仍落盘（第 1 项已定适配，官方 varMap 纯内存）；myVar
   与 kv 不合并。
3. `toJsonObj` 的 `typeof` 守卫与 `charAt` 写法（见 §3.2）。
4. 不做 `sanitizeKvPoisonOnce / sanitizeKvUndefinedOnce`、
   `kv.json.bad` 备份、tombstone（见 §6）。

## 4. 验收

- ① 路径/隔离/类型/缺省值：逐项对 §1.2/§1.3 的官方行号核对；
  `node --check` 校验抽出的 BOOTSTRAP JS 语法（无 JDK，只能语法级）；
  逐行重读 diff（返回类型、泛型、三元类型一致是已知翻车点）。
- ② 跨求值/跨规则：代码级——`setItem` 写盘是同步整文件写，
  `prepare()` 每次重载 `items`；不同规则走不同 `data/<标题>/kv.json`；
  三参未知规则读回 `""`、写是空操作（`HkStore.exists` 把关）。
  真机才能跑的留给 §6。
- ③ config 不受影响：`initConfig` / `config` accessor / `myvar.json`
  路径零改动；`storage0.getMyVar/putMyVar` 走同一 `myVars` map，
  与 `config` 共用后端、不抢键（`initConfig` 键与 kv 键不在同一文件）。
- ④ 3 条验收规则零回归：三条规则全文 grep `storage0|getItem|setItem|
  getPublicItem|setPublicItem` 均为 **0 次**；BOOTSTRAP 新增内容只加
  全局名，不改现有函数行为（`getItem/setItem` 只多透传一个三参，
  两参调用走同一重载）。

## 5. 回滚

- 单一提交 + recovery tag；回滚 = `git revert <提交>`（或 reset 到
  tag 前）。旧 `items.json` 数据不受影响（`readItems` 仍会迁移读，
  回滚后继续读 `items.json`）。
- 注意：回滚后新写的 `kv.json` 会被旧代码忽略（旧代码只认
  `items.json`），已用 `storage0`/公共 KV 的规则数据会丢——测试版
  阶段可接受，写进汇报。

## 6. 诚实限定（只能等真机 / 明确不做的）

- 本地无 JDK/Android SDK，**编不了**：Rhino 重载决议（`__hk.getItem(k, d, t)`
  三参透传时 Undefined 的落点）、`storage0` 在真机 scope 里的实际
  行为、跨求值/跨进程读写，只能等推上去后的 GitHub 构建 + 真机验。
- 未对齐（官方有、合集无用量、本项不做）：`sanitizeKvPoisonOnce` /
  `sanitizeKvUndefinedOnce`（毒串与 `"undefined"` 一次性清理 + 备份）、
  `kv.json` 解析失败拷 `.bad`、公共 KV 的 tombstone 与 `savePublicKv`
  的读盘合并（我们的公共 KV 是每次求值现读文件，无跨进程合并问题）。
- `storage0.getVar/putVar` 走的仍是我们的 `vars`（存真 JS 值，官方存
  字符串）；经 `storage0` 读写时 `toJsonStr` 保证落到 `vars` 里的是
  字符串，与官方一致；直接 `putVar(k, 对象)` 仍存真对象（第 1 步已定
  行为，不动）。
- 公共 KV 文件位置与官方不同（内部 vs 外部 Documents，见 §1.3）。

## 7. 验收记录（2026-11-15 23:40 CST，本地提交前）

- `node --check` 抽出的 BOOTSTRAP（152 行）：通过。
- node 功能 harness（`/tmp/hk-test.js`，`__hk` 按 Java 层语义打桩）：
  **16/16 通过**——BOOTSTRAP 求值不抛错；`storage0` 数组/对象往返、
  缺省值、纯字符串原样、坏 JSON 回原串；全局 `getItem` 一参缺失回 null；
  `getMyVar/getVar` 经 `storage0` 读写；公共 KV 读写 + `clearPublicItem`；
  `storage0` 恰好 8 个方法。
- 括号平衡：与 HEAD 对比增量，HkEngine `{`+22/`}`+22、`(`+100/`)`+100；
  HkStore `{`+5/`}`+5、`(`+26/`)`+26、`[`+1/`]`+1，全部平衡。
- 逐行重读 diff：返回类型/泛型/三元类型一致；`getItem/setItem` 三参
  重载按元数区分，无歧义；`initConfig` / `config0` / `myvar.json`
  代码零改动（diff 里 2 处命中只是未改动的上下文行）。
- 3 条验收规则 grep `storage0|getItem|setItem|getPublicItem|
  setPublicItem|clearPublicItem` 均为 0 次；`menu_nav.xml` 仍 6 项，
  未进 diff。
- 未验（等推上去后的 GitHub 构建 + 真机）：Rhino 重载决议、
  `storage0` 在真机 scope 里的实际行为、跨求值/跨进程读写、三参
  跨规则读写。
