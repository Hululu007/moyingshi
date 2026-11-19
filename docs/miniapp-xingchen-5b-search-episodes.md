# 移植阶段 5b：搜索 + 选集 + FindDelivery + col_type 表

> Recovery anchor（任务：miniapp-xingchen-5b；lane：standard；分支 main；HEAD 4cd509a1）
> 目标：阶段 5 第 2 笔——搬星辰 HkSearchEngine（299 行）、HkChapters（210）、
> 选集四件套（HkEpisodeDryClassifier 140、HkEpisodeMerger 386、HkEpisodeQualifier 27、
> HkEpisodeSorter 267）、HkFindDelivery（163）、HkM3u8ClearAdRule（67）/
> HkM3u8ClearUtil（601）、ui/hk HkColType（124）；恢复 HkDetailItem.dryVerdict
> 字段（4a 暂略）；本地提交 + recovery tag，不推不编。做完即停，不开 5c。
> 范围：新增 10 文件 + 本文件；改 HkDetailItem.java（仅 dryVerdict 恢复）。
> 状态：已搬运、逐行 diff 已验（见 §6，verify5b.py 49 PASS 0 FAIL），待 guard finish 提交。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref`（HEAD 52450c0），
  `app/src/main/java/com/fongmi/android/tv/api/hk/` 与
  `app/src/mobile/java/com/fongmi/android/tv/ui/hk/HkColType.java`。
- 移植方式：机械搬运脚本 `/tmp/xc5/port5b.py`（包名改写 + 文件头插入；不随仓库走）。
  包名改写 `com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`。每个新文件在
  package 行后加一行文件头移植说明（3a/3b/3e/4a/4b/4c/5a 同款）。
- 本笔 10 个新文件全部只 import `java.*` / `android.*` / `com.google.gson` /
  `okhttp3`，无跨包依赖（逐文件 grep 核对，见 §2），故全部逐字节原文搬运、
  零适配。

## 2. 搬入清单

| 类 | 星辰行 | 我方落点 | 我方行 | 依赖核对 |
|---|---|---|---|---|
| HkSearchEngine | 299 | api/hk/HkSearchEngine.java | 300（+1 文件头） | 零 import；同包 HkRule/SearchResult |
| HkChapters | 210 | api/hk/HkChapters.java | 211 | java.util only |
| HkEpisodeDryClassifier | 140 | api/hk/HkEpisodeDryClassifier.java | 141 | java.util only |
| HkEpisodeMerger | 386 | api/hk/HkEpisodeMerger.java | 387 | java.util only |
| HkEpisodeQualifier | 27 | api/hk/HkEpisodeQualifier.java | 28 | java.util only |
| HkEpisodeSorter | 267 | api/hk/HkEpisodeSorter.java | 268 | java.util only |
| HkFindDelivery | 163 | api/hk/HkFindDelivery.java | 164 | java.util + concurrent only |
| HkM3u8ClearAdRule | 67 | api/hk/HkM3u8ClearAdRule.java | 68 | gson annotations only |
| HkM3u8ClearUtil | 601 | api/hk/HkM3u8ClearUtil.java | 602 | android/gson/okhttp/java only |
| HkColType | 124 | ui/hk/HkColType.java | 125 | 零 import（纯 enum） |

## 3. 适配点

### 3.1 HkDetailItem.dryVerdict 恢复（4a 暂略的字段）

按星辰 `HkDetailItem.java` 原文恢复（4a 文档已预告）：

```java
/** 干跑分类结论（HkRouter.detail 收编时由 HkEpisodeDryClassifier 写入；null=未分类）。 */
private HkEpisodeDryClassifier.Verdict dryVerdict;

public HkEpisodeDryClassifier.Verdict getDryVerdict() { return dryVerdict; }
public void setDryVerdict(HkEpisodeDryClassifier.Verdict dryVerdict) { this.dryVerdict = dryVerdict; }
```

字段插在 `colType` 字段之后、getter 段之前（与原文位置一致）；4a 的文件头
注释里“dryVerdict 字段暂略，阶段 5 恢复”一句同步改为“已恢复”。其余
6 个字段与方法一字不动。写入方是 5a 已搬的 `HkRouter.classifyDetailItems`
（:309-334），读方是 5a 已搬的 `HkDetailBridge.toVod` 选集收编链。

### 3.2 col_type 表（任务书第 4 项）

- 星辰 `HkJsRuntime.java:9085-9103` 的 `COL_TYPES`（57 码，按官枚举序号序）
  在我方 `HkJsRuntime` 内**3b 搬运时已逐字节在位**（`colTypeCodes()` 同款），
  本笔不再重复。
- 本笔搬入的 `ui/hk/HkColType.java` 是同一张表的 enum 形态（57 码 + itemType/
  spanCount/landSpanCount/leftRight + `byCode/itemTypeByCode/spanCountByItemType/
  landSpanCountByItemType/leftRightByItemType`，照抄 8.83
  `ArticleColTypeEnum`，查不到回退 `MOVIE_3`）。它是 5d 删除 `HkListHelper`
  时 `styleFor/columnsFor`（42 种实测类型 + 数字族通用解析）的替代落点：
  HkListHelper 的表是“我方列表样式+列数”映射，与 HkColType 的“官码→栅格”
  映射不是同一张表——5d 删除时按 `HkColType.spanCountByItemType` 重做列数、
  按 `HkItem.getType`/colType 重做样式分流，旧表不直接平移（见 5d 文档）。
- `x5_webview_single`（itemType 34，全宽 60 栅格）在 HkColType 内已登记，
  x5 卡分流见 5d 的二选一。

### 3.3 按需评估结论（任务书第 8 项）

| 类 | 结论 | 理由 |
|---|---|---|
| HkM3u8ClearAdRule / HkM3u8ClearUtil | **搬**（本笔） | 两类自包含（android/gson/okhttp/java，无跨包依赖）；星辰 `HkJsRuntime:5030` 的 `proxyClearM3u8` 经 `HkM3u8ClearUtil.getProxyContent(url, headers, adRules)` 去广告。注意：我方 `HkJsRuntime` 内**没有** `proxyClearM3u8` 的 `putFunction` 注册（3b 搬运时整段未搬，只留了 `fixM3u8`/`cacheM3u8`）；本笔只把类搬到位，JS 函数接线留给 5d 定夺（把星辰 `HkJsRuntime:4989-5060` 的 putFunction 块逐字搬入，约 70 行，依赖 `toStringMap/argStr/md5/App.get()` 在我方均已在位）。未接线前去广告不生效，与 3b 至今的行为一致，无回归。 |
| HkLyricLoader（76 行） | **不搬** | 依赖星辰 `player/engine/HkPlayCompanion.classifyLyric`（我方全仓库无此 类）与 `utils/Task`（我方有同名 `Task`，但 classifyLyric 无等价物）；我方已有歌词接线（`LyricUtil` + 播放器歌词位），对照后取舍的结果是保留我方接线、不引入星辰歌词加载器。`HkPlay.getSubtitle/getDanmu` 等字段仍由 5a 的 HkPlay 承载，播放器侧不动。 |
| HkImage（33 行） | **已在位，不动** | 3a 已搬，与星辰原文逐字节一致（仅文件头一行之差）；`HkEngineManager.runtimeFor` 与 `HkEngine` 构造器调的 `setCurrentRuntime/setCurrentTitle/currentRuntime` 均已闭合。 |

## 4. 悬空引用闭合对照（本笔的核心验收）

5a 结束时 HkJsRuntime 内仍悬空的引用（任务书称 17 处，是含 HkRouter/
HkDetailBridge 等文件的粗计；单 HkJsRuntime 文件内核对：FindDelivery 9 处
代码引用 + SearchEngine 4 处代码引用 = 13 处），本笔闭合情况：

| 符号 | 5a 前 | 本笔后 | 说明 |
|---|---|---|---|
| HkFindDelivery | 9 处代码引用（:473 字段 + :475 ThreadLocal + :3217/:6674/:8247/:8276/:8366 + :11969/:12121 局部变量；另 2 处注释提及 :472/:8254） | 0 悬空 | 类在位（163 行）；`HkFindDelivery.Sink` 内嵌类型随同在位 |
| HkSearchEngine | 4 处代码引用（:11112/:11113/:11114/:11121 getMyRule 内 instanceof + 强转 + 三元；另 2 处注释提及 :11100/:11101） | 0 悬空 | 类在位（299 行）；`getTitle/getDisplayName/fromRule/toRule` 随同在位 |
| HkChapters / 选集四件套 | 5a 的 HkRouter.classifyDetailItems（5 处）+ HkDetailBridge.toVod（多处）引用 | 0 悬空 | 5 类全部在位 |
| HkDetailItem.dryVerdict | HkRouter:323/:328 两处 `setDryVerdict` | 0 悬空 | 字段 + getter/setter 恢复 |

闭合后 `HkJsRuntime` 内应无任何未定义的 hk 类引用（5a 的 HkRouter 等类
亦已在位）；剩余未闭合的只剩阶段 6/7 的类（云口令两法、HkPageActivity 等，
见 §7）。注意：闭合是“符号在位”口径，不是“能编译”口径——最终编译仍待
5d 接线后统一构建验（本地无 JDK/Android SDK）。

## 5. 语义变化

无。本笔 10 类逐字节原文搬运；HkDetailItem 只加字段与一对 accessor，
不改既有行为。HkSearchEngine 的 `getDisplayTitle` 空串回落 title（星辰
2026-10-24 真机实证修法，见类注释）随原文搬入——搜索结果来源标不再灭失，
属星辰行为恢复，非我方自造。

## 6. 验收证据（代码级，未构建）

- **逐行 diff**（`diff <(sed 包名还原 + 去文件头 我方文件) 星辰原文`，
  脚本 `/tmp/xc5/verify5b.py`，**49 PASS，0 FAIL**）：10 文件全部
  `IDENTICAL-MODULO-PACKAGE-HEADER`（零差异）；HkDetailItem 经逐行 diff 核对
  与星辰原文一致（仅文件头注释一行措辞不同 + dryVerdict 段），diff 结果：
  仅 +12/−1（字段 3 行 + getter/setter 8 行 + 文件头注释 1 行改写）。
- **包名/禁入残留**：`com.fongmi.android.tv` 在新增十文件内只剩文件头注释
  里的星辰原文路径，无代码引用；`LitePal|DexClassLoader|loadClass|
  SourceGate|GlobalProxy|XPopup|Clash` 零命中。
- **悬空引用计数前后对照**：HkJsRuntime 内 `HkFindDelivery` 9 处代码引用 +
  `HkSearchEngine` 4 处代码引用，本笔后类定义在位、引用全部闭合（verify 脚本
  核对 10 类定义各恰好 1 处、`HkFindDelivery.Sink` 内嵌类型在位、
  `HkSearchEngine.fromRule/getTitle/getDisplayName` 在位）；
  全树 grep `HkEpisodeDryClassifier|HkChapters|HkEpisodeMerger|
  HkEpisodeQualifier|HkEpisodeSorter|HkColType|HkM3u8Clear` 均有定义在位。
- **git diff --check** 通过（verify 脚本内跑过）。
- **改动文件列表与行数**：新增 10 文件共 2294 行（SearchEngine 300、Chapters 211、
  DryClassifier 141、Merger 387、Qualifier 28、Sorter 268、FindDelivery 164、
  M3u8ClearAdRule 68、M3u8ClearUtil 602、ColType 125）；改 HkDetailItem.java
  +12/−1；本文件。

## 7. 遗留（5b 结束后仍缺什么）

- 5c：列表/搜索 UI 替换（星辰 HkListActivity 3268 行因 XPopup 禁令 + 阅读器/
  ArticleFragment/RouteBlocker 生态无法逐字搬；搜索页同款核对
  HkSearchFragment/HkSearchModel 的 Clash 引用与缺失布局后定案）。
- 5d：接线（UI 调用点改走 HkRouter；HkExecutor 瘦身/删除；HkListHelper 等
  删除清单，col_type 表按 §3.2 落地；HkHikerRuleUtil history 支恢复；3e 适配段
  缺口复核；HkLazyRuleParser.parse 桥注入定案；x5 卡二选一；8 条规则全链路核对；
  `proxyClearM3u8` JS 函数接线定夺——见 §3.3）。
- 阶段 6：云口令两法、HkCloudClip 等；阶段 7：HkPageActivity、剩余 event、
  HkWebkitHolder 全桥。均不提前搬。
