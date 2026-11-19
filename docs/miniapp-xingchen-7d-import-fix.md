# 移植阶段 7d：HkEngineManager 错误 import 修复（协调人复核发现）

> Recovery anchor（任务：miniapp-xingchen-7d-import-fix；lane：quick-fix；分支 main；HEAD 90c32c8d）
> 目标：修复 3 个 Fragment 对 HkEngineManager 的错误 import（api.hk → ui.hk），否则 javac 必失败。
> 状态：已修复并提交，见下。

## 1. 发现过程

阶段 7 完成后，协调人做全树 import 解析核对（所有 hk import 逐条对类文件）：
`HkEngineManager` 的唯一定义在 `ui/hk/HkEngineManager.java`（package
`com.github.moyingshi.osc.tv.ui.hk`，星辰同款位置
`app/src/mobile/java/com/fongmi/android/tv/ui/hk/HkEngineManager.java`），
但三处 import 写成了 `com.github.moyingshi.osc.tv.api.hk.HkEngineManager`：

| 文件 | 错误行 |
|---|---|
| `ui/hk/HkListFragment.java` | :24 |
| `ui/hk/HkDetailFragment.java` | :25 |
| `ui/hk/HkSearchFragment.java` | :22 |

`HkRouter.java:9` import 的是正确的 `ui.hk` 版，说明 5c 机械替换时把旧
`import ...api.hk.HkExecutor` 一行改写错了包（`git log -S` 定位：引入于
5c `70f48233`，5d `f2d5aa3d` 同款写法）。其余核对：全树 package 声明与目录
零不匹配；非 import 的全限定 hk 引用零坏引用；本修复是唯一缺口。

## 2. 修复

三行 import 一字改包名，其余零改动：

```diff
-import com.github.moyingshi.osc.tv.api.hk.HkEngineManager;
+import com.github.moyingshi.osc.tv.ui.hk.HkEngineManager;
```

## 3. 验收证据

- 修复后重跑 import 解析脚本：hk import 引用 35 条，缺失 0 条。
- `git diff --check` 通过。
- 未编译：本地无 JDK/Android SDK，编译靠推上去后的 GitHub 构建验（任务书已明示）。
- 本笔不改任何行为，只修 import；不推、不编。

## 4. 遗留

无。本笔之后，全树 hk import 解析零缺失；是否可编仍以 GitHub 构建为准。
