# 海阔小程序第 7 步之十：预热 / 缓存完整策略（最后一步）

- 任务：miniapp-warmup（standard lane；10 步串行工程的第 10 步，最后一步）
- HEAD：64a330b6（main，本地超前 origin 九笔，未推）；工作区干净后开工
- 完成句：导入完成与 App 启动后，后台线程静默预热前 5 条规则进内存 LRU 卡片缓存（128/600）；列表页命中缓存秒开、下拉刷新强制重取、规则文件变了或删了对应缓存失效；只改手机端，本地提交，不推、不编、不触发构建、不动版本记录空间。
- 时间：2026-11-15 19:21 CST 开工；估计 1–1.5 小时，预期 20:30 CST 前完成并提交。
- 最便宜的决定性验证：逐行重读改动 + `git diff --check`（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。

## Recovery anchor

- 目标与验收：见“验收标准”一节；真机验不了的项已在验收表里标明。
- 计划状态：任务文档已写；实现：新 `HkCache.java`（页级 LRU）+ 新 `HkWarmup.java`
  （后台预热），改 `HkListFragment.java`（缓存查/填）、`HkMiniappActivity.kt`
  （导入完成触发预热、删除失效）、mobile `HomeActivity.java`（启动触发预热）。
- 已完成证据：逐行重读完成（复核修见 §6）；`git diff --check` 通过；改动只在
  guard scope 的 6 个文件内；TV/leanback、`BaseLoader`、`menu_nav.xml` 未动。
- 未验证的改动：全部（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；
  真机验收等用户）。
- 未解决风险：预热与缓存命中只能真机对；Rhino 单条规则执行时间没有硬性中断手段，
  超时靠 OkHttp 30s + 预热总时限 120s（见“超时”一节）。
- 下一步：`bash .codex/scripts/task_guard.sh finish --verified <证据> --commit-message <消息>`，
  随后 `git tag -a` 建 recovery tag。

## 1. 证据表

| 证据 | 位置 | 结论 |
|---|---|---|
| `CACHE` 定义 | 逆向报告 `~/workspace/user/files/__________8_3azg.md` §7.1 L389–390 | `Collections.synchronizedMap(new jo(128, 0.75f, true, 4))` + `CACHE_MAX = 600`；`jo` 是 accessOrder 的 LinkedHashMap 子类（128 初始容量、0.75 负载、按访问排序），淘汰看 `CACHE_MAX` |
| `CACHE` 的用途（上游） | 同上 §7.1 L722、L1154（`toVods` 与搜索路径的 `CACHE.put(卡片 url, 卡片)`） | 上游 CACHE 是**按卡片 URL 索引的单卡片缓存**，供详情页按 URL 取卡片；键不是“规则+分类+页码” |
| `warmUp()` 本体 | 同上 §7.1 L739–743 | 空表直接返回；否则起一个名叫 `hk-warmup` 的后台线程跑 `lambda$warmUp$0` |
| `lambda$warmUp$0` | 同上 §7.1 L456–471 | 前 5 条（`min(size, 5)`）**串行**跑：`f22.p(标题).k(1, "")`（首页第 1 页默认分类）；0 条且规则有分类时再跑一次 `k(1, 首分类 id)`；每条 try/catch，只记 `SpiderDebug.log`，不抛、不弹 |
| 导入完成调 `warmUp()` 的两个点 | 同上 §7.1 L890（`importFiles`：有新增站源 **且规则总数 ≤ 20** 时调）、L984（`importCloudCodes`：有新增站源就调，无 ≤20 门槛） | 门槛只在文件导入链路上有；云口令链路没有 |
| 导入时 ≤20 的门槛 | 同上 §2.2 L159（“规则数 ≤ 20 时 `warmUp()`”） | 与 L890 一致 |
| `i22` 目录结构 | 报告 §7.11 + `docs/miniapp-foundation.md` | 规则 `plugins/hk/<标题>.json`；配置 `plugins/hk/config/`；数据 `plugins/hk/data/<标题>/`（第 3 步已建：`myvar.json`/`items.json`/数据文件） |
| 导入后预热是第 2 步留待的缺口 | `docs/miniapp-import.md`“导入链路”表（`importFiles … ≤20 条时本来要 warmUp 预热——第 3 步才有列表页，暂不做”）+ `docs/miniapp-list.md`“不做的”一节 | 本步补的就是这个缺口；列表页（第 3 步）已出，预热才有可填的缓存 |
| 首页取数链路 | `docs/miniapp-list.md` + `HkExecutor.homeDetailed` | 预热 = 后台先跑 `HkExecutor.home(rule, 1, null)`（含第 3 步的子页面/require 展开），结果经 `pickCards`/`toVods` 成 Vod 再进缓存；与列表页同一条链路，不另写取数 |
| 子页面/require 已能跑 | `docs/miniapp-subpage.md` | 预热对子页面规则（西瓜影院、阅动漫）不需要特殊分支，走同一条 `home()`；失败静默记日志 |
| 网络超时 | `catvod/.../net/OkHttp.java` L42、L243 | 共享客户端 `TIMEOUT = 30s`（connect/read/write 全 30s）；`HkExecutor.fetch` 用 `OkHttp.client()`，即每请求最多 30s |
| 规则文件 mtime | `HkStore.lastModified(title)`（第 1 步已有，对应 `i22.M()`） | 缓存失效判断用它：取数时记下 mtime，命中时比对，变了即失效 |
| 列表页加载结构 | `HkListFragment.loadPage`（第 3 步） | 后台线程取数 + `mLoadSeq` 防串位 + `App.post` 回主线程；下拉刷新 `onRefresh()` → `loadPage(1, false)`；分类切换 → 同样 `loadPage(1, false)`；翻页 `loadPage(next, true)` |
| 导入完成回调 | `HkMiniappActivity.onImported(added)` + 云口令 partial 分支 | 三路导入（文件/目录、粘贴 JSON、云口令）最终都汇到这两个回调；同步（`runSync`）不走导入回调 |
| 启动入口 | mobile `HomeActivity.onCreate`（`app/src/mobile/.../ui/activity/HomeActivity.java`） | 桌面启动的 Activity 在 mobile 源集，可直接引用 `api/hk` 的类；main 源集的 `App` 不能引用 mobile 类，所以启动预热只能挂在这里，不能挂 `App.onCreate` |
| 底栏上限 | `menu_nav.xml` + `mooyingshi_upkeep` skill | 6 项上限，本步不加项、不碰 |

## 2. 方案比较

### 2.1 缓存形态：页级缓存 vs 照搬上游的单卡片缓存 vs 不做缓存只做预热

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 只预热、不缓存：预热跑完就扔结果 | 零新增状态 | 列表页每次打开仍要重新跑 `home()`（网络 + Rhino），预热等于白跑，用户感知不到秒开；任务验收“命中缓存秒开”做不到 | 否决 |
| B. 照搬上游（单卡片 CACHE） | `Map<卡片 url, HkCard>`，`toVods` 里每张卡片 `put`，详情页按 url 取卡片 | 与魔影逐行一致 | 本地详情页不按 url 查卡片：`HkDetailActivity.open(..., url, name, pic, desc)` 是把卡片字段直接放 intent 传过去的（第 4 步已定），单卡片缓存没有消费者；列表页“打开秒开”仍做不到——打开列表页要的是一整页卡片，单卡片缓存帮不上忙 | 否决（只保留 128/600 的容量数字与 LRU 语义，不保留键形态） |
| C. 窄化适配：页级 LRU 缓存（推荐） | 新 `HkCache`：键 = `标题 + \n + 分类 + \n + 页码`，值 = 一页 Vod 列表 + 取数时规则文件 mtime；列表页取数前先查、命中直接渲染；预热把第 1 页默认分类的 Vod 写进去 | 键直接对应列表页的取数参数，命中即整页秒开；失效条件天然对应（规则文件 mtime 变了 → 失效）；容量仍按报告的 128/600 LRU 落地；与上游的偏差只有键形态，且有明确原因（本地详情不查缓存） | 与上游 CACHE 的键不同，算“窄化”不是“照搬”，文档里写明 | **采用** |

### 2.2 预热触发点：只在导入后 vs 导入后 + 启动时

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 只在导入后 | `HkMiniappActivity.onImported` 里调一次 | 改动最小；与报告的两个调用点（`importFiles`/`importCloudCodes`）逐一对应 | 用户每天第一次打开 App、点进已装好的规则时，仍要等一次完整取数；报告的 `warmUp` 只覆盖导入时刻，启动后冷缓存是已知缺口 | 否决（任务明确要求“App 启动后有已装规则时”也预热） |
| B. 导入后 + 启动时（推荐） | 导入完成（`onImported` 成功分支 + 云口令 partial 分支）调 `warmUpAfterImport()`；mobile `HomeActivity.onCreate` 调 `warmUpOnStartup()`（进程内只跑一次） | 两次冷启动场景都覆盖；启动触发点在 mobile 源集，不碰 main 源集的 `App`，TV 不受影响（leanback 有自己的 HomeActivity，不调） | 多一个触发点，但两个入口都只是“起后台线程”，前台零感知 | **采用** |

### 2.3 预热并发：5 条全并行 vs 单线程串行 vs 线程池限 2

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 5 条全并行 | 5 个线程同时跑 `home()` | 总耗时最短 | 5 个 Rhino 引擎 + 5 路网络同时占 CPU/流量，低端机上与前台抢资源；任务明确说“不要一次把 5 条规则全并行打满” | 否决 |
| B. 单线程串行（推荐） | 一个名叫 `hk-warmup` 的后台线程，按顺序跑 5 条 | 与上游 `lambda$warmUp$0` 逐字一致（上游也是一个线程里顺序循环）；资源占用最小；失败隔离简单（一条抛错不影响下一条） | 5 条都慢时总耗时最长（上限见“超时”：单条网络 30s × 5 + 总时限 120s 截断） | **采用** |
| C. 线程池限 2 | 2 并发 | 折中 | 比 B 复杂（要管池的生命周期），收益只是预热快几十秒——预热是后台静默的，用户不感知快慢 | 否决 |

### 2.4 缓存失效：mtime 比对 vs 显式失效 vs TTL

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 只显式失效 | 导入/删除时调 `invalidate(title)` | 简单 | 规则文件被外部改了（用户手动替换文件、同步重登记）时缓存还留着旧页；列表页无法自愈 | 否决（作辅助手段保留，不作唯一手段） |
| B. mtime 比对（推荐主手段） | `put` 时记 `HkStore.lastModified(title)`；`get` 时重取 mtime，不等即失效 | 任何写盘（重导入、覆盖、手动替换）都自动失效，不依赖调用方记得调失效；`lastModified` 是便宜的 stat 调用，主线程查缓存也无负担 | 文件系统 mtime 粒度是毫秒，同一毫秒内两次写盘理论上看不出变化——实际导入是用户操作间隔，不构成问题；文件被删时 `lastModified` 回 -1，与任何已存 mtime 都不等，天然失效 | **采用** |
| C. TTL（如 10 分钟过期） | 记 `cachedAt`，超期失效 | 能处理“站点内容变了但规则文件没变”的情况 | 站点内容本来就一直在变，TTL 只是把“多旧算旧”换成一个拍脑袋的数字；下拉刷新已提供强制重取的明确入口，TTL 是多余的一层 | 否决（不设 TTL；`cachedAt` 只记下来供日志/排查，不参与失效判断） |
| D. 组合（采用） | B 为主 + A 为辅（删除时 `invalidate(title)` 立即清掉，不等下次 `get`）+ 下拉刷新强制重取（`force` 绕过 `get`，成功后覆盖写回） | 三种失效场景全覆盖；删除后不留幽灵缓存 | — | **采用** |

## 3. 推荐

采用 C（页级 LRU）+ B（导入后 + 启动时）+ B（单线程串行）+ D（mtime 主 + 显式辅 + 强制刷新）。

- **新 `HkCache.java`**（`api/hk`，mobile 源集）：
  - `CACHE`：`Collections.synchronizedMap(new LinkedHashMap<String, Entry>(128, 0.75f, true) { removeEldestEntry → size() > 600 })`——数字与报告逐字一致（128 初始容量、0.75 负载、accessOrder、600 上限）。
  - 键：`title + "\n" + classId(空→"") + "\n" + page`（classId 是分类名，`loadPage` 传什么就用什么；单分类规则 `mClassId` 可能是唯一分类名或 null，统一归一化）。
  - 值 `Entry`：`List<Vod> vods`（put/get 都做防御性拷贝，`new ArrayList<>(vods)`——`VodAdapter` 拿到的列表不能反向污染缓存）、`long ruleModified`、`long cachedAt`（只记不判）。
  - `get(title, classId, page)`：无条目 → null；`HkStore.lastModified(title)` 与 `ruleModified` 不等 → 删条目、回 null（规则文件变了/被删了都走这条）。
  - `put(title, classId, page, vods, ruleModified)`：空列表不存（0 条可能是站点临时失败，存了会把空态粘住；下次打开重取才是正确行为）；非空才存。
  - `invalidate(title)`：删掉该标题前缀的全部键（导入覆盖、删除时调；删除是立即清，不等下次 `get`）。
  - 缓存里只存卡片（标题/海报/描述/链接/类型名），不存规则原文、不存请求头、不存 cookie、不存任何凭证；Vod 上没有这些字段，结构上就放不进去。
- **新 `HkWarmup.java`**（`api/hk`，mobile 源集）：
  - `warmUpAfterImport()`：只起线程、不做 I/O（调用方在主线程）；线程里 `HkStore.list()`，空表直接返回；**总数 > 20 时跳过**（报告 `importFiles` 链路的门槛，逐字保留）；否则预热前 5 条。
  - `warmUpOnStartup()`：进程内只跑一次（`AtomicBoolean` `compareAndSet`）；线程里 `HkStore.list()`，空表直接返回（没装规则 → 不跑，零回归）；**不设 ≤20 门槛**——启动时用户已有规则是常态，门槛会让装了 20 条以上规则的用户永远冷启动；预热的仍是前 5 条。门槛差异已记录，见“与上游的偏差”。
  - `warmUp(List<HkRule> rules)`（共用实现，包内可见）：单线程顺序跑前 5 条，每条 `HkExecutor.home(rule, 1, 首分类名)`（分类名取 `rule.classNames()[0]`，无分类为 null——与 `HkListFragment.setClasses()` 定 `mClassId` 的口径一致，这样预热写入的缓存键与列表页打开时查的键是同一个）→ `pickCards` → `toVods` → `HkCache.put`（成功且非空才进缓存；0 条只记日志，不进缓存）；每条独立 try/catch，失败只 `Log.w`（规则名 + 原因），不弹提示、不阻断下一条、不抛到调用方。
  - 并发控制：一个 `AtomicBoolean running`，已有预热在跑时新的请求直接丢弃（导入与启动几乎同时触发时不另起线程；两个入口预热的都是同一份前 5 条，丢了不丢结果）。
  - 超时：单条规则的网络由 `OkHttp.client()` 的 30s connect/read/write 兜底（`HkExecutor.fetch` 用共享客户端）；另设**总时限 120s**：从预热开始计时，跑下一条前若已超 120s 就停（不再起新规则）。Rhino 单次执行本身没有安全的硬中断手段（`Thread.interrupt` 停不掉正在跑的 JS），所以总时限只约束“不再起新规则”，不谎称能中断正在跑的那条；最坏情况是最后一条在 120s 后仍由 OkHttp 30s 兜底结束，后台线程自然退出，不阻塞前台、不阻塞退出。
  - 子页面/require 规则：不特殊处理。预热跑的就是列表页同一条 `home()`（第 3 步已让子页面/require 在这条路上能跑），西瓜影院（`hiker://empty` + pages）与阅动漫（远程拉规则列表）都按普通规则预热，失败静默记日志。
  - 线程名 `hk-warmup`（与报告一致），普通优先级，不设为守护线程以外的特殊属性。
- **`HkListFragment.java`**：
  - `loadPage(int, boolean)` 加重载 `loadPage(int, boolean, boolean force)`；原两参版调 `force=false`。调用点：`onRefresh()`（下拉刷新）传 `force=true`，其余（首屏、分类切换、翻页、`reloadPage()`、重试）传 `false`。
  - 缓存查：`!force` 时，在起后台线程前先 `HkCache.get(title, mClassId, page)`；命中 → `mLoadSeq` 自增后直接 `App.post` 回主线程走现成的 `onPageLoaded(page, append, cached, "", null)`（不显示 loading、不发请求；`!append` 时先 `mScroller.reset()` + 隐藏诊断行，与网络路径一致）；未命中 → 走原网络路径，成功后 `HkCache.put(title, classId, page, vods, HkStore.lastModified(title))`。
  - 强制刷新：`force=true` 跳过 `get`，成功后照常 `put`（覆盖同一键，等价于刷新缓存）。
  - 翻页（`append=true`）同样可查可填：键里有页码，第 2 页及以后访问过也会进缓存；预热只填第 1 页默认分类。
  - 搜索页不接缓存：搜索是关键词相关的，结果不可按“规则+分类+页码”复用；上游 CACHE 也不覆盖搜索列表（它只在搜索路径里按卡片 url 存单卡片，供详情用，本地详情不查它）。
- **`HkMiniappActivity.kt`**：
  - `onImported(added)`：`added > 0` 分支里 `reloadLocalConfig()` 后调 `HkWarmup.warmUpAfterImport()`。
  - 云口令 partial 分支（`added > 0` 但有错误）：同样调 `warmUpAfterImport()`（部分成功也是“导入完成”）。
  - `onDeleteClick`：`HkStore.delete(title)` 后调 `HkCache.invalidate(title)`。
  - 同步（`runSync`）不触发预热：它只是重登记站源条目，规则文件没变，缓存的 mtime 判断依然有效；报告里 `syncSites` 也不调 `warmUp`。
- **mobile `HomeActivity.java`**：`onCreate` 里 `super.onCreate` 后调 `HkWarmup.warmUpOnStartup()`（只起线程，`list()` 在线程里做，不阻塞启动）。leanback 的 HomeActivity 不动。

### 与上游的偏差（已记录）

1. 缓存键形态：上游是单卡片 `url → 卡片`（详情查用），本地是整页 `标题+分类+页码 → Vod 列表`（列表页秒开用）。原因：本地详情页不按 url 查卡片（第 4 步已定：卡片字段走 intent），单卡片缓存没有消费者；页级缓存直接服务验收标准。容量数字 128/600 与 LRU 语义逐字保留。
2. 上游 `lambda$warmUp$0` 里“首页 0 条时再跑一次首分类”：本地预热直接用首分类名调 `home(rule, 1, 首分类名)`，与 `home(rule, 1, null)` 的默认行为是同一条 URL，不再重复跑。
3. 启动预热没有 ≤20 门槛（导入链路保留）：理由见上；启动时规则数多是常态，门槛会让重度用户永远冷启动。
4. 磁盘不另建页缓存：`plugins/hk/data/<标题>/` 仍只归规则自己用（第 3 步的 `myvar.json`/`items.json`/读到的文件内容），预热/缓存代码绝不读写它；内存页缓存与磁盘规则数据是两套东西，键空间不重叠、失效条件不互相触发。规则原文与配置（`plugins/hk/*.json`、`config/`）也不进缓存。

### 缓存目录与内存缓存分工（写清）

| 层 | 位置 | 存什么 | 谁写 | 失效 |
|---|---|---|---|---|
| 内存页缓存 | `HkCache.CACHE`（进程内，600 条上限 LRU） | 一页 Vod 列表（标题/海报/描述/链接/类型名）+ 取数时规则 mtime | 列表页取数成功后、`HkWarmup` 预热成功后 | 规则文件 mtime 变了 / 删除时 `invalidate(title)` / LRU 满了淘汰 / 进程退出即消失 |
| 磁盘规则数据 | `plugins/hk/data/<标题>/`（第 3 步已建） | 规则的持久变量（`myvar.json`）、`getItem/setItem`（`items.json`）、`readFile/writeFile` 的文件内容 | 规则 JS 执行（`HkEngine` 经 `HkStore`） | 规则自己写、用户删规则文件时连目录一起走（现有删除路径只删规则文件，data 目录残留是第 3 步已知行为，本步不动） |
| 磁盘规则本体/配置 | `plugins/hk/*.json`、`plugins/hk/config/` | 规则原文、require 清单 | 导入（`HkStore.save/saveConfig`） | 导入覆盖、删除 |

两套缓存不打架：内存缓存只存“取数结果的派生数据”，键里带规则 mtime，规则一变就失效；磁盘数据是“规则运行时的状态”，缓存代码不读写、不删除、不参与失效判断。缓存里不存凭证：Vod 没有凭证字段；规则原文里的 `firstHeader`/ua 不进缓存。

## 4. 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. `HkCache` 的 map 是 `LinkedHashMap(128, 0.75f, true)` + `removeEldestEntry → size() > 600`，`Collections.synchronizedMap` 包裹；`get` 有 mtime 比对、`put` 拒空列表、`invalidate(title)` 按标题前缀清。
2. `HkWarmup`：单线程串行、每条独立 try/catch、只记 `Log`、不弹提示、不抛到调用方；`warmUpAfterImport` 有 ≤20 门槛、`warmUpOnStartup` 进程内一次、无规则时不跑、已有预热在跑时新请求丢弃；总时限 120s（跑下一条前判断）。
3. `HkListFragment`：`onRefresh` 强制刷新绕过缓存；其余路径命中缓存直接走 `onPageLoaded`；成功后 `put`；`force` 成功后覆盖同一键。
4. `HkMiniappActivity`：三路导入都汇到 `onImported`/partial 分支调预热；删除调 `invalidate`；同步不调预热。
5. mobile `HomeActivity.onCreate` 调 `warmUpOnStartup`；leanback HomeActivity、`BaseLoader`、`menu_nav.xml`（仍 6 项）未动；无 `Json.safeListElement` 式返回类型误用、无泛型原始类型；新文件全在 mobile 源集。
6. 缓存值只有 Vod 字段（无规则原文、无请求头、无 cookie），`HkCache` 不读写 `plugins/hk/data/`。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入）：

7. 导入 3 条验收规则后，后台预热跑完（`adb logcat` 搜 `hk-warmup`，应有 3 条 `items=N`，无失败刷屏、无 toast、无卡顿）。
8. 再点进任意一条规则的列表页，命中缓存秒开（首屏不等网络）；切分类后切回来，缓存命中同样秒开。
9. 下拉刷新：强制重取（log 里应出现一次新的网络取数，内容随站点更新而更新，不被旧缓存粘住）。
10. 缓存满了按 LRU 淘汰、不无限涨内存：连续打开多条规则多分类多页后进程内存不持续爬升（只能真机粗看，600 条上限是代码层面的硬 bound）。
11. 规则删了对应缓存失效：管理页删一条规则后，实验室/本地源里残留条目点开不显示旧卡片（应报规则不存在）；重导入同一标题的规则，列表页取到的是新规则的内容（mtime 失效）。
12. 预热失败静默：断网（或把规则 url 改成不可达）后导入/启动，App 不弹任何提示、不卡启动、列表页仍可正常打开（走网络路径报自己的错）。
13. 三条验收规则列表/详情/搜索/播放零回归（第 3–6 步已验过的链路，本步只在取数前后加了缓存查/填，不改取数本身）。
14. 不导入小程序时预热不跑、零回归：全新安装（无规则）启动，log 里无 `hk-warmup` 活动；首页、本地源、实验室、底栏开关都不受影响。

## 5. 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（`HkCache`/`HkWarmup`/任务文档）随 revert 一起消失；`HkListFragment`、
  `HkMiniappActivity`、`HomeActivity` 的三处接线恢复原样；回滚后列表页回到
  “每次打开都走网络取数”（第 3 步的行为），预热不再跑，无残留状态（缓存是纯内存，
  进程退出即消失；磁盘 `plugins/hk/data/` 本步没碰过）。
- 局部回滚：只停预热、保留缓存——删 `HkWarmup` 的两处调用（`HkMiniappActivity`
  两处 + `HomeActivity` 一处），`HkCache` 与列表页查/填继续工作（列表页自己访问过的页
  仍会进缓存）；只停缓存、保留预热——删 `HkListFragment` 里的查/填，预热跑完的
  结果无人消费，等价于不预热。两处互相独立。

## 6. 复核修（提交前逐行重读发现，一处小错直接修一轮）

- 预热缓存键口径错：`HkWarmup` 初版用 `classId=null` 写缓存，但列表页
  `mClassId` 是首分类名（`setClasses` 定的），两边键对不上、预热永远命中不了。
  已改成预热时取 `rule.classNames()[0]`（无分类为 null），`home()` 与 `put()`
  都用它，与列表页同一口径；任务文档 §3 的 `warmUp` 一节与“与上游的偏差”
  第 2 条同步改掉。
- `HkWarmup.warmUp` 的 javadoc 还写 `home(rule, 1, null)`，与改后的代码
  （首分类名）不一致，已改成“首分类名”。

## 7. 已知缺口与遗留（本步不做）

- 预热只跑第 1 页默认分类：用户常看的第 2 分类、第 2 页不预热，第一次点仍要等网络取数；页级缓存会把访问过的页留下来，第二次起秒开。
- 单条规则的 Rhino 执行没有硬中断：总时限 120s 只约束“不再起新规则”，正在跑的那条靠 OkHttp 30s 兜底网络部分；一条纯 JS 死循环规则理论上会拖住预热线程——预热线程是后台的，不影响前台；列表页自己的取数线程不受预热线程影响。
- 缓存不跨进程：杀掉 App 重进，缓存全空，靠启动预热重新填；磁盘不存页缓存（刻意，见 §3 分工表）。
- 缓存没有“站点内容变了”的主动感知：规则文件没变时，列表页缓存会一直命中，直到用户下拉刷新或切走再回来时……注意：切分类回来命中缓存是旧内容，站点新内容要下拉刷新才看得到——与首页普通站源“每次进都重取”不同，这是页缓存的固有取舍，验收时与用户说清楚。
- `HkStore.list()` 的顺序是目录枚举顺序（不排序），“前 5 条”是这个顺序里的前 5 条，不是按使用频率或导入时间排的；以后要做“最常用优先”再改排序。
- 阅动漫这类框架型规则：预热跑 `home()` 时会远程拉它的规则列表（`https://14719.kstore.space/阅动漫.json`），在线与否、耗时都在真机上看；失败静默，不影响其它规则。
