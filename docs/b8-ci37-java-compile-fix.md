# CI #37 Java 编译失败：GlideUrl 取 URL 方法 + B15LocalItem 限定修复

## 失败

- CI run #37（44075379612，head d8026cd）："Build four release APKs" 在
  `:app:compileMobileArm64_v8aReleaseJavaWithJavac` 失败，共 **21 errors**，4 个 APK
  一个也没产出。Dart analyze、Pigeon 生成、Kotlin 编译均已通过（#35 的
  HkMiniappActivity 修复生效，日志里 "HkMiniapp" 出现 0 次）。
- javac 一次性报出全部错误，所以 21 条即本次编译任务的完整清单。

## 错误清单

1. 20 处 `error: cannot find symbol / symbol: method getStringURL()`，10 个
   `*HostApiImpl.java` 文件各 2 处（`parts.url` 与 `parts.fallbackUrl`）：
   C2（291、308）、B2（357、374）、B8（423、440）、B10（1000、1017）、
   B12（364、381）、B13（356、373）、B14（877、894）、B17（710、727）、
   B18（660、677）、B22（521、538；B22 用 `nn(...)` 而非 `text(...)`）。
   调用形如 `parts.url = text(glideUrl.getStringURL());`，`glideUrl` 来自
   `ImgUtil.getUrl(rawPic)` 返回的 `Object`（`instanceof GlideUrl` 分支），
   import 均为 `com.bumptech.glide.load.model.GlideUrl`。
2. 1 处 `error: cannot find symbol / symbol: class B15LocalItem`：
   B15HostApiImpl.java:132 `List<B15LocalItem> items = new ArrayList<>();`。
   同文件第 135 行用的是 `new B15Messages.B15LocalItem.Builder()`——
   B15LocalItem 是 Pigeon 生成的 B15Messages 的嵌套类，第 132 行漏了限定。

## 根因

1. **Glide 4 记忆 vs 项目实际 Glide 5.0.7。** C2 批次的
   docs/b8-c2-history.md §2.5 写的是 "GlideUrl 自带干净 URL（`getStringURL()`）"。
   这是 Glide 4 的 API；本项目实际用 Glide **5.0.7**
   （gradle/libs.versions.toml:36），5.0.7 的 GlideUrl 没有 `getStringURL()`。
   仓库里没有任何原生代码调用过 getStringURL/getStringUrl/getURL()，所以没有
   可抄的现成用法；这是联合 CI 构建第一次编译到这 20 处。
2. **B15 漏限定。** 第 132 行写裸类名，第 135 行写了限定形式，同一文件内不一致。

## Glide 5.0.7 源码证据

- 来源：https://raw.githubusercontent.com/bumptech/glide/v5.0.7/library/src/main/java/com/bumptech/glide/load/model/GlideUrl.java
  （tag `v5.0.7`，commit c751c283ed852f73b7fd6f0ed8ba5b8b7e452b38，2026-11-17 读取）。
- 类文档（L38–42）："To obtain a properly escaped URL, call `toURL()`. To obtain
  a properly escaped string URL, call `toStringUrl()`. To obtain a less safe, but
  less expensive to calculate cache key, call `getCacheKey()`."
- `public String toStringUrl()`（L149–153）："Returns a properly escaped String
  url that can be used to make http/https requests." 实现为返回
  `getSafeStringUrl()`（Uri.encode 后的安全 URL）。
- 全文件无 `getStringURL` / `getStringUrl` 方法。`toString()`（L211–217）返回
  `getCacheKey()`（未转义，不可作 URL 用）；`getCacheKey()`（L204–208）文档
  明确说 "Unlike toStringUrl() and toURL(), this method does not escape input"，
  故两者都不能替代。
- 结论：**`toStringUrl()` 是 Glide 4 `getStringURL()` 在 5.0.7 的语义继承者**，
  且是 Glide 官方文档指定的取请求用字符串 URL 的方法。

语义说明：Glide 4 的 `getStringURL()` 返回原始字符串；`toStringUrl()` 返回
Uri.encode 转义后的安全字符串。Dart 侧用它作 Image.network 的 URL，转义后
的形式是正确且更安全的；图片本身与原生 Glide 加载的是同一地址，不会漂移。

## 修复

- 10 个文件各 2 处：`glideUrl.getStringURL()` → `glideUrl.toStringUrl()`，
  `glideFallback.getStringURL()` → `glideFallback.toStringUrl()`。只换方法名，
  `text()` / `nn()` 包装与周围逻辑一行未动。
- B15HostApiImpl.java:132：`List<B15LocalItem> items` →
  `List<B15Messages.B15LocalItem> items`，与 135 行的限定形式一致。

## 本地验证

- `git diff --check` 干净；`git status` 只有 11 个修改文件 + 本 doc。
- 全仓 grep：`getStringURL` 出现 0 次；`toStringUrl()` 恰好 20 次
  （C2/B2/B8/B10/B12/B13/B14/B17/B18/B22 各 2 次）；不带
  `B15Messages.` 前缀的 `B15LocalItem` 出现 0 次。
- 复读 B2（text 包装）与 B22（nn 包装）改动处：包装函数与逻辑未变。

## 未验证项

- 本机无 Android SDK，无法本地跑 Java 编译；编译结果以 CI 下一轮为准。
- 真机行为：URL 由原始字符串改为转义后安全字符串，Dart 图片加载地址与原生
  Glide 请求地址一致；若某地址含需转义字符，Dart 与原生行为以转义后为准。
  需随联合构建的真机验收复核图片加载（与既有图片 parity 验收项相同）。

## Recovery anchor

- 目标：清掉 CI #37 的 21 条 Java 编译错误（20× getStringURL + 1× B15LocalItem）。
- 分支/HEAD：b8-flutter-rewrite，基线 d8026cd；main 未动（f39a6e0）。
- 已完成：Glide 5.0.7 源码核实（toStringUrl）、11 文件修改、本地 grep 计数核对、
  doc 撰写；待办：task_guard finish 提交 → 父 agent 推送 → 下一轮 CI 验证。
