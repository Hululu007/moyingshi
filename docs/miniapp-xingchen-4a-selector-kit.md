# 移植阶段 4a：HkSelector + HkParserKit（+ HkDetailItem）

> Recovery anchor（任务：miniapp-xingchen-4a；lane：standard；分支 main；HEAD 464f1b6e）
> 注：任务书起点 HEAD 9fde5fa3；工作期间 main 前进一笔合并提交 464f1b6e
> （Merge remote main，mpv JNI rebuild PR #1，仅动 mpv 工作流/.so 与文档，
> 未碰 hk 文件，与本笔无交集），guard base HEAD 以此为准。
> 目标：阶段 4 第一笔——搬星辰 HkSelector.java（323 行）、HkParserKit.java（312 行），
> 及 HkSelector.parseDetail 的返回类型 HkDetailItem.java（80 行，dryVerdict 暂略）；
> 本地提交 + recovery tag，不推不编。做完即停，不开 4b。
> 范围：只加 3 个新文件 + 本文件。HkExecutor/HkEngine 接线、HkParser.java 替换在 4c。
> 状态：文件已搬、逐行 diff 已验（见 §5），待 guard finish 提交。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref`（HEAD 52450c0），
  `app/src/main/java/com/fongmi/android/tv/api/hk/`。
- 移植方式：机械搬运脚本 `/tmp/xc4/port4.py`（包名改写 + 文件头插入 +
  §3 所列唯一适配；不随仓库走）。包名改写 `com.fongmi.android.tv` →
  `com.github.moyingshi.osc.tv`；`com.fongmi.quickjs.*` 不动（我方 quickjs
  模块同包）。每个新文件在 package 行后加一行文件头移植说明（3a/3b/3e 同款）。
- 交叉验证：oktv 反编译源 `~/workspace/hk-ref/src/sources/` 无 HkSelector /
  HkParserKit 独立文件（只有 HkJsRuntime 内联的等价逻辑），本笔以星辰真源码
  为准，不做 oktv 逐行对位；语义参考 `~/workspace/hikerview-ref` 的 JSEngine
  （选择器即 8.83 CommonParser 口径，见 §4）。

## 2. 搬入清单

| 类 | 星辰行 | 我方落点 | 我方行 |
|---|---|---|---|
| HkSelector | 323 | api/hk/HkSelector.java | 324（+1 文件头） |
| HkParserKit | 312 | api/hk/HkParserKit.java | 313（+1 文件头） |
| HkDetailItem | 80 | api/hk/HkDetailItem.java | 70（+1 文件头，−11 dryVerdict，见 §3） |

## 3. 适配点

1. **HkDetailItem 去 dryVerdict**（唯一适配）：星辰 `HkDetailItem` 带
   `HkEpisodeDryClassifier.Verdict dryVerdict` 字段 + getter/setter（选集
   干跑分类结论，HkRouter.detail 收编时写入）。`HkEpisodeDryClassifier` 是
   阶段 5 的类（计划 §2 阶段 5 选集四件套），本阶段不提前搬；字段、getter、
   setter 及字段注释一并去掉，`isDisplayOnly()` 等其余部分原样。阶段 5 搬
   `HkEpisodeDryClassifier` 时把该字段恢复（届时 HkSelector.parseDetail
   本身不用改，字段只由 Router 写入）。
2. **其余零适配**：HkSelector 与 HkParserKit 除包名与文件头外与星辰原文
   逐字节一致（见 §5 diff 证据）。HkSelector 依赖的 quickjs `Parser`
   （pdfh/pdfa）是我方 3a 已搬的文件，与星辰 quickjs `Parser.java` 的 diff
   只有文件头注释一行（见 §5），选择器求值与星辰同款。
3. **HkParserKit 注释里提到的 `ui/hk/HkStringUtil`**：那是星辰侧的另一份
   同名转写，我方树里没有该文件（全树 grep 零命中），不新建副本；
   HkParserKit 是我方唯一的 StringUtil 转写收口（getHome/getHomeUrl/
   getRealUrl/clearTag 等全集在此）。

## 4. 本笔闭合的引用（代码级）

- `HkJsRuntime.java:93` `implements HkSelector.JsEvaluator`：此前 HkSelector
  不存在，该 implements 是悬空引用；本笔补上后闭合。`:7886` 的 `eval(js, input)`
  实现（选择器 `.js:` 值加工）与 `HkSelector.JsEvaluator` 接口签名一致。
- `HkJsRuntime.java:6489-6490` 调 `HkParserKit.clearTag/replaceOnce/
  splitUrlByQuestionMark`：此前 HkParserKit 不存在，本笔补上后闭合。
- `HkHikerRuleUtil.java:127` 注释提到的 `HkParserKit.getHomeUrl`（阶段 4）：
  本笔起该方法在位可用（`HkParserKit.java:159`）；historyJson 本体仍是
  阶段 5/7 的事（该文件 history 支仍回 `"[]"`，不动）。
- 本笔不接线：HkSelector 尚无调用方（HkEngine 门面 selector 字段 +
  getSelector()、HkExecutor 解析调用点都在 4c）；HkParserKit 内对
  `HkHttpParser.getFirstPageUrl/decodeUrl` 的引用待 4c 补 HkHttpParser 后闭合。

## 5. 验收证据（代码级，未构建）

- **逐行 diff**（`diff <(sed 包名还原 + 去文件头 我方文件) 星辰原文`）：
  HkSelector、HkParserKit 均为 `IDENTICAL-MODULO-PACKAGE-HEADER`（零差异）；
  HkDetailItem 的 diff 只有两处 hunk，恰好是 §3 的 dryVerdict 字段 + getter/setter
  删除，别无差异。
- **包名残留**：`grep -c "com.fongmi.android.tv"` 三文件各 1 处，均是文件头
  注释里的星辰原文路径（3e 同款写法），无代码引用残留。
- **禁入项**：三文件 grep `LitePal|DexClassLoader|loadClass` 零命中（命中
  的只是 `indexOf`/`.js:` 里的 `dex`/`js:` 子串，无动态加载）。
- **行数统计**：新增 324 + 313 + 70 = 707 行（含 3 行文件头）。

## 6. 遗留（阶段 4 内）

- 4b：HkCommonParser + HkPageParser（HkPageParser 依赖 4c 的 HkHttpParser）。
- 4c：HkHttpParser + HkLazyRuleParser + HkPlayData + util/HkCodeUtil，接线
  （HkEngine 门面 selector 字段/getSelector()、HkExecutor home/detail/search/
  lazy 调用点、HkRule.isJsFindRule），删 HkParser.java，3 条验收规则等价核对。
- 阶段 4 结束后仍缺（阶段 5）：HkFindDelivery（HkJsRuntime:473 等 8 处引用）、
  HkSearchEngine（HkJsRuntime:11102 等 3 处引用）——中间态仍编不过，见 4c 文档汇总。
