# 对齐第 2 项：缺失的 JS 属性不再漏出 UniqueTag 字符串（修麻花影院卡片标题）

## 用户所见

2026-11-16 08:03 用户在 v5.6.0-beta-202611152351 上测麻花影院：
列表页每张卡片标题栏显示 `UniqueTag@e0e...`（截断的
`org.mozilla.javascript.UniqueTag@e0e...`），点开详情页标题栏也是这串。

## 规则证据

合集 `~/workspace/user/files/4255_____10_q31w.json`，title `麻花影院`，video，
url `hiker://empty##https://v.jczyws.com/`，col_type `movie_3`。find_rule 只有一行：

```js
js: setResult([{url: "https://v.jczyws.com/",col_type: "x5_webview_single",desc: "100%&&float",extra:{floatVideo: true}}])
```

卡片没有 `title` 字段。`desc` 的 `100%&&float` 是规则自己写的 x5 卡描述，
不是乱码，不动它。列表里同一张卡重复出现约 10 次是翻页追加所致，见第 3 项去重；
卡片本身只有一张。

## 根因

我们的 `HkEngine.cardFromJsObject` 经 `jsString` 读字段，`jsString` 只判了
`value instanceof Undefined`：

```java
private String jsString(Scriptable obj, String name) {
    Object value = ScriptableObject.getProperty(obj, name);
    if (value == null || value instanceof Undefined) return "";
    return Context.toString(value);
}
```

属性缺失时 `ScriptableObject.getProperty` 返回的是 `UniqueTag.NOT_FOUND`，
不是 `Undefined`。`NOT_FOUND instanceof Undefined` 为假，于是
`Context.toString(NOT_FOUND)` 打出 `org.mozilla.javascript.UniqueTag@e0e...`，
一路进 Vod 标题、进详情页标题栏。

## 星辰源码对照（照搬做法）

星辰 `~/workspace/xingchen-ref`（HEAD 52450c0，真源码）：
`app/src/main/java/com/fongmi/android/tv/api/hk/HkJsRuntime.java` 约 744 行
`scriptableString`（及其同款 `scriptableCallback`）：

```java
Object v = ScriptableObject.getProperty(o, key);
if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) return "";
return Context.toString(v);
```

即缺失（NOT_FOUND）与未定义（Undefined）同按缺失处理。照搬这个三条件判断；
按我们结构改写——只改我们已有的 `jsString` 与同类取属性点，不引星辰的整套工具类。

## 方案

`HkEngine.java`：

1. `jsString`：条件补 `value == Scriptable.NOT_FOUND`。卡片标题/url/图片/desc/
   col_type 五处字段同受益；麻花影院这张卡标题回 `""`，`toVods` 里 x5 卡
   按现有逻辑回落标题“网页”。
2. 同类取属性点一并修（只修会漏到用户可见面或进请求/弹框的）：
   - `batchItemFromJs` 的 item url：缺失回 `null`（原只判 Undefined），否则
     UniqueTag 串会被当 URL 去抓，报错里带出这串。
   - `fetchOptsFromNativeObject` 的 headers 值：缺失跳过。
   - `formParams` 的表单值：缺失跳过。
   - `stringList`（select/confirm 弹框选项）：缺失跳过，否则 UniqueTag 串
     会出现在弹框选项里。
   - `fetchOptsFromNativeObject` 的 body：缺失与 Undefined 同按 `null` 处理。

顺查后明确不动的（已核对，不会漏）：
- `log()`：只写 logcat，不进用户可见面。
- `jsTruthy`：非 Boolean/Number/String 一律回 false，NOT_FOUND 安全。
- `putVar` 对象形的 key：只进内部 vars 表，不显示、不进卡片。
- `select()`/`optString`/`rawToString`/`evalExpression`：均有
  `instanceof` 守卫，NOT_FOUND 走回落分支。
- `HkParser`/`HkExecutor`/`HkSubpage`/`HkActionHandler`：无
  `getProperty` + `Context.toString`/`String.valueOf` 的点。

## 验收

- `jsString` 与上述五处均含 `NOT_FOUND` 判断（grep 核对）。
- 麻花影院卡片：标题不再是 UniqueTag 串（x5 卡按现有逻辑回落“网页”，
  见第 3 项点卡进站内 WebView）。
- 3 条验收规则（奇优影院♔/童话/臻品fix）grep：卡片字段齐全，
  `jsString` 的行为对有值字段不变（`Context.toString` 原样），零回归。
- 真机验（只能等构建后）：麻花影院列表标题显示“网页”，详情页标题栏不再是
  UniqueTag 串。

## 回滚

revert 本提交。改动只在取值守卫条件，不动存储与结构。

## Recovery anchor

- 目标：缺失的 JS 属性按缺失处理，不再漏出 UniqueTag 字符串。
- 状态：已做完。`jsString` + batchFetch url/headers/body + 两处表单 params
  + `stringList` 共 7 处加 `NOT_FOUND` 守卫（实施中发现表单 params 循环有
  两处同款：`formParams` 与请求体构造，两处都修了）；`git diff --check` 通过。
  3 条验收规则卡片字段齐全，`jsString` 对有值字段行为不变。
- 文件：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`
  （7 处守卫条件），`docs/miniapp-xingchen-2-uniquetag.md`。
- 下一步：等构建 + 真机验麻花影院列表标题显示“网页”。
