# 移植阶段 4b：HkCommonParser + HkPageParser

> Recovery anchor（任务：miniapp-xingchen-4b；lane：standard；分支 main；HEAD c3d13ee9）
> 目标：阶段 4 第二笔——搬星辰 HkCommonParser.java（487 行）、HkPageParser.java（371 行）；
> 本地提交 + recovery tag，不推不编。做完即停，不开 4c。
> 范围：只加 2 个新文件 + 本文件。不接线（接线在 4c）。
> 状态：文件已搬、逐行 diff 已验（见 §4），待 guard finish 提交。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref`（HEAD 52450c0），
  `app/src/main/java/com/fongmi/android/tv/api/hk/`。
- 移植方式：机械搬运脚本 `/tmp/xc4/port4.py`（包名改写 + 文件头插入，
  零适配；不随仓库走）。包名改写 `com.fongmi.android.tv` →
  `com.github.moyingshi.osc.tv`。每个新文件在 package 行后加一行文件头
  移植说明（3a/3b/3e/4a 同款）。
- 交叉验证：oktv 反编译源 `~/workspace/hk-ref/src/sources/` 有
  `HkPageParser$HkPage` 类文件，未逐行对位——星辰真源码为准；语义参考
  `~/workspace/hikerview-ref` 的 JSEngine（两者皆为 8.83
  CommonParser/PageParser 的移植，类注释自述 "do not rewrite"）。

## 2. 搬入清单

| 类 | 星辰行 | 我方落点 | 我方行 |
|---|---|---|---|
| HkCommonParser | 487 | api/hk/HkCommonParser.java | 488（+1 文件头） |
| HkPageParser | 371 | api/hk/HkPageParser.java | 372（+1 文件头） |

## 3. 适配点

**零适配。** 两文件除包名与文件头外与星辰原文逐字节一致（见 §4）。
依赖核对（均为我方已有，无需新增）：

- HkCommonParser 用到的 HkRule 方法全在我方 HkRule 已有：
  `getChapterUrl/getBaseUrl/getClassUrl/setClassUrl/getYearUrl/setYearUrl/
  getAreaUrl/setAreaUrl/getSortUrl/setSortUrl/getClassName/getYearName/
  getAreaName/getSortName/getFirstHeader`；HkParserKit 方法全在 4a 已搬
  （decodeConflictStr/arrayToString/listToString/isEmpty/isNotEmpty/trimBlanks）。
- HkPageParser 用到的 `HkRuleManager.get().getRule(title)`（:1218）与
  `HkRuleManager.fastJsonValueText`（:368）均为我方 2b 已搬；用到的
  HkRule 的 set/get（proxy/group/ua/title/lastChapterRule/colType/pages/
  findRule/preRule/url/params）全在我方 HkRule 已有。
- HkPageParser 对 `HkHttpParser.getParamsByUrl` 的引用待 4c 补
  HkHttpParser 后闭合（与 4a 的 HkParserKit 前向引用同款，中间态已知）。
- `HkCommonParser.setJsEval` / `HkPageParser.setRuleLookupOverride`
  的注入点：星辰仓库全树 grep 无调用方（JsEval 桥与 RuleLookup 桩在星辰
  快照里同样未接线），本笔不自造注入；`.js:` 段在桥未设时按星辰原文
  回落（getTextWithoutOr/getUrlWithoutOr 内 `eval == null` 时返回原值，
  不抛错）。阶段 5 路由接线时再定注入方。

## 4. 验收证据（代码级，未构建）

- **逐行 diff**（`diff <(sed 包名还原 + 去文件头 我方文件) 星辰原文`）：
  两文件均为 `IDENTICAL-MODULO-PACKAGE-HEADER`（零差异）。
- **包名残留**：`grep "com.fongmi.android.tv"` 两文件各 1 处，均是文件头
  注释里的星辰原文路径（3e/4a 同款写法），无代码引用残留。
- **禁入项**：两文件 grep `LitePal|DexClassLoader` 零命中。
- **行数统计**：新增 488 + 372 = 860 行（含 2 行文件头）。

## 5. 遗留（阶段 4 内）

- 4c：HkHttpParser + HkLazyRuleParser + HkPlayData + util/HkCodeUtil，接线
  （HkEngine 门面 selector 字段/getSelector()、HkExecutor home/detail/search/
  lazy 调用点、HkRule.isJsFindRule），删 HkParser.java，3 条验收规则等价核对。
- 阶段 4 结束后仍缺（阶段 5）：HkFindDelivery、HkSearchEngine——中间态仍编不过，见 4c 文档汇总。
