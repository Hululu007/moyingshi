# 对齐第 3 项：x5 卡点开进站内 WebView，不再进详情页；同页去重

## 用户所见

2026-11-16 08:01–08:03 用户在 v5.6.0-beta-202611152351 上：
- 麻花影院列表里同一张卡重复出现约 10 次，标题是 `UniqueTag@e0e...`；
- 点其中一张卡，详情页报“规则缺少 detail_find_rule”；
- 西瓜影院的“网页”卡点开同样报“规则缺少 detail_find_rule”。

标题串已由第 2 项修掉；本项修点卡去向与重复。

## 规则证据

- 麻花影院：find_rule `js: setResult([{url: "https://v.jczyws.com/",
  col_type: "x5_webview_single",desc: "100%&&float",extra:{floatVideo: true}}])`，
  只出一张卡，`detail_find_rule` 为空。
- 西瓜影院的“网页”卡：同款 x5 卡，`detail_find_rule` 为空。
- 重复：规则只出一张卡，翻页每页都返回同一张被追加，所以约 10 次。

## 星辰源码对照（照搬做法）

星辰 `~/workspace/xingchen-ref`（HEAD 52450c0，真源码）：

1. `app/src/main/java/com/fongmi/android/tv/api/hk/HkRouter.java:1291-1326`
   `dealKind`（注释写明是官方 `ArticleListFragment.dealWithUrl` 顺序）：
   `pics://`→pics、`x5://`→x5、磁力→magnet、图片→image、音视频→video、
   `http(s)://`/`file://`→web。调用处注释（`HkRouter.java:141`）：
   “官方 dealWithUrl：video 直接播放；pics/x5/web/image/magnet 按种类分流，
   均不进 V4（详情页）”。即 x5/web 类点卡不进详情页。
2. 星辰自己不接 X5 引擎：`HkRouter.java:732-734` 注释——
   “咱家不接 X5（他亲定），x5Rule 走系统 WebView 顶替”。
   `x5_webview_single` 在星辰只出现在 `COL_TYPES` 57 码枚举里，
   列表页没有内嵌 WebView 渲染。
3. 结论：照搬做法——x5 卡点开分流到网页，不进详情页；按我们结构改写——
   我们已有 main 源集通用站内 WebView 浏览器 `CatWebActivity`
   （`CatWebActivity.intent(context, url)`），点 x5 卡用它打开卡片 URL。
   不做列表页内嵌 WebView（那是另一档工作，不在这次三处里）。

## 现状

- `HkListFragment.onItemClick`（约 392 行）与 `HkSearchFragment.onItemClick`
  （约 295 行）：`HkActionHandler.handle` 之后直接进 `HkDetailActivity`，
  x5 卡的 `detail_find_rule` 为空即报“规则缺少 detail_find_rule”。
- `HkListHelper.toVods` 把卡片转成 Vod 时丢了 colType（Vod 没有 colType 字段），
  点卡时拿不到 colType。
- 翻页在 `onPageLoaded` 的 append 分支直接 `mAdapter.addAll`，无去重。

## 方案

1. `HkListHelper`：加静态 x5-only 集合（`ConcurrentHashMap.newKeySet()`，
   超 500 条清空防膨胀），`toVods` 里 colType 为 `x5_webview_single` 的卡
   记下 URL；加 `public static boolean isX5Url(String url)`。
   只记 x5，不记全部卡片；key 只用 URL（x5 卡的 URL 本就是网页地址；同一 URL
   在别的规则里若也是卡片，开网页仍是合理去向）。不改 Vod 模型、不改
   `toVods` 签名（调用方有列表页、搜索页、HkWarmup 三处）。
   线程：`toVods` 跑在后台线程、点卡读在主线程，用并发集合。
2. 两处 `onItemClick`：`HkActionHandler` 之后、`HkDetailActivity` 之前加分支——
   `HkListHelper.isX5Url(item.getId())` 为真时
   `startActivity(CatWebActivity.intent(requireContext(), item.getId()))` 并 return。
   动作 URL 优先逻辑不动；普通卡仍进详情页。
3. 去重：`HkListHelper` 加静态 `dedupeByUrl(List<Vod> shown, List<Vod> incoming)`，
   按 `getId()`（即 URL）过滤：已在列表里的不要，页内重复的只留第一张。
   两处 `onPageLoaded` 在 `addAll` 前调用（append 时 shown 取
   `mAdapter.getItems()`，非 append 时 shown 为空）。不改 `VodAdapter`，
   不动正常多分类/多页追加（不同 URL 照常追加）。

## 验收

- 点麻花影院卡进站内 WebView，不再报缺 `detail_find_rule`；点普通卡仍进详情页。
- 麻花影院翻页不再重复同一张卡；不同 URL 的正常翻页追加不受影响。
- 3 条验收规则（奇优影院♔/童话/臻品fix）grep：col_type 全是 movie_3，
  无 x5 卡，`toVods` 行为不变；去重只去掉相同 URL。
- 真机验（只能等构建后）：麻花影院点卡开出 `https://v.jczyws.com/` 网页；
  西瓜影院“网页”卡同理。

## 回滚

revert 本提交。改动只在点卡分支与 `toVods` 记表，不动存储与详情/播放链路。

## Recovery anchor

- 目标：x5 卡点开进站内 WebView；同页相同 URL 的卡只留一张。
- 状态：已做完。`HkListHelper` 加 x5 记表 + `isX5Url` + `dedupeByUrl`；
  两处 `onItemClick` 在动作 URL 处理后、详情页前加 x5 分支进 `CatWebActivity`；
  两处 `onPageLoaded` 在 `addAll` 前调去重；`git diff --check` 通过。
  3 条验收规则 col_type 全是 movie_3、0 处 x5，`toVods` 行为不变；
  去重只去掉相同 URL。
- 文件：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkListHelper.java`，
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java`，
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkSearchFragment.java`，
  `docs/miniapp-xingchen-3-x5-webview.md`。
- 下一步：等构建 + 真机验麻花影院点卡开出网页、翻页不再重复。
