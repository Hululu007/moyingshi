# B6 播放器画面调节 + Anime4K — 设计文档

任务来源：用户 2026-11-11 14:38 说“b6”。B6 是 B 档最后一项待做的功能级工程（1–2 周）。
参考：`~/workspace/ui-ref/analysis.md`（B6 在第 4 节；播放器在第 2.4 节）、BieCuiLe 解包 `~/workspace/ui-ref/extracted/`（`assets/flutter_assets/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`、`assets/flutter_assets/assets/i18n/en.json`）。
基线：main `d3f2b211`（B5 之后，已推到线上 main，本地与线上一致，工作树干净）。
约束：只在本地改、本地提交，**不推 GitHub**；不编测试版；每完成一个逻辑单元走一次 task_guard finish（一个 guard 会话 = 一次原子提交 + recovery tag）；保留墨影视已有改动（品牌/包名 com.github.mooyingshi.osc、液态玻璃、短剧、Archive.org 爬虫、本地源、默认搜索本站、底栏 6 项、B1–B5）；底栏上限 6 项；本机无 Android SDK，编译检查靠 GitHub 构建。

完成句：播放器里加“画面”入口，五项调节（亮度/对比度/饱和度/色相/伽马）在 Exo 与 mpv 内核实时生效、ijk 明确不支持，Anime4K 开关只在 mpv 内核可用（默认关），调节值持久化，黑边虚化本期不做。

- Lane：standard（功能级新工程；设计研究门见 §7）。
- 允许路径：本文件；实现单元见 §8（每单元开新 guard 会话、声明自己的 scope）。
- 本地时间：2026-11-11 14:39 CST（Asia/Shanghai）。总耗时估计：设计文档约 1 小时；实现约 6–10 小时分单元做（模型/持久化 1h、Exo 效果器 1.5h、mpv 属性+Anime4K 2h、PlayerManager 接线 1.5h、面板 UI + 播放器按钮 1.5h、strings + 收尾静态核对 1h）；无本地编译，真机验证只能靠推上去之后的 GitHub 构建 + 用户实测。
- 最便宜的决定性验证：静态核对（`git diff --check`、类型/签名 grep、底栏 6 项、开关默认关）+ GitHub 构建一次通过；行为验证靠用户真机（见 §9）。

## Recovery anchor

- 目标：B6 画面调节五项 + Anime4K + 播放器调节面板 + 参数持久化（见上完成句）。
- 当前阶段：设计文档已写（本文件）；实现未开始。
- 分支/HEAD：main / d3f2b211。
- 已完成：BieCuiLe 播放器文案与 Anime4K shader 已读（§1）；三内核调节能力已摸清（§2）；Anime4K 上游文件已下载并与 APK 内文件逐字节比对一致（§3）。
- 未验证：全部实现（无本地 SDK，编译靠 GitHub 构建；每项只做静态核对；性能/发热只能真机测）。
- 风险：task_guard 是单会话状态（`.codex/task-state/current`），不可并行跑多个 guard 会话——B6 各单元必须串行做、串行提交。
- 下一步：按 §8 的单元顺序实现，先做单元 1（模型 + 持久化）。

---

## 1. 参考：BieCuiLe 的画面调节与 Anime4K（实证）

来源：解包目录 + `assets/flutter_assets/assets/i18n/en.json`。以下全部是读源码/文件得到的事实，不是推测。

### 1.1 播放器文案（en.json 第 100–123 行）

- 入口叫“画面调节”（"Picture"），五项：“对比度” Contrast、“饱和度” Saturation、“色相” Hue、“伽马” Gamma、“视频亮度” Video brightness；另有“黑边虚化” Blur letterbox。
- 多内核：media_kit / mpv / ijk 可切；屏显信息、倍速、音轨、字幕、弹幕、选集、跳过片头片尾、上下滑动换集、后台播放、投屏。

### 1.2 五项调节的真实语义：就是 mpv 的五个 video 属性

BieCuiLe 用 media_kit（Dart 封装 libmpv）。media_kit 的 `mpv_kit_video` 包有 `MpvVideoAdjustment` 枚举（pub.dev 文档实证）：brightness / contrast / saturation / gamma / hue 五个值，**每个都是 -100..100 的整数，0 = 不变**，直接对应 mpv 的同名属性。mpv 手册（mpv.io/manual，2026-11-11 查）同样写明：`--brightness/--contrast/--saturation/--gamma/--hue` 均为 `<-100-100>`，默认 0；“Not supported by all video output drivers”。

**结论：BieCuiLe 的五项滑杆没有自研数值，就是 mpv 属性 -100..100、默认 0。** 墨影视照搬这个口径：滑杆一律 -100..100、默认 0；精确数值反编译拿不到也不需要——mpv 手册就是数值来源。

### 1.3 Anime4K shader（实证：文件本身）

- BieCuiLe APK 内只有 **一个** shader 文件：`assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`，18,638 字节，Anime4K v3.2（S = Small 档，最轻的一档）。
- 文件头是 bloc97 的 MIT License（2019–2021）；内含 5 个 pass（`//!DESC` 计）：4 个卷积 pass（Conv-4x3x3x3 → 2 个 Conv-4x3x3x8 → 最后卷积）+ 1 个 Depth-to-Space pass（`//!SAVE MAIN`，2x 放大输出）；每个 pass 都有 `//!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *`，即**只在输出比输入大 1.2 倍以上（真正需要放大）时才生效**，播 1080p 片源基本不跑。
- 自包含：无 `#include`、无外部纹理，5 个 pass 靠 `//!SAVE conv2d_tf / conv2d_1_tf / conv2d_2_tf / conv2d_last_tf` 在文件内串联。mpv 的 `glsl-shaders` 原生支持这种多 pass 单文件格式。
- 来源核对（2026-11-11）：从上游 bloc97/Anime4K 仓库下载 `glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl`（master 分支），sha256 `4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4`，**与 APK 内文件逐字节相同**。所以墨影视直接用上游文件，不从 APK 里抠（出处干净，MIT 头完整保留）。

### 1.4 黑边虚化（实证：只有文案，没有可搬的实现）

en.json 只有 "黑边虚化": "Blur letterbox" 一条文案；Flutter 侧的实现在编译后的 Dart 里，反编译拿不到。按 media_kit/Flutter 的常见做法，它是“视频后面再铺一层模糊放大的同一视频”做背景（两层视频），不是 mpv 内置功能（mpv 没有 blur-letterbox 选项/属性）。见 §5：本期不做。

## 2. 决策：墨影视怎么做（分内核）

### 2.1 总口径

| 项 | 口径 |
|---|---|
| 滑杆 | 五项一律 -100..100 整数，默认 0（与 mpv / BieCuiLe 一致） |
| 生效时机 | 拖动滑杆实时生效（BieCuiLe 的面板就是实时调）；值变更即持久化（见 §4） |
| 调节值归属 | 全局持久化（跟 LUT 开关一样记 Prefers），不是按片记忆；换片、换内核都沿用上次的值 |
| Anime4K | 开关，默认关；只在 mpv 内核可用（§3）；Exo/ijk 下入口置灰并说明原因 |
| 面板入口 | 播放器控制栏加“画面”按钮（与 LUT 按钮并列，见 §6）；只做手机端 Vod 播放器（TmdbDetailActivity 内嵌播放器），直播/TV 播放器本期不加按钮（引擎层调节照样生效，见 §2.5） |

### 2.2 mpv 内核：原生属性 + 原生 shader（最顺的一路）

- 五项调节：mpv 原生属性 `brightness / contrast / saturation / hue / gamma`，运行时 `set_property` 即可实时改，不用重建播放器。Mooyingshi 的 `MpvPlayer`（`app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`）已有私有 `mpvSetPropertyDouble`（`MPVLib.setPropertyDouble` 封装，带 propertySnapshot 记录）；需新增一个公开方法（如 `setVideoProperty(String property, double value)`，内部 `initialized` 检查 + 调 `mpvSetPropertyDouble`），`MpvPlayerEngine` 再包一层。**不改 mpv 源码、不改 lock、不碰 native。**
- 注意：mpv 手册说 eq 属性“不是所有 vo 都支持”；Mooyingshi 默认 `vo=gpu`（`MpvPlayerConfig.java:206`，`vo = "gpu"`），gpu/gpu-next 均支持 eq 属性。`mediacodec_embed` 直出模式（surface-direct）下没有 GPU VO，eq 属性与 glsl-shader 都不生效——与 LUT 的限制完全一致（`MpvPlayerEngine.supportsNativeLut()` 在 surface-direct 时返回 false；`LutEligibility` 同样判 surface-direct 不可用）。Anime4K 与五项调节在 surface-direct 下同样不可用，面板里按同一口径提示（复用 `LutEligibility` 式的不可用原因文案，见 §6）。
- Anime4K：把上游 shader 文件放 `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`，运行时由新写的 `Anime4kShaderStore` 拷到缓存目录（`cacheDir/mpv_shaders/Anime4K_Upscale_CNN_x2_S.glsl`，与 LUT 的 `mpv_lut_shaders` 目录并列；清缓存时一并清理，见单元 3），再走现成的 `glsl-shaders` 机制挂载。
  - 现状缺口：`MpvPlayer.applyShaderPipeline()` 只管一个槽位（`lutShader` / `appliedLutShaderPath`，`change-list glsl-shaders append/remove`）。Anime4K 需要**第二个独立槽位**：新增 `anime4kShaderPath` + 公开 `setAnime4kShader(@Nullable String path)`，`applyShaderPipeline` 改为分别管理两个槽位（各管各的 append/remove，互不干扰）。
  - 挂载顺序：glsl-shaders 列表里 **Anime4K 在前、LUT 在后**（先超分放大，再做 LUT 调色；与“先修后调”的常规顺序一致）。BieCuiLe 只有一个 shader，无参考顺序；这是墨影视自己的决定，写死在 `applyShaderPipeline` 里。
  - `gpu-shader-cache-dir` 已配（`MpvPlayer.java:1855`），shader 编译结果有缓存，二次打开不重复编译。

### 2.3 Exo 内核：自写 GlEffect（抄 LUT 的现成套路）

- 现状：`ExoPlayerEngine.supportsVideoEffects()` 返回 true，`setVideoEffects(List<Effect>)` 直通 `player.setVideoEffects()`（Media3 1.11.0-alpha01-fongmi，`media3-effect` 已依赖）。`DynamicLutEffect implements GlEffect`（`player/lut/DynamicLutEffect.java`）是现成的范本：`BaseGlShaderProgram` + 内嵌 GLSL + uniform 实时改 + `isNoOp()` 恒 false 保管线温热 + HDR 时 `toGlShaderProgram` 抛错（`useHdr` 不支持）。
- 新写 `PictureAdjustEffect implements GlEffect`（放 `player/lut/` 旁的新包或同包，见 §8）：一个 `BaseGlShaderProgram`，fragment shader 里按顺序做五项变换（见 §2.4 的映射公式），五个 uniform（亮度/对比度/饱和度/色相角/伽马）+ 一个总开关 uniform；`set(PictureAdjust)` 只改 volatile 状态（与 `DynamicLutEffect.set()` 同款），不用重建 effect、不用重调 `setVideoEffects` 就能实时生效。
- 与 LUT 的组合：`PlayerManager` 里 effects 列表改为 `[PictureAdjustEffect, DynamicLutEffect]`（调节在前、LUT 在后，与 mpv 侧顺序一致）。现状 `PlayerManager` 多处直接传 `dynamicLutEffect.effects()`（6782/6990/7075/7165/7198 行附近），需抽一个 `currentVideoEffects()` 统一组装；`safeSetVideoEffects` 的“已激活则复用”短路逻辑保留（两个 effect 都是状态型对象，uniform 改了就实时生效，不用重设列表）。
- HDR：与 LUT 同款处理——HDR 片源下 Exo 不挂调节 effect（`LutEligibility.isHdr` 的判断逻辑复用；需把 `isHdr(Format)` 抽成可复用的公开方法或在 `PictureAdjustEffect` 侧自己判一次，二选一，单元 4 定）。mpv 侧 HDR 不受限（VO 内处理），所以同一滑杆值在 mpv 下对 HDR 生效、在 Exo 下不生效——面板里对 Exo+HDR 给出提示，见 §6。
- 隧道模式（tunneling）/软解：与 LUT 同款门禁（`canWarmLutPipeline`：tunnel 开、SOFT 解码、video-prefer 时不挂 effects）；调节 effect 复用同一组门禁条件，不另发明。

### 2.4 五项映射公式（Exo shader 侧；mpv 侧走原生映射）

滑杆值 `s ∈ [-100, 100]`，默认 0。Exo shader 用以下**墨影视自定**映射（标准教科书组合，写死在 `PictureAdjustEffect` 里；mpv 侧把 `s` 原样传给原生属性，由 mpv 自己映射——**两边曲线不完全一致是已知差异，默认 0 时两者均为原样**）：

| 项 | Exo shader 映射 | 说明 |
|---|---|---|
| 亮度 | `color += s / 100.0` | ±1.0 全幅；0 不变 |
| 对比度 | `c = 1 + s / 100.0`（0..2）；`color = (color - 0.5) * c + 0.5` | 0 不变；-100 全灰 |
| 饱和度 | `sat = 1 + s / 100.0`（0..2）；`color = mix(vec3(luma), color, sat)`，luma 用 Rec.709 系数 | 0 不变；-100 得灰度图（与 mpv 手册“saturation 可出灰度”一致） |
| 色相 | `angle = s / 100.0 * π`；绕亮度轴 YIQ 旋转 | 0 不变；±100 转 180° |
| 伽马 | `g = pow(10, s / 100.0)`（0.1..10）；`color = pow(max(color, 0), vec3(1.0 / g))` | 0 → g=1 不变；与 FFmpeg eq 的 gamma 范围 0.1..10 同口径 |

执行顺序（shader 内）：亮度 → 对比度 → 饱和度 → 色相 → 伽马。全部中性（五项全 0）时 shader 直接原样输出（`uEnabled=0` 短路，零视觉变化；effect 本身仍挂着，与 LUT 的暖管线 passthrough 同成本）。

### 2.5 ijk 内核：本期不支持（有据）

- ijkplayer 的 `IjkMediaPlayer.setOption(OPT_CATEGORY_PLAYER, "vf0"/"vf1", ...)` 确实能挂 FFmpeg 视频滤镜（上游 issue #3025 实证），FFmpeg `eq` 滤镜也确实支持 brightness/contrast/saturation/gamma（+ `hue` 滤镜管色相）。但三点致命：
  1. `vf*` 只能在 prepare 阶段设，播放中改滤镜要重建播放器（issue 原话 "must be applied on preparing stage, not during play stage"）——做不到拖动滑杆实时生效；
  2. 硬解（MediaCodec）下视频帧不走 CPU 滤镜链，滤镜是否生效未经证实，墨影视默认硬解；
  3. ijk 没有任何 shader/GL 挂载点，Anime4K 完全接不上。
- 决定：ijk 下五项调节与 Anime4K 都不支持；面板在 ijk 内核时显示不可用原因（不是静默失效）。调节值照样持久化，切回 Exo/mpv 即生效。后续如有人验证 `vf0=eq=...` 在 prepare 时挂载可行，可单独立项做“ijk 启动时应用已保存值”（不实时），不在 B6 范围。

### 2.6 SystemPlayer（系统播放器）

`SystemPlayerEngine` 是外抛给系统播放器的路线，无任何效果器挂载点；不支持，面板同 ijk 处理。短剧播放器走自己的 feed（ShortDramaActivity），不在本期范围（面板只挂 Vod 播放器）。

## 3. Anime4K 接入细节（只接 mpv）

- 档位：只上 **S 档**（`Anime4K_Upscale_CNN_x2_S`），与 BieCuiLe 一致。M/L 档（更大卷积、更吃 GPU）不在本期范围——S 档已是“手机能开”的上限，再往上发热风险陡增；后续用户真机测 S 档没压力再议。
- 文件：`app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`（上游 bloc97/Anime4K master 的 `glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl`，sha256 `4c53ec2e…49a4`，MIT 头完整保留，不改一字）。构建进 APK 约 +18KB，可忽略。
- 挂载：`Anime4kShaderStore.ensureShaderFile()`（assets → `cacheDir/mpv_shaders/`，文件已存在且长度一致则跳过拷贝）→ `MpvPlayer.setAnime4kShader(path)` → `applyShaderPipeline` 里 `change-list glsl-shaders append`。关闭时 `remove`。与 LUT 槽位独立管理（§2.2）。
- 生效条件（缺一不可，面板里逐项提示）：内核是 mpv；不是 surface-direct（`supportsNativeLut()` 为 false 即不可用）；开关开。片源分辨率 ≥ 输出分辨率时 shader 自身的 `//!WHEN` 会让它空转（不生效也不报错），不用我们再判。
- 性能定性（见 §5）：S 档是 5 个卷积 pass，每帧多 5 次全屏卷积；低端机 1080p→4K 放大时 GPU 负载明显上升。默认关；面板里放一句性能提示文案。

## 4. 参数持久化

- 新建 `player/picture/PictureAdjustSetting.java`（照抄 `LutSetting.java` 的写法：`Prefers` + 静态方法 + clamp）：
  - 五个 int key：`pic_brightness / pic_contrast / pic_saturation / pic_hue / pic_gamma`，各默认 0，读写 clamp 到 [-100, 100]。
  - 一个 boolean key：`pic_anime4k`，默认 **false**（关）。
  - 一个不可变值对象 `PictureAdjust`（五个 int + `isNeutral()` + `copyWith(...)`），Setting 与 PlayerManager/Effect 之间只传这个对象，不传五个散参。
- 持久化时机：滑杆每动一次就写（Prefers 写是轻量的；与 `LutSetting.putStrength` 同款粒度）。对话框关闭不做额外保存；“重置”按钮把五项写回 0（Anime4K 开关不动——重置只管五项调节，开关是独立功能）。
- 升级兼容：老版本没有这些 key，默认全 0 + Anime4K 关，升级后行为零变化（与 B5 开关默认关同款零回归）。

## 5. 性能与发热（必须写进 tracker 与汇报）

- Anime4K（mpv，S 档）：每帧 5 个全屏卷积 pass，GPU 算力与显存带宽双吃；1080p 源在 4K 屏上放大时最重，720p 源在 1080p 屏上次之。预期：中高端机可开，低端机可能掉帧/发热/耗电明显。**开关默认关**；面板里写明“低端机可能掉帧发热”；建议用户真机测：开 Anime4K 播 720p/1080p 动漫 10 分钟，看帧率（OSD 有帧率显示）、摸机身温度、看电量。
- 五项调节：mpv 侧是 VO 内原生 eq，成本可忽略；Exo 侧是 1 个全屏 quad pass（与 LUT 暖管线同成本），成本可忽略；拖动滑杆时每帧只改 uniform，不重建管线。
- 风险点（真机才能验）：① Exo 下 `PictureAdjustEffect` 与 `DynamicLutEffect` 双 effect 叠加时，Media3 的帧处理器在部分机型/编码组合下可能回退或报错（LUT 单 effect 已有暖管线实战，但双 effect 是新组合）；② mpv 下 Anime4K + LUT 双 shader 在部分 GPU 上编译失败（`glsl-shaders` 报错时 mpv 会记错但继续播——需真机看日志）；③ Exo 下 HDR 门禁若漏判，shader 会在 HDR 帧上跑出错误色彩（门禁复用 LUT 的 `isHdr`，漏判概率与 LUT 相同）。
- 回退：任何一处出问题，用户关 Anime4K / 把五项归零即回原样；调节值持久化不影响播放流程本身（播不了片永远先查内核/源，不查调节）。

## 6. 调节面板 UI

- 入口：播放器控制栏加“画面”按钮，与 LUT 按钮并列。落点（照抄 LUT 按钮的整条链）：
  - `PlayerButtonSetting` 加 `PICTURE_ADJUST = "picture_adjust"` 项（`DEFAULT` 列表里放在 LUT 后一位，默认可见；可配置显隐与排序，与 LUT 同款）；
  - `VodPlayerChrome` 加 `picture` 控件位（布局里 LUT 按钮旁）；
  - `VodPlayerUiController.bindInlineActions()` 加 `chrome.picture.setOnClickListener(view -> host.showPictureAdjust())`；
  - `VodPlayerUiHost` 加 `default void showPictureAdjust() {}`；
  - `TmdbDetailActivity` 实现 `showPictureAdjust()` → `PictureAdjustDialog.show(...)`（对照 `onInlineLut()` / `showLut()` 的落点，单元 5 实施时按实际代码对齐）。
  - 只做手机端 Vod 播放器（`VodPlayerUiController` 唯一构造点就是 `TmdbDetailActivity`）；直播/TV 播放器不加按钮（后续有人要再加，引擎层已通用）。
- 面板：新建 `ui/dialog/PictureAdjustDialog.java`（DialogFragment 或 AlertDialog，实施时按 `LutDialog`/`ChoiceDialog` 的现成形态定）：
  - 五项各一行：中文名 + Material Slider（-100..100，步进 1，当前值实时显示）+ 默认值 0 刻度提示；拖动即实时生效（调 `PlayerManager.setPictureAdjust(...)`），松手即已持久化。
  - 一行 Anime4K 开关（Switch）：默认关；旁边一句性能提示（“低端机可能掉帧发热，仅 mpv 内核可用”）；非 mpv 内核 / surface-direct 时开关置灰 + 一行不可用原因（复用 LUT 的不可用文案口径：内核不支持 / 直出模式 / HDR 下 Exo 调节不生效）。
  - 一个“重置”按钮：五项回 0（Anime4K 不动），实时生效。
  - 面板打开时读当前持久化值；面板不做“取消恢复”（改了就是改了，与 BieCuiLe 的实时面板一致）。
- 滑杆控件：用 `com.google.android.material.slider.Slider`（`SliderNumberDialog.java` 已有现成用法：`setValueFrom/setValueTo/setStepSize/addOnChangeListener`，可直接抄样式与颜色）；五项行用代码拼 LinearLayout（SliderNumberDialog 就是代码拼布局，无需新 XML；单元 5 实施时若行数多导致布局臃肿，再改抽 `dialog_picture_adjust.xml`）。
- strings：`play_picture_adjust`（按钮名“画面”）、五项名、Anime4K 开关文案、性能提示、重置、不可用原因——三端 `values / values-zh-rCN / values-zh-rTW` 都加（B5 同款三端）。

## 7. 设计研究门（AGENTS.md §7/§8 的证据记录）

B6 是 material 新功能（新用户能力 + 跨 Exo/mpv 管线），且动 mpv/Exo 播放器依赖面，按 §7/§8 做了有界的最佳实践审查；B6 不是上游 cherry-pick（无上游 commit 可摘），所以不建上游 commit ledger、不占 E*/P* 任务号，本文件就是唯一的任务文档。

| 证据类 | 来源（2026-11-11 访问） | 结论 | 等级 |
|---|---|---|---|
| mpv 官方手册 | mpv.io/manual（`--brightness/contrast/saturation/gamma/hue` -100..100 默认 0；`glsl-shaders` 列表；eq 属性非全 VO 支持） | 五项口径 -100..100 默认 0；Anime4K 走 glsl-shaders | 一手 |
| media_kit / mpv_kit_video | pub.dev `MpvVideoAdjustment` 枚举文档（五项 int -100..100，0 不变） | BieCuiLe 五项 = mpv 属性直通 | 一手 |
| Anime4K 上游 | bloc97/Anime4K master `glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl`，sha256 4c53ec2e…49a4，与 APK 内文件逐字节相同 | 只上 S 档；文件自包含 5 pass；MIT | 一手 |
| FFmpeg eq 滤镜 | ffmpeg-cookbook eq 文档（brightness -1..1、contrast、saturation、gamma 0.1..10） | Exo 侧 gamma 映射取 10^(s/100) 的口径来源；ijk 的 eq 可能性来源 | 二手 |
| ijkplayer 上游 issue | bilibili/ijkplayer #3025（`vf0` 只能 prepare 时设） | ijk 本期不支持的直接依据 | 一手 |
| 本地实现（范本） | `player/lut/DynamicLutEffect.java`（GlEffect + BaseGlShaderProgram + HDR 抛错）、`MpvPlayer.applyShaderPipeline`（glsl-shaders 单槽位）、`PlayerManager.applyLut/safeSetVideoEffects`（effects 组合点）、`LutEligibility`（HDR/surface-direct/DRM/tunnel 门禁）、`LutSetting`（Prefers 持久化范本）、`SliderNumberDialog`（Slider 用法）、`PlayerButtonSetting` + `VodPlayerChrome` + `VodPlayerUiHost`（按钮链） | 全部设计点都有本地现成套路可抄，无新依赖 | 一手（仓库内） |
| 不适用类 | 上游 webhtv 播放器 commit（B6 是墨影视自研功能，无上游对应物）、论文/基准测试（无公开的 Anime4K 手机端基准可引；性能结论靠真机实测，不编数字） | 已注明原因，不硬凑 | — |

方案比较：

| 方案 | 结论 |
|---|---|
| 不做 | 否决：用户已说“b6”，且 B 档只剩这一项 |
| 全内核统一 shader（Exo 也接 Anime4K） | 否决：Exo 接 Anime4K 需多 pass GlEffect 链 + 浮点中间纹理，未验证；v1 只 mpv，后续可单独立项验证 |
| ijk 用 vf0=eq 接五项调节 | 否决：prepare 时才能设、播放中改不了、硬解下未验证；v1 不做，面板明示不支持 |
| 黑边虚化一起做 | 否决：需第二层模糊视频（双路解码或额外 blur pass），是独立工程；本期不做，tracker 里记为后续项 |
| WebView/Flutter 嵌 media_kit（照搬 BieCuiLe） | 否决：即 B8（已排除）；墨影视已有三内核，mpv 原生属性就是同一条路 |
| **选定：mpv 原生属性 + 原生 shader；Exo 自写 GlEffect；ijk 不支持；黑边虚化不做** | 全部复用本地已有管线，零新依赖，零上游改动 |

## 8. 实施计划（单元 + 文件 + 提交）

按顺序串行做，每单元一个 guard 会话、一次 finish 提交。单元编号沿用 B5 的做法（0=本文档）。

| 单元 | 内容 | 文件（新建/改动） | 验收 |
|---|---|---|---|
| 0 | 设计文档（本文件） | 新建 `docs/b6-picture-adjust-anime4k.md` | 文档完整，含 Recovery anchor |
| 1 | 模型 + 持久化：`PictureAdjust` 值对象 + `PictureAdjustSetting`（Prefers，5×int + anime4k boolean，默认 0/false）+ strings（五项名/画面/Anime4K/重置/提示，三端） | 新建 `app/src/main/java/com/fongmi/android/tv/player/picture/PictureAdjust.java`、`PictureAdjustSetting.java`；改 `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` | 静态核对：key 不与现有 Prefers key 撞名；默认值 0/false；clamp 逻辑重读 |
| 2 | Exo 效果器：`PictureAdjustEffect implements GlEffect`（BaseGlShaderProgram，五项 uniform，§2.4 公式，中性短路，HDR 抛错照抄 DynamicLutEffect） | 新建 `app/src/main/java/com/fongmi/android/tv/player/picture/PictureAdjustEffect.java` | 静态核对：GLSL 括号/分号逐行读；uniform 名与 Java 侧 set 一致；`isNoOp` 与 DynamicLutEffect 同款恒 false；HDR 路径与 LUT 一致 |
| 3 | mpv 侧：`MpvPlayer` 加公开 `setVideoProperty(String, double)` + 第二 shader 槽位 `setAnime4kShader(@Nullable String)`（`applyShaderPipeline` 双槽位，Anime4K 在前、LUT 在后）；`Anime4kShaderStore`（assets → `cacheDir/mpv_shaders/`，已存在且长度一致跳过）；`MpvPlayerEngine` 加 `setPictureAdjust(PictureAdjust)` / `setAnime4kShader(String)` 转调；`CacheCleanupManager` + `CacheModuleRegistry` 里登记 `mpv_shaders` 目录 | 新建 `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`（上游原文件，一字不改）、`app/src/main/java/com/fongmi/android/tv/player/picture/Anime4kShaderStore.java`；改 `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`、`app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java`、`app/src/main/java/com/fongmi/android/tv/cache/CacheCleanupManager.java`、`app/src/main/java/com/fongmi/android/tv/cache/CacheModuleRegistry.java` | 静态核对：双槽位 append/remove 成对、不互相误删；`initialized` 守卫；shader 文件 sha256 与上游一致；清缓存登记处与 LUT 的 `mpv_lut_shaders` 对齐 |
| 4 | 引擎接口 + PlayerManager 接线：`PlayerEngine` 加默认方法 `supportsPictureAdjust()` / `setPictureAdjust(PictureAdjust)` / `supportsAnime4k()` / `setAnime4kShader(String)`（默认不支持/空实现，ijk/System 不用动）；`ExoPlayerEngine` 已有 `setVideoEffects`（不用动）；`PlayerManager` 加 `pictureAdjustEffect` 实例 + `currentVideoEffects()`（调节在前、LUT 在后）+ `applyPictureAdjust()` + 公开 `setPictureAdjust(PictureAdjust, boolean anime4k)`（写 Setting → 按内核分发 → Exo 刷 effect / mpv 设属性+shader / ijk 记不支持原因）；重建/换核/换片后重应用（挂在 `applyLut` 调用的同批点位）；HDR/tunnel/软解门禁复用 LUT 的条件 | 改 `app/src/main/java/com/fongmi/android/tv/player/engine/PlayerEngine.java`、`app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java`；`MpvPlayerEngine` 的实现落在单元 3，Exo 的 effect 实例化落在本单元 | 静态核对：`applyLut` 的调用点逐个确认是否要并行调 `applyPictureAdjust`（只加、不改 LUT 行为）；`safeSetVideoEffects` 的复用短路在双 effect 下仍成立；ijk/System 走默认方法零改动 |
| 5 | 面板 UI：`PictureAdjustDialog`（5 条 Slider 行 + Anime4K Switch + 重置；拖动实时生效；非 mpv/直出/HDR 时置灰 + 原因文案；Slider 用法抄 `SliderNumberDialog`） | 新建 `app/src/main/java/com/fongmi/android/tv/ui/dialog/PictureAdjustDialog.java` | 静态核对：Slider 范围 -100..100 步进 1；开关默认关；重置只清五项；不可用原因与 `LutEligibility` 口径一致 |
| 6 | 播放器按钮：`PlayerButtonSetting` 加 `PICTURE_ADJUST`（DEFAULT 里 LUT 后一位，默认可见）+ `VodPlayerChrome` 加 `picture` 位 + `VodPlayerUiController` 绑定 + `VodPlayerUiHost.showPictureAdjust()` + `TmdbDetailActivity` 实现（对照 `onInlineLut()` 落点） | 改 `app/src/main/java/com/fongmi/android/tv/setting/PlayerButtonSetting.java`、`app/src/main/java/com/fongmi/android/tv/ui/player/VodPlayerChrome.java`、`app/src/main/java/com/fongmi/android/tv/ui/player/VodPlayerUiController.java`、`app/src/main/java/com/fongmi/android/tv/ui/player/VodPlayerUiHost.java`、`app/src/main/java/com/fongmi/android/tv/ui/activity/TmdbDetailActivity.java` + 控制栏布局（LUT 按钮旁加一个，实施时按实际布局文件定） | 静态核对：按钮 id 与 chrome 字段一致；`PlayerButtonSetting` 的 DEFAULT 顺序；TmdbDetailActivity 的 host 方法与 `showLut()` 对齐 |
| 7 | 收尾：tracker 更新（`docs/b-ui-b1-b7-port.md` 的 B6 行）+ 全量静态核对（diff --check、类型、底栏 6 项、硬限制）+ 汇报 | 改 `docs/b-ui-b1-b7-port.md` | 汇报见 §10 |

## 9. 验证计划

- 上游文件核对（已做）：Anime4K shader sha256 `4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4`，与 APK 内文件逐字节相同。
- 静态核对（每单元提交前）：`git diff --check`；改动行重读（返回值类型、泛型、null 守卫——尤其 `MpvPlayer` 新增公开方法的 `initialized` 检查与 `applyShaderPipeline` 的双槽位成对操作）；`PlayerEngine` 默认方法不能破坏 ijk/System 的编译（它们不覆写，走默认）；底栏仍 6 项；开关默认关；Anime4K 文件 sha256 复核。
- 编译：本机无 SDK，靠 GitHub 构建（用户新规矩：有更新直接编；但 B6 代码未推，构建待用户说“推”后再触发）。
- 真机验证（推后编测试版才能验，用户测，见 §10 建议）：五项滑杆在 Exo/mpv 下实时生效、归零恢复原样；切 ijk 后面板提示不支持、切回后上次的值还在；Anime4K 开后 720p 动漫明显变锐、无掉帧；HDR 片源下 Exo 调节不生效（有提示）、mpv 下生效；开 Anime4K 播 10 分钟看发热/帧率/电量；LUT 与 Anime4K 同时开不闪退、不报错。

## 10. 汇报清单（做完后交回主对话）

设计文档路径、实现了什么、改了哪些文件、commit 号、哪些内核支持五项调节、哪些内核支持 Anime4K、哪些没接上及原因（ijk、SystemPlayer、黑边虚化）、没做完/有风险的地方（§5 的三点）、建议用户先测什么（§9 真机验证 + 性能/发热注意事项）。

## 11. 与 BieCuiLe 的差异（诚实记录）

- BieCuiLe 的五项调节在它三个内核（media_kit/mpv/ijk）下据称都可用（Flutter 侧统一调 mpv 属性——它的 ijk 也是包在 media_kit 的 mpv 之外的另一路，具体行为反编译拿不到）；墨影视 v1 只在 Exo/mpv 做，ijk 明确不支持（§2.5 有据）。
- BieCuiLe 的 Anime4K 档位未知（只确认它带了 S 档这一个文件）；墨影视 v1 只上 S 档。
- BieCuiLe 有“黑边虚化”，墨影视 v1 不做（§1.4/§5）。
- BieCuiLe 的调节面板在播放器内是常驻面板形态（Dart 侧）；墨影视做成弹窗对话框（原生侧无常驻面板容器，弹窗是 LUT 同款形态）。
- 滑杆数值口径一致（-100..100 默认 0），但 Exo 下的伽马曲线是墨影视自定映射（§2.4），与 mpv 原生映射不完全一致；默认 0 时两者均为原样。
