# B17 TMDB 人物（B8 全量迁移）

> §0 执行声明：本批把手机端 TMDB 人物页（TmdbPersonActivity，958 行，含照片查看器）迁移到 Flutter，路由 `/person?personId=&name=&subtitle=&profile=&department=&biography=&siteKey=&detailMode=`（C1 引擎）。
> 任务护栏 `b8-b17-tmdb-person`（standard），分支 `b8-flutter-rewrite`，基线 B16 提交 `54c931bb`。
> 开关 `flutter_b17_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 人物页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

### 1.1 人物页

原生实现：`app/src/main/java/.../ui/activity/TmdbPersonActivity.java`（958 行，main 源集，与 TV 共用一个 Activity，leanback 有 `dispatchKeyEvent` 分支）+ 布局 `app/src/main/res/layout/activity_tmdb_person.xml`（两列 230dp 侧栏 + 内容）与 mobile 覆盖 `app/src/mobile/res/layout/activity_tmdb_person.xml`（竖向单列）。

- **形态（mobile 布局）**：头像卡 120×170dp；名字 23sp 加粗；personalInfo 12sp；评分块内联（标题 13sp + 分值 24sp + meta 12sp 最多 3 行）；简介 13sp；照片横排 142dp 高，卡片 92×138dp（`adapter_tmdb_person_photo.xml`，marginEnd 12dp）；作品行：海报 72×102dp、标题 15sp（手机最多 2 行、行最小高 132dp）、meta 11sp 最多 2 行（`formatMeta` 把 " · 评分" 拆成换行）、credit 12sp 绿色、简介 12sp 最多 2 行。
- **加载流水线**（`loadPerson`）：`tmdbConfig.isReady()` 为假 → 隐藏进度条 + `Notify` `detail_tmdb_need_key`，直接返回（无错误态）；否则子线程依次调 `TmdbService.person` → `personProfile` → `personPhotos` → `personCastWorks` / `personCrewWorks` / `personWorks` → `personScore`，回主线程 `bindPerson`；异常时只隐藏进度条 + toast（`detail_person_empty` 或异常消息）。
- **初始人物**（`setInitialPerson`）：先按 intent extras 显示名字/副标题/简介/头像（`ImgUtil.load`），快照到达后替换；副标题初始隐藏，`bindPerson` 后才显示 profile 的副标题。
- **数据（`TmdbService`，实例方法）**：`person(personId, config)` 一次请求，`append_to_response=combined_credits,images,translations,external_ids`，缓存 7 天（`PERSON_CACHE_TTL`）；`personProfile(detail, config)` → `TmdbPerson`，副标题 = 部门 · 生日[-忌日] · 出生地 · 别名，以 " · " 连接；`personPhotos` 最多 24 张、去重；`personWorks` 全部作品按日期倒序、限 30；`personCastWorks` 限 60；`personCrewWorks` 限 60。
- **评分**（Activity 内 `personScore`，按 `combined_credits` 计算）：权重 cast 1.0 / director 2.0 / production 1.3；经验分 `32·(1−exp(−weightedCount/15))`；质量分 `58·weightedVote/10`（weightedVote 按角色加权平均，导演权重 2.0、其他幕后 1.3）；focus 加分 `10·(leading/weighted)·(1−exp(−leadingCount/8))`；总分 0–100 封顶；focus 取权重最大的类别（cast/director/crew/all）。`bindScore` 在 `totalWorks() <= 0` 时隐藏整个评分组。
- **筛选**（`filteredWorks` / `refreshFilterButtons` / `setFilter`）：6 档 all/cast/crew/director/movie/tv；数据源 cast→castWorks、crew/director→crewWorks、其余→allWorks；movie/tv 按 mediaType 过滤；director 按 credit 含 director/directing/导演判断；`setFilter` 在所选档数量为 0 时回落到 all；按钮按数量倒序排列、数量为 0 的隐藏，"all" 在有作品时排第一。
- **照片查看器**（`showPhotoDialog`，移动端）：全屏黑色 Dialog、FIT_CENTER、38dp 进度转圈；点按分区：左 1/3 上一张、右 1/3 下一张、中间关闭；fling（≥48dp 且速度 ≥120）切换；长按 → 保存弹窗（仅移动端）；MENU 键保存；左上角旋转按钮（仅移动端，横竖屏切换）；右上角保存按钮，经 `TmdbImageSaver.save(FragmentActivity, url, Callback)` 保存原图（`highResTmdbImage` 把 `/t/p/<尺寸>/` 换成 `/t/p/original/`）；leanback 另有底部栏（上一张/保存/下一张/关闭）。
- **作品点卡**（`openWork`）：站源为空/不可搜索 → `SearchActivity.direct(title)`；否则先 toast `detail_work_searching`，子线程 `SiteApi.searchContent(site, keyword, false, "1")` → `Result.getList()` → `bestVod` 评分（名字全等 300、包含 220、备注 120，必须 >0）→ `TmdbDetailActivity.start(siteKey, id, name, pic, "", item, detailMode)`（8 参数重载）；无匹配 → toast `detail_work_global_searching` + `SearchActivity.direct(title)`。
- **明暗主题**（`resolveLightTheme`）：`Setting.resolveTmdbDetailLightTheme(getTmdbDetailTheme(), systemNight)`；浅色背景 0xFFF4F7FA、深色 0xFF101820，两套文字色。
- **不在本批范围**：`TmdbPersonDialog`（575 行，`ui/dialog/TmdbPersonDialog.java`）是另一个独立弹窗（mobile `VideoActivity` 头像点击与 leanback 共用），按规划全部 `*Dialog` 保持原生，B17 不动。

### 1.2 入口

- **分流点（唯一一处）**：`TmdbPersonActivity.start(Activity, TmdbPerson, String, int)`（4 参数重载）内，null/personId 守卫之后，`Setting.isFlutterB17Enabled() && Util.isMobile()` 时拼路由 `/person?...` 调 `C1ScreenActivity.start(activity, route)` 并返回；否则走原 intent。3 参数重载委托给 4 参数重载，所以两个调用方都被覆盖。
- **调用方（全文 grep，仅 2 处）**：
  - `TmdbDetailActivity.loadPersonDetail`（约 12794 行）：经 cast/creator/guest 三个 `TmdbPersonAdapter`；personId<=0 时回落 `SearchActivity.direct(name)`，`!canMatchTmdb()` 时 toast `detail_tmdb_need_key`，否则 `start(this, person, getKeyText(), getDetailMode())`。这两个前置分支在分流点之前，不受影响。
  - `WebHomeThemeBridge.openPerson`（约 335 行）：`TmdbPersonActivity.start(activity, person, site.getKey())`。
- **Activity 声明**：`TmdbPersonActivity` 在 mobile 与 leanback 两端 manifest 均有声明（main 源集 Activity，与 TV 共用）；B17 只分流 mobile 的 `start()` 调用，Activity 本身不动，TV 入口因 `Util.isMobile()` 守卫不受影响。

### 1.3 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 27 个 B17 用到的字符串只在 `app/src/main/res/values*`（values + zh-rCN + zh-rTW 各 3 个文件），mobile/leanback res 里一个都没有：`detail_person_info`、`detail_person_score_title`、`detail_person_score_value`、`detail_person_score_meta`、`detail_person_biography`、`detail_person_photos`、`detail_person_works`、`detail_person_work_count`、`detail_person_empty`、`detail_filter_all/cast/crew/director/movie/tv`、`detail_work_searching`、`detail_work_global_searching`、`detail_tmdb_need_key`、`detail_tmdb_empty`、`detail_image_rotate/save/previous/next/close/saving/save_success/save_failed`、`error_play_timeout`、`error_retry`。所以 impl 里直接 `R.string` 引用是 leanback 安全的，无需 `mobileString()`。
- `SearchActivity.direct(Activity, String)` 在 mobile 与 leanback 两源集同签名（B2 前例），main 源集 impl 直接引用安全。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b17_enabled`，默认 false，独立开关；开关 UI 只在实验屏（C0 屏新加一行）。
- **入口分流**：见 §1.2。路由参数与原生 intent extras 一一对应（personId/name/subtitle/profile/department/biography/siteKey/detailMode，全部 URL 编码；简介几 KB，远低于 Intent 1MB 限制，与 B8 的 Class JSON 同款做法）。开关关时原 intent 链路逐字不变。

### 2.2 快照与完成规则（本批核心设计）

- `getPersonShell()` 同步取页面静态文案（简介/信息/照片/作品/评分标题、重试、未配置 TMDB、保存、旋转），Dart 先渲染页面骨架。
- `getPerson(personId, siteKey, detailMode)` 是 `@async` 一次性快照（B8 形态）：子线程跑原生 `loadPerson` 流水线（§1.1 顺序）；`Completion` 看门狗 35s，超时返回错误快照（`error_play_timeout`）；每个路径恰好完成一次；迟到的后台完成是 no-op。`siteKey/detailMode` 存为会话（`volatile` 字段 + 原子换 map），供 `openPersonWork` 用。
- **失败形态**：未配置 TMDB → `needKey=true` 的快照（原生是 toast 后返回）；请求失败/无数据 → `errorText` 快照（`detail_tmdb_empty` 或超时文案）；Dart 在初始头卡下方显示状态行 + 重试按钮（见 §5 V9）。
- **resumed 重取**（规划 §4.6）：Dart 在 `resumed` 时重调 `getPerson`；`TmdbService.person` 有 7 天缓存，重取便宜。无 EventChannel。
- **跨屏导航恒走 host**：作品点卡、照片保存、旋转都是 host 方法（fire-and-forget 同步 void，toast 全在 host 侧）；两个点卡去向（`TmdbDetailActivity`、`SearchActivity.direct`）都是原生 Activity，**不存在 Flutter→Flutter 的 G2 引擎复用竞态**（B7/B10 的 G2 只发生在 Flutter 屏互相启动时，本批没有）。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b17_person_messages.dart`）

- `B17HostApi`：`isB17Enabled` / `setB17Enabled`；`getPersonShell()`；`@async getPerson(int personId, String siteKey, int detailMode)`；`getWorkCountText(int count)`（作品数文案依赖 Dart 侧筛选结果，所以 Dart 问 host 格式化，不硬编码）；`openPersonWork(int tmdbId, String mediaType, String title)`；`savePersonPhoto(String photoUrl)`；`togglePhotoOrientation()`。
- `B17PersonScreen`：`loaded` / `needKey` / `errorText` + 人物字段（name/subtitle/简介/评分可见性/分值/meta 预拼多行文案）+ 头像 PicSpec 字段组 + `photos`（`B17PhotoItem`，含原图 `fullPicUrl`）+ `allWorks` / `castWorks` / `crewWorks`（`B17WorkItem`，含海报 PicSpec 字段组）+ `filters`（`B17FilterItem`，host 排好序：all 在前、其余按数量倒序、只含数量 >0 的档）。
- **扁平数据类**：PicSpec 字段组（url/referer/cookie/user-agent/extraHeadersJson/fallbackUrl/placeholderText/placeholderColor）按 B2/B8 前例在每个条目类里平铺，不共享类型。Java 侧 int 一律装箱（`Long`，见 C1 教训；`setPersonId/setCount/setTmdbId/setPlaceholderColor` 全部 `(long)` 强转）。

### 2.4 Dart 页（`flutter_module/lib/b17_person_page.dart`）

- `B17PersonPage`（`/person` 路由，`onGenerateRoute` 分支，B8 V1 同款）：先按路由参数显示初始人物（头像 120×170、名字 23sp 加粗、副标题 12sp、简介 13sp），快照到达后替换；评分块只在 `scoreVisible` 时显示（标题 13sp + 分值 24sp + meta 12sp）；照片横排（142dp 高、卡片 92×138、间距 12dp，点按开查看器）；作品区：host 给的筛选 chips + 作品数文案 + 作品行（海报 72×102、标题 15sp 最多 2 行、meta 11sp 最多 2 行、credit 12sp 绿色、简介 12sp 最多 2 行），点卡调 host。
- **筛选在 Dart 侧本地算**：数据源选择（cast→castWorks、crew/director→crewWorks、其余→allWorks）与 movie/tv/director 过滤逐字抄原生 `filteredWorks`（director 判断：credit 小写含 director/directing/导演）；当前筛选不在 host 返回的 chips 里时回落到 all（原生 `setFilter` 同款规则）。
- **照片查看器**（`_PhotoViewer`，`showDialog` 全屏黑底）：`PageView` 滑动切换；点按分区（左 1/3 上一张、右 1/3 下一张、中间关闭）；长按 → 单项保存菜单；左上旋转按钮、右上保存按钮；原图 `Image.network`（FIT_CENTER 即 `BoxFit.contain`）+ 38dp 白色转圈 + 加载失败图标。无缩放（原生 FIT_CENTER 同样无缩放）。
- **图片**：头像/照片缩略图/作品海报走共享 `PicImage`（host 的 `ImgUtil.getUrl` 委托，D3）；`data:` 显示占位（D6）；查看器原图用同一 PicSpec 的 headers 直连 `Image.network`（`PicImage` 是 cover + 固定高，不适配 contain 全屏图）。

### 2.5 Host 实现（`B17HostApiImpl.java`）

- `buildScreen` = 原生 `loadPerson` 压平：配置检查 → `person` → `personProfile` / `personPhotos` / 三份作品列表 → `personScore`；`resolvePic`（B2/B8 同款复制，`ImgUtil.getUrl` 委托 + `TmdbProxy.nextAutoImageUrl` 回退）处理头像/照片/海报；`highResTmdbImage` 逐字抄原生（`/t/p/<尺寸>/` → `/t/p/original/`）。
- `personScore` / `scoreFocus` / `isDirectorCredit` / `creditKey` / `voteAverage` / `array` / `string` / `personBiography` / `normalize` / `filteredWorks` / `filterCount` 逐字抄 `TmdbPersonActivity`（带漂移警告：原生改权重时本复制必须同步改）；`PersonScore` 记录在 impl 内压平（只留分值字段）。
- `openPersonWork` = 原生 `openWork` 逐字抄：`bestVod` / `scoreVod` / `normalizeTitle` / `searchCurrentSite` 全抄；站内搜索在 `Task.execute`，结果导航回主线程；`activity.isFinishing() || isDestroyed()` 守卫；查不到作品（快照过期）时回落 `SearchActivity.direct(title)`。
- `savePersonPhoto` = 原生保存路径逐字抄（`detail_image_saving` toast + `TmdbImageSaver.save` + 成功/失败 toast），`savingPhoto` in-flight 标志替代按钮禁用；`togglePhotoOrientation` 逐字抄原生（含 `Util.isMobile()` 守卫）。
- 全部 27 个字符串直接 `R.string` 引用（§1.3 已验证 main-res）；`SearchActivity.direct` 双 flavor 同签名，直接引用。

### 2.6 文件清单

| 文件 | 动作 |
|---|---|
| `flutter_module/pigeons/b17_person_messages.dart` | 新（B17HostApi + B17PersonShell/Screen/PhotoItem/WorkItem/FilterItem） |
| `flutter_module/lib/b17_person_page.dart` | 新（人物页 + `_PhotoViewer`） |
| `flutter_module/lib/main.dart` | 改（`/person` 路由分支 + B17 开关；不动已有路由） |
| `app/src/main/java/.../flutter/B17HostApiImpl.java` | 新 |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | 改（注册 B17HostApi、加 `ROUTE_PERSON`） |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | 改（C0 引擎上注册 B17HostApi，只为实验屏开关用） |
| `app/src/main/java/.../setting/Setting.java` | 改（`flutter_b17_enabled`，默认 false） |
| `app/src/main/java/.../ui/activity/TmdbPersonActivity.java` | 改（`start()` 4 参数重载内唯一分流点 + `encode` 辅助；原生逻辑一行不删） |
| `.gitignore` | 改（加 `B17Messages.java`） |
| `.github/workflows/b8-c0.yml` | 改（Pigeon 步骤加 b17 生成 + 存在性检查，B16 同款） |
| `docs/b8-b17-person.md` | 新（本文档） |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 显示项 | 来源（host） | 备注 |
|---|---|---|
| 名字/副标题/简介 | `personProfile` + `personBiography` | 副标题 = 部门 · 生日[-忌日] · 出生地 · 别名 |
| 头像 | `resolvePic(name, profileUrl)` | PicSpec 字段组 |
| 评分分值/meta/可见性 | `personScore`（Activity 内逐字复制）+ `bindScore` 规则 | meta 预拼多行文案 |
| 照片列表 | `personPhotos`（≤24，去重）+ `highResTmdbImage` 原图 | 缩略图与原图同 headers |
| 作品列表 | `personWorks`（≤30）/ `personCastWorks`（≤60）/ `personCrewWorks`（≤60） | 标题/副标题/简介/credit/评分原样透传 |
| 筛选 chips | `refreshFilterButtons` 规则压平 | 顺序与数量 host 算好 |
| 作品数文案 | `getWorkCountText(count)` | `detail_person_work_count` |
| 全部静态文案 | `getPersonShell()` | 简介/信息/照片/作品/评分标题、重试、未配置、保存、旋转 |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 人物信息

- [ ] 详情页点人物头像/名字 → 开关开时进 Flutter 人物页，关时进原生页；Web 主题里点人物同样分流。
- [ ] 人物名、副标题（部门 · 生日 · 出生地 · 别名）、简介与原生一致；简介为空时显示 `detail_person_empty` 文案。
- [ ] 评分块：有作品时显示分值 + meta（方向、参演/导演/幕后数、作品均分），无作品时整块隐藏；分值与原生逐项比对。
- [ ] TMDB 未配置时：原生 toast 提示后停留在初始信息；Flutter 页在初始头卡下方显示提示文案 + 重试按钮。

### 4.2 照片查看器

- [ ] 点照片缩略图 → 全屏黑底查看器，原图清晰（`/t/p/original/`）。
- [ ] 左点上一张、右点下一张、中间点关闭；左右滑动切换；转圈 38dp，加载失败有提示图标。
- [ ] 长按 → 保存菜单 → 保存后 toast（成功含路径 / 失败文案）；右上保存按钮同样。
- [ ] 左上旋转按钮：横竖屏切换，旋转后页面不丢（路由/照片位置）。
- [ ] 照片为空时照片区整段隐藏。

### 4.3 作品列表

- [ ] 筛选 chips 顺序与原生一致（all 在前、其余按数量倒序、数量为 0 的档不出现）；点筛选 → 列表与作品数文案同步变化。
- [ ] 作品行：海报、标题（最多 2 行）、meta、绿色 credit、简介（最多 2 行）与原生一致。
- [ ] 作品为空时作品区整段隐藏。

### 4.4 点卡

- [ ] 点作品 → 当前站源有结果时进详情页（带 TMDB 条目）；无结果时 toast 后进聚合搜索；站源不可用时直接进聚合搜索。
- [ ] 点卡后返回人物页，人物页状态还在（resumed 重取）。

### 4.5 开关与回滚

- [ ] 实验屏关掉 B17 开关 → 人物入口回到原生页；再打开 → 回到 Flutter 页。
- [ ] TV 端人物页完全不受影响（leanback 详情页点人物仍走原生）。

## §5 相对规划的全部偏差

- **V1 明暗主题不复刻**：原生按 `resolveTmdbDetailLightTheme`（设置 + 系统夜间）切浅色 0xFFF4F7FA / 深色 0xFF101820 两套色；Flutter 页用共享亮色主题（与全部已迁移批一致），Dart 侧不感知主题设置。
- **V2 查看器 fling 阈值不逐字相等**：原生 fling（≥48dp 且速度 ≥120）切换；Dart 用 `PageView` 滑动切换，阈值由 Flutter 手势系统决定。
- **V3 查看器无序号指示**：原生移动端查看器也没有计数器，Dart 同样没有；一致。
- **V4 MENU 键保存不处理**：遥控器按键，只在 leanback 生效；移动端 Flutter 页不处理（原生移动端同样无此入口）。
- **V5 leanback 查看器底栏不迁移**：上一张/保存/下一张/关闭底栏只属于 TV 端，TV 不动；移动端查看器用点按分区 + 滑动。
- **V6 作品数文案晚一帧**：原生 `setFilter` 时同步刷新；Dart 在快照到达与筛选切换时异步问 host 取文案，切换瞬间计数行可能晚一帧才更新。
- **V7 筛选按钮形态近似**：原生是 `MaterialButton` 横向均分（手机宽）+ 焦点导航（TV）；Dart 用 `ChoiceChip` 横向滚动，无焦点导航；顺序与可见规则与原生一致（host 返回的顺序）。
- **V8 作品行无最小高**：原生手机行最小高 132dp；Dart 行高由内容自然决定。
- **V9 失败态多一个重试按钮**：原生加载失败只隐藏进度条 + toast，页面停留在初始人物信息；Dart 保留初始头卡（与原生一致），在其下方加状态行 + 重试按钮（B8 前例）。
- **V10 保存按钮不禁用**：原生保存时禁用按钮 + `savingTmdbPhoto` 标志；Dart 只有 host 侧的 in-flight 标志，重复点击在 Dart 侧不禁用（host 忽略第二次调用）。
- **V11 图片无磁盘缓存**：与 C2 相同，`Image.network` 只有内存缓存，滑动返回会重新加载。
- **V12 `TmdbPersonDialog` 不在范围**：`VideoActivity` 头像点击的人物弹窗与 leanback 共用，按规划全部 `*Dialog` 保持原生。
- **V13 路由多带 `department` 参数**：与原生 intent extras 一一对应；Dart 侧目前只用它做路由契约记录（副标题以快照为准），不单独渲染。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成（最担心）**：新形态——`@async` 返回含 5 个 `List<>`（照片 + 三份作品 + 筛选 chips）的同一快照；`B17HostApiImpl` 手写实现与生成接口的逐方法匹配（`Long` 参数、`Builder` 字段名、`Result<B17PersonScreen>`）。CI 是唯一证明。
2. **照片查看器手势与旋转（最担心）**：点按分区/滑动切换/长按保存/旋转按钮在真机上的表现；旋转后 Flutter 嵌入 Activity 是否重建引擎或丢路由（进程/配置变化后路由不恢复是全量规划已接受的缺口，规划 §4.4）。
3. **作品点卡**：站内搜索（`SiteApi.searchContent` 子线程）+ `bestVod` 评分 + `TmdbDetailActivity.start` 8 参数重载在真机上的表现；无结果与站源不可用两条回落链路。
4. **图片一致性**：头像/照片/作品海报的 `ImgUtil.getUrl` 委托 + `TmdbProxy` 回退在真机上的表现；`data:` 占位；原图（`/t/p/original/`）加载速度。
5. **评分复刻**：逐字复制的 `personScore` 权重与 focus 文案与原生逐项比对（同一人物两边各看一次）。
6. **leanback 编译**：全部 27 个字符串 main-res；`SearchActivity.direct` 双 flavor 同签名；`C1ScreenActivity` 引用在 main 源集内；分流有 `Util.isMobile()` 守卫。CI 联编时 leanback flavor 是证明。
7. **本页无文本输入**：不涉及 B5 的键盘/IME 待证明项。

## Recovery anchor

- **目标**：B17 把手机端 TMDB 人物页（TmdbPersonActivity，958 行，含照片查看器）迁移到 Flutter，路由 `/person`（C1 引擎），开关 `flutter_b17_enabled` 默认关。
- **状态**：本地实现完成，未推送。基线 B16 提交 `54c931bb`；分支 `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **已完成**：只读发现（§1）；Pigeon 合约 + Dart 页（含 `_PhotoViewer`）+ `B17HostApiImpl`（`personScore` 等逐字复制）+ main.dart 路由/开关 + C1/C0 注册 + Setting 开关 + `TmdbPersonActivity.start()` 唯一分流点 + `.gitignore` / `b8-c0.yml` + 本文档。验证：`git diff --check`（finish 时复核）、作用域 diff 复核、main 仍为 `f39a6e0`。
- **未验证**：全部构建与运行（本机无 SDK）；§6 的 7 项留给 CI 联编 + 真机验收。
- **下一步（恰好一个）**：B18 小组件浏览（`WidgetBrowseActivity`，417 行）——按 `docs/b8-full-migration-plan.md` §3 顺序，起新任务护栏 `b8-b18-widget-browse`，新合约 `b18_widget_browse_messages.dart`，开关 `flutter_b18_enabled` 默认关，不推送。
