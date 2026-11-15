# 对齐第 3 项：refreshPage 真实现

> Recovery anchor — 目标：把海阔小程序的 `refreshPage()` 从空操作改成官方语义（置位 + 事件驱动当前页重求值，200ms 节流，支持 scrollTop 回顶）；只做这一项，不碰其余 4 项；本地提交、不推、不编。
> 验收：① 规则 JS 里调 `refreshPage()` 后当前页（列表/详情/搜索）会重求值刷新；② 200ms 内多次调用只触发一次重求值；③ 回顶语义与官方一致（无参/非布尔参回顶，`false` 不回顶）；④ 3 条验收规则零回归（奇优/童话不用 refreshPage；臻品fix 只在点击懒规则里用，走原有 outcome 路径，不双重刷新）。
> 当前状态：main = 78667dd9（第 1、2 项已在本地，未推），工作区干净；本文档先写，代码未改；下一步：guard start → 改 HkEngine/HkExecutor/三 Fragment → 逐行重读 → guard finish 提交 + recovery tag。

- 完成句：墨影视海阔小程序的 `refreshPage()` 按官方语义实现为置位 + 事件驱动当前页重求值（200ms 节流、可回顶、防重入），本地提交完成。
- 允许路径：`docs/miniapp-align-3-refresh-page.md`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRefreshPageEvent.java`（新建）、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkExecutor.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkDetailFragment.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkSearchFragment.java`。
- 保护：TV/leanback 不动，BaseLoader 不动，底栏仍 6 项（`app/src/mobile/res/menu/menu_nav.xml`，提交前核对）；不推、不编、不动版本记录空间；第 1、2 项已做完的改动不动。
- 本地时间：2026-11-15 22:49+ CST；预计：文档 15 分钟 + 实现 40 分钟 + 校验提交 20 分钟，约 23:50 前完成。
- 最便宜的决定性验证：本地无 JDK/Android SDK，编不了。逐行重读改动（返回类型、泛型、三元表达式类型一致是已知翻车点）+ grep 核对 3 条验收规则的 refreshPage 用法与调用形态分布；编译检查等推上去之后的 GitHub 构建，真机行为等用户实测。

## 1. 证据（官方实现，读透再动手）

来源：`~/workspace/hk-ref/ANALYSIS.md` §6.7 + `~/workspace/hk-ref/src/sources/com/fongmi/android/tv/api/hk/HkJsRuntime.java` + `~/workspace/hk-ref/src/sources/defpackage/fe3.java` + `~/workspace/hk-ref/src/sources/defpackage/vr1.java`（列表页 Fragment 订阅方）。

### 1.1 JS 入口：`lambda$registerApi$138`（HkJsRuntime.java:5739-5748）

```java
public /* synthetic */ Object lambda$registerApi$138(Object[] objArr) {
    this.refreshRequested = true;
    this.refreshToTop = refreshScrollTop(objArr);
    long jCurrentTimeMillis = System.currentTimeMillis();
    if (jCurrentTimeMillis - this.lastRefreshPageAt >= 200) {      // 200ms 节流
        this.lastRefreshPageAt = jCurrentTimeMillis;
        EventBus.getDefault().post(new fe3(this.refreshToTop));     // 通知页面重载
    }
    vn2.b(TAG).m("refreshPage(%s) requested", Boolean.valueOf(this.refreshToTop));
    return null;
}
```

即：**置位（refreshRequested + refreshToTop）无条件；事件只在距上次发事件 ≥200ms 时发**。节流拦的是事件，不是置位。

### 1.2 scrollTop 语义：`refreshScrollTop`（HkJsRuntime.java:10671-10680）

```java
private static boolean refreshScrollTop(Object[] objArr) {
    if (objArr == null || objArr.length <= 0) return true;
    Object obj = objArr[0];
    if (obj instanceof Boolean) return ((Boolean) obj).booleanValue();
    return true;
}
```

无参 → 回顶；布尔参 → 按值；其他类型 → 回顶。语料分布（`4255_____10_q31w.json` 全文子串统计）：`refreshPage()` 4759 次、`refreshPage(false)` 3363 次、`refreshPage(true)` 1063 次——三分之一多的调用传 `false`（不回顶），重载必须分清，不能一律回顶。

### 1.3 订阅方：`vr1.onRefreshPage`（vr1.java:2662-2679，ThreadMode.MAIN）

```java
@Subscribe(threadMode = ThreadMode.MAIN)
public void onRefreshPage(fe3 fe3Var) {
    if (fe3Var != null) {
        boolean z = fe3Var.a;
        if (getActivity() == null || getActivity().isFinishing()) return;
        if (isResumed() && getUserVisibleHint()) {
            if (z && (recyclerView = this.Y) != null) recyclerView.scrollToPosition(0);
            P0();   // 重跑当前页（重抓 + 重解析）
            return;
        }
        // 未 resumed 或不可见：守卫拦截，只记日志（另有分支记 "onRefreshPage 守卫拦截 ..."）
    }
}
```

即：事件只对**已 resumed 且可见**的当前页生效；回顶（`scrollToPosition(0)`）在重跑之前；不可见/未 resumed 的页直接丢弃，不补跑。另有一处同款调用：`vr1.java:2899` 在收到 `he3`（hiker_reverse）事件后也 `post(new fe3(false))`——那是反向排序的附带刷新，不属本项（不实现）。

### 1.4 两个位还有第二条消费链（`refreshRequested` 读后清零）

- `consumeRefreshRequest()`（HkJsRuntime.java:25896-25900）：读 `refreshRequested` 后清零；`isRefreshRequested()` / `isRefreshToTop()`（26484-26489）只读不清理。
- 置位在每次求值开始时清零：`evalLazyDry` 开头（4796-4797）与 `beginEval`（5001-5002）都把 `refreshRequested` / `refreshToTop` 置 false。
- 消费点：`nt1.java:106` 在 listInput 求值后记日志 `refresh=...isRefreshRequested()`（只读日志，不触发重载）。即官方除了事件链外，**没有**“求值后读位再重载”的第二条重载链——重载只走事件。我们的点击路径（`outcome.refreshRequested → host.reloadPage()`，见 §2）是第 7 步自研的等价实现，不是官方链路，本项保留它不动。

### 1.5 不在本项范围

- `back(@Parameter("refreshPage") ...)`（HkJsRuntime.java:25665）：`back()` 的 refreshPage 参数是另一条 API（返回上一页时是否刷新），不实现。
- `goTo(url)`（`lambda$registerApi$139`）：只置 `goToUrl`，与 refreshPage 无关，不实现。

## 2. 现状（我们这边）

- `HkEngine.Bridge.refreshPage()`（HkEngine.java:370-376）：空操作 + 置位 `engine.refreshRequested = true` + 日志。BOOTSTRAP（:142）是 `function refreshPage() { __hk.refreshPage(); }`，无参。`prepare()`（:754）每求值清 `refreshRequested`，与官方 §1.4 同款，但没有 `refreshToTop` 位。
- 点击路径已有等价重载：`HkExecutor.evalLazy` 把 `engine.wasRefreshRequested()` 交进 `LazyOutcome.refreshRequested`（HkExecutor.java:662），`HkActionHandler.dispatchLazyResult` 三处读它调 `host.reloadPage()`（HkActionHandler.java:218/255/259）。**页面求值路径没有消费方**：`homeDetailed` / `search` / `detailOutcome` 里规则 JS 调 `refreshPage()` 后，位在 `prepare()` 下次求值时被默默清掉，页面不刷新——这就是本项要修的缺口。
- 三个 Fragment 都有现成的重跑入口：`HkListFragment.reloadPage() → loadPage(1, false)`、`HkDetailFragment.reloadPage() → loadDetail()`、`HkSearchFragment.reloadPage() → loadPage(1, false)`，且加载都有 `mLoadSeq` 防串位（旧结果丢弃）。但都没有“进行中”标志：`loadPage` / `loadDetail` 只靠 seq 区分新旧。
- 事件机制：项目已用 greenrobot EventBus 3.3.1（`RefreshEvent` 全是 `EventBus.getDefault().post(...)`，main 源集；leanback 的 Activity/Dialog 用 `@Subscribe` + `register/unregister`）。**mobile 源集目前没有任何 `@Subscribe`**（grep 为空），三个 hk Fragment 都没注册过 EventBus——本项是 mobile 源集第一次在 Fragment 里订阅，需自配 `onStart` 注册 / `onStop` 注销。无 `EventBusIndex` / `addIndex` 配置，走运行时反射，与现有 leanback 订阅者同一机制。
- 引擎是每次求值新建：`HkExecutor` 里 `new HkEngine()` 共 7 处（:93 homeDetailed、:138 search、:358 lastChapter、:402 evalDetailRule、:522/:530 playResolved、:644 evalLazy），`HkSubpage` 另有 2 处（:211 expand、:260 requireOne）。因此 **200ms 节流位不能放引擎实例上**（每求值重置，拦不住），必须放静态位。

## 3. 方案

### 3.1 新事件 `HkRefreshPageEvent`（新建，`api/hk/` 下）

照官方 `fe3`（`defpackage/fe3.java`，仅一个 `public final boolean a`）：

```java
public final class HkRefreshPageEvent {
    private final boolean toTop;
    public HkRefreshPageEvent(boolean toTop) { this.toTop = toTop; }
    public boolean isToTop() { return toTop; }
    public static void post(boolean toTop) { EventBus.getDefault().post(new HkRefreshPageEvent(toTop)); }
}
```

不复用 `RefreshEvent`（main 源集，加类型要动主事件枚举；hk 专属事件放 hk 包内，TV 不可见）。

### 3.2 `HkEngine`：两重载 + 静态节流 + refreshToTop 位 + 事件开关

```java
/** 进程内上次发 refreshPage 事件的时间（静态：引擎每次求值新建，实例位拦不住 200ms 节流）。 */
private static volatile long lastRefreshPageAt;
/** 200ms 节流（官方 lambda$registerApi$138 同款阈值）。 */
private static final long REFRESH_PAGE_THROTTLE_MS = 200;
private boolean refreshRequested;   // 已有
private boolean refreshToTop;       // 新增
/** 是否发 refreshPage 事件（默认开；点击/播放求值关，见 §3.3）。 */
private boolean refreshEventEnabled = true;
public void setRefreshEventEnabled(boolean enabled) { this.refreshEventEnabled = enabled; }
```

BOOTSTRAP 改成带参转发（JS 侧 `undefined` 判空，避免 Rhino 零参重载解析的不确定性）：

```js
function refreshPage(scrollTop) { if (scrollTop === undefined) { __hk.refreshPage(); } else { __hk.refreshPage(scrollTop); } }
```

Bridge（照官方 §1.1/§1.2，置位无条件、事件节流、开关关时只置位不发事件）：

```java
/** 海阔 refreshPage(scrollTop?)：置位 + 事件驱动当前页重求值（200ms 节流；无参/非布尔参回顶）。 */
public void refreshPage() { requestRefresh(true); }
public void refreshPage(Object scrollTop) {
    requestRefresh(scrollTop instanceof Boolean ? (Boolean) scrollTop : true);
}
private void requestRefresh(boolean toTop) {
    engine.refreshRequested = true;
    engine.refreshToTop = toTop;
    if (!engine.refreshEventEnabled) { Log.i(TAG, "rule refreshPage requested (event suppressed)"); return; }
    long now = System.currentTimeMillis();
    if (now - lastRefreshPageAt >= REFRESH_PAGE_THROTTLE_MS) {
        lastRefreshPageAt = now;
        HkRefreshPageEvent.post(toTop);
    }
    Log.i(TAG, "rule refreshPage requested toTop=" + toTop);
}
```

`prepare()` 里与 `refreshRequested` 一起清 `refreshToTop = false`（照官方 §1.4 求值开始清两个位）。旧注释（HkEngine.java:108、:370-372）里“置位后由点击执行路径决定是否重跑当前页”同步改成新语义。

### 3.3 哪些引擎不发事件（防双重刷新）

| 引擎创建点 | 是否发事件 | 原因 |
|---|---|---|
| `HkExecutor:93 homeDetailed`、`:138 search`、`:402 evalDetailRule` | 开（默认） | 页面求值，事件是唯一重载链 |
| `HkExecutor:644 evalLazy` | **关**（`engine.setRefreshEventEnabled(false)`） | 点击懒规则的刷新已由 `LazyOutcome.refreshRequested → host.reloadPage()` 消费；再发事件会双重重载 |
| `HkExecutor:522/:530 playResolved` | **关** | 播放链无页面可刷；`PlayOutcome` 本来就不读 `refreshRequested`，发事件只会让背后详情页无故重载 |
| `HkExecutor:358 lastChapter` | **关** | 装饰性字符串求值（详情加载内嵌套），调 refreshPage 是无意义的，关掉防详情页多一次重载 |
| `HkSubpage:211 expand`、`:260 requireOne` | 开（默认） | 嵌套在页面求值里（详情/子页面/require 库）；播放链里 expand 时详情 Activity 已 stop、无订阅者在册，事件自然丢弃（§3.4 守卫） |

### 3.4 三个 Fragment：订阅 + 官方同款守卫 + 防重入（mLoading + mPendingRefresh）

每个 Fragment 加同一套（`HkListFragment` / `HkDetailFragment` / `HkSearchFragment`，各约 40 行，与各文件已有的 Host 样板重复风格一致）：

```java
@Override
public void onStart() { super.onStart(); EventBus.getDefault().register(this); }
@Override
public void onStop() { EventBus.getDefault().unregister(this); super.onStop(); }

@Subscribe(threadMode = ThreadMode.MAIN)
public void onHkRefreshPage(HkRefreshPageEvent event) {
    if (event == null || getActivity() == null || getActivity().isFinishing()) return;
    if (mRule == null) return;
    if (!isResumed() || !getUserVisibleHint()) {   // 官方 vr1.onRefreshPage 同款守卫
        Log.i(TAG, "onHkRefreshPage guard: resumed=" + isResumed() + " visible=" + getUserVisibleHint());
        return;
    }
    if (mLoading) {
        // 进行中的加载不重入：攒下来，等它完成后再重载一次（不丢、不重叠）。
        mPendingRefresh = true;
        mPendingRefreshToTop = mPendingRefreshToTop || event.isToTop();
        return;
    }
    if (event.isToTop() && mBinding != null && mBinding.recycler != null) mBinding.recycler.scrollToPosition(0);
    reloadPage();
}
```

`mLoading` / `mPendingRefresh` / `mPendingRefreshToTop` 三个字段 + `consumePendingRefresh()`：

- `loadPage` / `loadDetail` 在 `++mLoadSeq` 后置 `mLoading = true`（缓存命中路径与网络路径共用同一个 seq 点，两个都覆盖到）。
- `onPageLoaded` / `onDetailLoaded` 开头清 `mLoading = false`；`App.post` 回调里 `!isAdded()` 时也清 `mLoading = false`（Fragment 已死，旧加载不再回来）；`seq != mLoadSeq` 时不碰（新加载接管了标志）。
- 回调末尾调 `consumePendingRefresh()`：有攒下的刷新时清掉它（回顶按攒下的值，先 `scrollToPosition(0)` 再 `reloadPage()`），只重载一次。失败态也消费（与官方 P0() 不分成功失败一致；失败重试只走一次，不循环——下一次重载是否再调 refreshPage 由规则决定）。

为什么不用“加载中直接丢弃”：事件从后台求值线程发出、主线程投递，几乎总是先于本次求值结果的渲染到达——丢弃会把页面求值里调 `refreshPage()` 的主场景（`find_rule` 里 `setResult` 后调 `refreshPage`）直接丢掉。攒下来等本次加载完成再重载一次，既满足“进行中的加载不重入”，又不丢刷新。

为什么 `mLoadSeq` 不够：seq 只防旧结果串位，不管“是否正在加载”；事件到达时 seq 尚未推进，需要独立的 `mLoading` 才能判断重入。

### 3.5 防死循环（两道）

1. **200ms 节流**（§3.2 静态位）：200ms 内多次 `refreshPage()` 只发一个事件；无条件置位的 `refreshRequested` 不受节流影响，点击路径的 outcome 消费不受影响。
2. **进行中的加载不重入**（§3.4）：事件到达时加载进行中 → 只攒一个待处理标志，不启动第二路加载；本次加载完成才重载一次。一条 `refreshPage()` 调用最多触发一次重载；规则每求值都无条件调 `refreshPage()` 时会像官方一样逐轮重载（官方 `P0()` 同样无上限，靠规则自律），文档记为已知限定，不另加计数器偏离官方。

## 4. 验收

| # | 验收项 | 怎么核查（本地无 JDK、无真机） |
|---|---|---|
| ① | 规则 JS 调 `refreshPage()` 后当前页重求值刷新 | 代码走读：页面求值引擎（home/search/detail）默认发事件 → 三 Fragment 已订阅 → 守卫通过 → `reloadPage()` 重跑 `loadPage(1,false)` / `loadDetail()`；真机验（等推上去编好后用户实测） |
| ② | 200ms 内多次调用只触发一次重求值 | 代码走读：静态 `lastRefreshPageAt` + `>= 200` 判定与官方 `lambda$registerApi$138` 同款；`HkExecutor.evalLazy` 等非页面引擎不发事件，不会从点击路径漏出第二路 |
| ③ | 回顶语义与官方一致 | 代码走读：无参/`refreshPage(true)`/非布尔参 → `scrollToPosition(0)`；`refreshPage(false)` → 不回顶；与官方 `refreshScrollTop`（§1.2）逐行对照；语料三种形态全覆盖（4759/3363/1063 次） |
| ④ | 3 条验收规则零回归 | grep 已核查（见 §4.1）：奇优/童话零 `refreshPage`；臻品fix 仅 2 处，都是点击懒规则里的 `refreshPage()`，走 `evalLazy`（事件已关）→ `outcome.refreshRequested` → `reloadPage()` 一次，与改前行为完全一致 |

### 4.1 3 条验收规则 grep 结果（2026-11-15 22:49）

- `奇优影院♔.json`：无 `refreshPage`。
- `童话.json`：无 `refreshPage`。
- `臻品fix.json`：2 处，都是 `refreshPage()`（无参），都在 `detail_find_rule` 里拼出的点击懒规则 URL 中：
  - `hiker://empty@lazyRule=.js:putVar('...', '...');refreshPage();'toast://切换成功！'`（线路 tab 切换）
  - `#noLoading#@lazyRule=.js:...;refreshPage();'toast://切换排序成功';#noHistory#hiker://empty`（排序切换）

  两处都是点击时 `HkActionHandler` → `HkExecutor.evalLazy` 求值，`refreshRequested` 经 `LazyOutcome` 交回 `dispatchLazyResult` 调 `host.reloadPage()`。本项给 `evalLazy` 的引擎关事件后，这两处与改前**完全一致**（一次重载，不多不少）；三 Fragment 的新订阅收不到这次事件（根本没发），不存在双重刷新。

## 5. 已知缺口 / 只能等真机验

- **Rhino 零参与一参重载解析**：`__hk.refreshPage()` 与 `__hk.refreshPage(false)` 分别命中 `refreshPage()` / `refreshPage(Object)` 两个重载，靠 Rhino 按元数精确匹配；本地无 JDK 编不了、也跑不了 Rhino，编译检查等 GitHub 构建，真机点臻品fix 的线路 tab / 排序切换验 `refreshPage()` 无参形态，另找一条传 `refreshPage(false)` 的规则验布尔形态。
- **EventBus 反射订阅**：mobile 源集首次在 Fragment 用 `@Subscribe`，无 `EventBusIndex`，与 leanback 现有订阅者同一机制；R8 下订阅方法由 EventBus 库自带的 consumer 规则保留（与 leanback 同款），以 GitHub 构建 + 真机为准。
- **内嵌列表页的可见性**：首页内嵌的 `HkListFragment`（`VodFragment` 的子 Fragment）的 `getUserVisibleHint()` 默认 true、不会随首页 tab 切换自动变——官方同款守卫在这种宿主下也拦不住切到别的 tab 时的重载；后果只是一次多余的后台重载，不影响显示，暂不另做可见性透传。
- **规则无条件调 refreshPage 的循环**：与官方行为一致（逐轮重载），不另加计数器；真机如遇某规则无限刷新，先在规则侧确认是否无条件调用。
- **`back(refreshPage)` / `he3` 附带刷新**：官方另有入口（§1.3/§1.5），本项不做。

## 6. 回滚

- 单个原子提交 + recovery tag；回滚 = `git revert <提交号>`（只动 §“允许路径”里的 7 个文件，revert 即回退到第 2 项完成态；事件类文件删除、BOOTSTRAP 与 Bridge 恢复空操作、Fragment 的订阅与 mLoading 相关行删除）。
- 若 GitHub 构建报新订阅/重载相关编译错，按 AGENTS.md §6 每处只允许一处小错修一轮，修不好记 blocked、不连修。

## Recovery anchor（尾部快照，执行中更新）

- 目标：见文首；当前：代码已改完，逐行重读通过，待 guard finish 提交 + recovery tag。
- 已做：`HkRefreshPageEvent` 新建；`HkEngine`（BOOTSTRAP 带参转发、Bridge 两重载 + 静态 200ms 节流 + refreshToTop 位 + 事件开关、`prepare()` 清两个位）；`HkExecutor` 四处关事件（lastChapter / play×2 / evalLazy）；三 Fragment 各加订阅 + 守卫 + mLoading/mPendingRefresh。
- 核查结果：`git diff --stat` 5 文件 +250/−10，另 2 个新文件（事件类 + 本文档），都在 guard scope 内；`menu_nav.xml` 仍 6 项；3 条验收规则 grep 结果见 §4.1（臻品fix 2 处全是点击懒规则，走原有 outcome 路径）；语料三种调用形态全覆盖；全仓库 refreshPage 引用已核对，无遗漏调用点。
- 未验（等构建/真机）：Rhino 重载解析、EventBus 反射订阅、真机重求值与回顶（§5）。
- 下一步：guard finish 提交 + recovery tag。
