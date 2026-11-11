# 隐藏下载功能（hidden-download）

## Recovery anchor

- 目标：隐藏的“下载”功能。默认关闭、关闭时设置列表不出现；关于页版本号连点 5 次开启；第一个隐藏选项是“下载”开关；开关打开后下面显示下载列表；打开后详情页选集行排序图标左侧出现下载按钮，点开弹窗多选集数，底部有全选 / 反向选择 / 开始下载；视频下到手机内置存储 Download 文件夹，按剧名建子文件夹。
- 分支/HEAD：main @ 40c38ed4（2026-11-12 00:26 起步），工作树干净，无其他 guard 会话。
- 完成标准：本地多笔提交（每笔 recovery tag），只做静态核对（无 SDK，无法编译）；全部会话做完并提交后，直接推到新仓库 xinyonghuu/moyingshi（gh 加 -R，绝不碰 .github/workflows），推完核对线上 main，再直接触发测试版构建（release_channel=beta，prerelease=true，publish_oci=false，release_notes 写清隐藏设置、下载开关、下载列表、选集下载弹窗、Download/<剧名>/ 文件夹）；触发前确认没有别的构建在跑，有在跑就等它跑完。构建跑完核对 4 个 APK 齐全后汇报 tag 和链接。构建失败先看日志诊断，一处小错可修一轮再推再编，修不好就汇报，别硬推。（2026-11-12 00:31 用户新指示：做好直接推，明早测试；取代之前“只在本地改、不推不编”。）
- 当前状态：调查完成，设计已定。会话 A 已完成（偏好 + 关于 5 连点 + 22 条字符串×3 locale），会话 B 已完成（下载引擎 4 个文件），会话 C 已完成（设置页隐藏分组 + 下载开关 + 按剧名分组的下载列表：点剧名展开集数，长按出删除按钮，确认后真删文件夹 + 记录），会话 D 已完成（详情页选集行排序图标左边加下载按钮 + 选集下载弹窗：全选/反向选择/开始下载），待提交。下一步：推到新仓库 + 触发测试版构建（见完成标准），构建跑完核对 4 个 APK 并汇报。

## 调查结论（2026-11-12）

- targetSdk 28 / minSdk 24 / compileSdk 37。targetSdk 28 → 传统存储模式，`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` 直接 File 读写合规，不写死 /sdcard 路径；首次下载前用现有 `PermissionUtil.requestFile` 要存储权限（manifest 已有 READ/WRITE_EXTERNAL_STORAGE）。
- 设置页：`app/src/mobile/java/.../ui/fragment/SettingFragment.java`（708 行），`buildGroups()` 用 `addGroup`（标题 + MaterialCardView）+ `addRow`/`addConfigRow` 建组；开关行 pattern 是普通行 + 右侧值 开/关，点按取反（如无痕模式 `setIncognito`）。`onResume()`/`onHiddenChanged()` 只调 `setText()`，不重建分组——隐藏开关变化后需在 onResume 比对快照后重建分组。
- 关于：`ui/dialog/AboutDialog.java`，版本号在 `binding.version`（DialogAboutBinding），目前无点击监听。在其上加 5 连点计数（间隔 >2s 清零），第 5 下切换 `hidden_settings_enabled` 并 Toast。
- 偏好：`setting/Setting.java`，`Prefers.getBoolean(key[, def])` / `Prefers.put(key, value)`。新增 `hidden_settings_enabled`（默认 false）、`download_enabled`（默认 false）。
- 选集行：`app/src/main/res/layout/activity_tmdb_detail.xml` 618-740 行 `episodeHeader`：episodeLabel（选集）+ episodeTitle + Space + episodeReverse（排序，40×36dp，icon 20dp 白）+ episodeFileName + episodeViewMode（各 marginStart 8dp）。下载按钮插在 episodeReverse 之前，同尺寸同样式（MaterialButton outlined，backgroundTint colorSurfaceContainerHigh，icon 20dp 白，cornerRadius 18dp），episodeReverse 补 marginStart 8dp。接线在 `TmdbDetailActivity` 914-926 行附近（点击/按键/焦点链）及 2033/2458 行的 tint helpers；可见性在选集渲染处跟 `Setting.isDownloadEnabled()` 联动。
- 选集数据：`TmdbDetailActivity` 字段 `vod`（380 行）、`selectedFlag`（397 行），`visibleEpisodes(List<Episode>)`（6535 行）；`Episode.getName()` / `getUrl()`；`Vod.getSiteKey()` / `getId()` / `getName()`。
- 解析播放地址：`SiteApi.playerContentIsolated(siteKey, flag, episodeUrl)`（用 isolated 版，不干扰当前播放源）→ `Result`，最终地址 `result.getUrl().v()`，请求头 `result.getHeader()`。与播放同一条解析链（含 parse 跟随），返回的就是播放器播的地址。
- 无现成视频下载代码（只有 gitcloud/lab/cache 的下载引用）。Media3 1.11.0-alpha01-fongmi 的 DownloadManager 在 media3-exoplayer 内，但它写自有缓存格式、不是用户可见的普通视频文件，不用。
- 下载引擎：OkHttp（`com.github.catvod.net.OkHttp`：`client(timeout)` / `newCall(client, url, headers)` / `string(url, headers)`）。后台用 `Task.submit` / manager 内单线程 Executor。
- 本地播放：`VideoActivity.start(activity, "file://" + 绝对路径)` → push 路径 → `SiteApi.playerContent` 遇 `isLocalFileUrl` 走 `pushPlayer` 直接播本地文件，已有通路。
- 字符串：`app/src/main/res/values/strings.xml`（英文默认）、`values-zh-rCN/`、`values-zh-rTW/` 三处同步。
- 设置搜索索引 `SettingSearchIndex.java`：不索引隐藏行，避免搜索泄露隐藏功能。

## 设计决定

1. **存储**：`Download/<清洗后剧名>/`。直链文件：`Download/<剧名>/<剧名> <集名>.<ext>`；m3u8：`Download/<剧名>/<剧名> <集名>/index.m3u8` + 同目录 ts/key/初始化段。文件名清洗：`[/\\:*?"<>|]` 及控制字符 → `_`，去首尾空白/点，空剧名 → “未知剧名”。同一部剧重复下载同一集不重复建文件夹（mkdirs 幂等）；已存在目标文件 → 跳过下载、直接记为已完成，不覆盖；已在队列/下载中的同一集不重复入队。
2. **下载方案**：直链（mp4/mkv/mov/avi/flv/webm/ts 等，按扩展名或 Content-Type 判断）用 OkHttp Range 断点续传，写 `.part` 临时文件后改名。m3u8：取 playlist；master 选最大 BANDWIDTH 的 variant；逐段下载（每段重试 3 次、支持 Range 续传）；EXT-X-KEY 下载 key 文件并把 URI 改写为本地相对路径；EXT-X-MAP 初始化段同样下载改写；本地 index.m3u8 用相对路径引用各段，可直接播。DASH（.mpd）及其他类型 v1 不支持，任务失败并明示。失败重试：整集失败后保留 .part，下次可续传；重试按钮暂不做（见“未做”）。
3. **并发**：单线程队列逐集下载，避免冲垮站点；解析地址（playerContent）逐集在后台调。
4. **任务记录**：内部存储 JSON 文件（`getFilesDir()/download_tasks.json`）持久化任务（剧名、集名、源站 key、flag、episodeUrl、最终 URL、状态、进度、文件路径）；应用被杀后下载中任务下次启动回到排队状态，.part 可续传。
5. **下载列表（按剧名分组）**：隐藏分组内下载开关下方容器，仅开关打开时显示。列表按剧名分组，一部剧一行；点剧名行展开，显示该剧已下载的各集（集名 + 状态/进度），点已完成的一集 → 本地播放；长按剧名行 → 该行出现“删除”按钮；点删除先弹确认框（标题 download_delete_title，内容带剧名），确认后真正删除：递归删掉 `Download/<剧名>/` 文件夹里的全部文件和文件夹本身，删掉这部剧的全部下载记录，列表里这行消失并刷新。删除失败（文件删不掉）要 Toast 明示，不静默。
6. **隐藏开关再点 5 次**：对称切换——已开启时再连点 5 次关闭隐藏设置并 Toast“隐藏设置已关闭”，分组消失。
7. **选集弹窗**：新 Dialog（RecyclerView 多选打勾）+ 底部“全选”“反向选择”“开始下载”三个按钮。确认后按当前显示顺序逐集入队。
8. **下载按钮可见性**：仅 `download_enabled` 为 true 且有选集时显示；开关关闭后详情页按钮消失（返回详情页时刷新）。

## 未做 / 不确定（待汇报）

- 真机未测：无 SDK，无法编译，首次编译检查靠推上 GitHub 后的构建。
- m3u8 加密（EXT-X-KEY）按“下载 key 并改写 URI”实现，未真机验证能否播。
- 部分站点的解析地址可能仍需二次 parse 或带时效 token：解析失败 / 地址失效时任务失败并明示，不做自动重新解析。
- 无通知栏进度、无后台 Service：切后台下载继续跑（进程内线程），被系统杀掉后下次启动续传；下载完成无系统通知。
- DASH（.mpd）不支持；直播流不支持。

## 提交计划

- A：偏好 + 关于 5 连点 + 字符串 + 本文件。
- B：下载引擎（VideoDownloadManager / DownloadTask / DownloadStore / DownloadPathUtil，download 包）。
- C：设置页隐藏分组 + 下载开关 + 下载列表 UI。
- D：详情页下载按钮 + 选集下载弹窗 + 入队接线。
