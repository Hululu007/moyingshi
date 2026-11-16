# 对齐第 1 项：桥接返回的字符串转真 JS 字符串（修韩剧TV.Q find_rule#19 replace 报错）

## 用户所见

2026-11-16 08:01 用户在 v5.6.0-beta-202611152351 上测韩剧TV.Q，列表页报：

> 加载失败
> JS 执行失败：对应于 JavaScript 参数类型 (object,string) 的 Java 方法
> java.lang.String.replace 的选择不明确。建议的方法是：
> class java.lang.String replace(java.lang.CharSequence,java.lang.CharSequence)
> class java.lang.String replace(char,char) (find_rule#19)

## 规则证据

合集 `~/workspace/user/files/4255_____10_q31w.json`，title `韩剧TV.Q`，video，
url `hiker://empty##http://www.diggold.net/vod/taiju-fypage.html`。find_rule 第 19 行：

```js
true_url = true_url.replace(/type\/(.*?)\//, 'show/'+ '$1-----------/')
    .replace('---/', page + '---/').replace(/(---\d+\/)/, page + '$1')
```

其中第 16 行 `true_url = getVar(MY_RULE.url, MY_URL)`。

## 根因

- 我们的 `Bridge.getVar(String, Object)`（`HkEngine.java:756`）声明返回 `Object`。
  字符串经它回 JS 时，Rhino 按静态类型 Object 包成 `NativeJavaObject` 包着的 Java String，
  不是真 JS 字符串。`.replace(正则, ...)` 于是按 Java 方法解析，在
  `replace(CharSequence, CharSequence)` 与 `replace(char, char)` 之间无法选择而抛错。
  字符串实参的 `.replace('---/', ...)` 不报错，因为两个重载都能接字符串时 Rhino 能定；
  正则对象两个重载都接不了，才报“选择不明确”。
- `MY_URL` 本身经 `getPageUrl()`（声明返回 `String`）是真 JS 字符串，但过一次
  `getVar(..., MY_URL)` 就被重新包起来，所以第 19 行第一个 `.replace(/.../` 就炸。

## 星辰源码对照（照搬做法）

星辰 `~/workspace/xingchen-ref`（HEAD 52450c0，真源码）：
`app/src/main/java/com/fongmi/android/tv/api/hk/HkJsRuntime.java` 约 12832 行，
STRING 型方法包装生成：

```java
"    return retStr == null ? retStr : retStr + '';\n"
```

即 Java 方法返回的字符串一律 `+ ''` 转成真 JS 字符串。oktv 反编译
`~/workspace/hk-ref/src/sources/.../HkJsRuntime.java` 同款写法（`retStr + ''`）。
官方另有 `argsNativeObjectAdjust`（`NativeJavaObject` → `unwrap()`）管回 Java 的方向，
本次只修 JS 侧的返回方向，不动入参方向。

结论：照搬做法——返回值转 JS 字符串；按我们结构改写——我们不生成包装函数，
而是在已有的 BOOTSTRAP JS 包装层里加一个 `__jsStr` 帮手套在返回值上。
不整篇搬 HkJsRuntime，不改 Bridge 的 Java 签名。

## 方案

`HkEngine.java` 的 BOOTSTRAP（`__unsupported` 之后）加：

```js
function __jsStr(v) { if (v == null || typeof v === 'string') return v; try { if (v instanceof java.lang.String) return String(v); } catch (e) {} return v; }
```

只转确是 `java.lang.String` 的值；数字/布尔/对象/数组/null/Undefined 一律原样返回。
`instanceof java.lang.String` 在标准 Rhino 作用域可用（官方生成代码里就用
`retStr instanceof java.io.InputStream`）；`java` 万一不可见时抛出的
ReferenceError 由 try/catch 兜住，帮手退化成直通，不引入新错。

套 `__jsStr(...)` 的位置（只改返回值，不改入参）：
`fetch`、`post`、`request`、`getUrl`、`getHome`、`getVar`、`getMyVar`、`getParam`、
`getItem`、`getPublicItem`、`readFile`、`md5`、`base64Encode`、`base64Decode`，
以及 `MY_URL`、`MY_HOME`、`MY_TYPE`、`MOBILE_UA`、`PC_UA` 五处赋值。
`MY_PAGE` 是 int，不动；`MY_RULE`/`MY_PARAMS` 是 `JSON.parse` 的结果，不动；
`parseDom`/`pdfh`/`pdfa` 返回的是选择器结果（非字符串标量），不动。

## 验收

- `node --check` 抽出的 BOOTSTRAP 文本通过。
- node 模拟韩剧TV.Q 第 19 行表达式：对真 JS 字符串跑通三段 `.replace`（正则一段 +
  字符串两段），结果与预期一致。
- 3 条验收规则（`~/workspace/miniapp/test-rules/` 奇优影院♔/童话/臻品fix）：
  find_rule 里 `getVar`/`getMyVar` 均为 0 次；臻品fix 全规则只有 1 处 `.replace(/`，
  且作用于 JS 字符串（帮手直通，行为不变）。帮手只改变“包着 Java String 的值”，
  之前这类值调正则 replace 是直接抛错的，没有任何规则能依赖抛错行为，故零回归。
- 真机验（只能等构建后）：韩剧TV.Q 列表出卡；再找一条合集里同样 `.replace(/.../` 的
  规则核对。

## 回滚

revert 本提交。改动只在 BOOTSTRAP 字符串常量内，不动 Java 代码，不动存储。

## Recovery anchor

- 目标：修韩剧TV.Q find_rule#19 的 String.replace 重载歧义报错。
- 状态：已做完。BOOTSTRAP 加 `__jsStr`，19 处包装/赋值套用；`node --check` 通过；
  node 模拟第 19 行三段 `.replace` 在真 JS 字符串上跑通；3 条验收规则 grep：
  find_rule 里 getVar/getMyVar 均为 0 次，臻品fix 全规则 1 处 `.replace(/` 作用于
  JS 字符串，帮手直通、行为不变。
- 文件：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`
  （BOOTSTRAP 内 `__jsStr` + 14 个包装函数 + 5 处赋值），
  `docs/miniapp-xingchen-1-replace.md`。
- 下一步：等构建 + 真机验韩剧TV.Q 列表出卡。
