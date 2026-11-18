# CI #153：WebHomeShellActivity `isImmersive` 访问权限冲突

## Recovery anchor

- 目标：修复 CI run #153 在 Java 编译步骤的失败，`WebHomeShellActivity` 的私有方法与 `Activity` 公共方法同名导致访问权限冲突。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `0e44123a`（#151 OkHttp 导入修复已确认有效，run #153 的步骤 1–14 全部通过，含 Dart analyze）。
- 文件：`app/src/main/java/com/github/moyingshi/osc/tv/ui/activity/WebHomeShellActivity.java`、本文档。
- 状态：已修复并本地提交，待推送后由 CI 验证。
- 下一步：推送后等 CI run 结果；若 Java 编译通过，继续按删除原生计划推进。

## 原因

`WebHomeShellActivity.java:361`（shell 提交 `5854a6c5` 引入）声明了：

```java
private boolean isImmersive() {
    return WebHomeChrome.IMMERSIVE.equals(mode);
}
```

其超类链 `BaseActivity → AppCompatActivity → Activity` 已有公共方法
`Activity.isImmersive()`（自 API 19，返回该 Activity 当前是否处于沉浸模式）。
私有方法以更弱的访问权限“覆盖”公共方法，javac 报错：

```
isImmersive() in WebHomeShellActivity cannot override isImmersive() in Activity
```

## 为什么现在才暴露

这不是新引入的回归条件，而是编译步骤首次到达该检查点：

- run #151 在 Java 编译前就因错误的 OkHttp 导入失败；
- run #153 修复 OkHttp 后，步骤 1–14 全部通过（含 Dart analyze），第一次走到 Java 编译，才撞上这个自 shell 提交 `5854a6c5` 就存在的问题。

## 修复

重命名为 `isImmersiveMode()`，并同步更新全部 5 处内部调用点（行号为修复前）：

| 位置 | 用途 |
| --- | --- |
| 173 | `WebHomeViewport.EMPTY.withChrome(mode, isImmersiveMode())` |
| 287 | 获得焦点时隐藏系统 UI |
| 306 | 退出沉浸时显示系统 UI |
| 324 | chrome 返回键消费判断 |
| 337 | 隐藏系统 UI |
| 361 | 方法声明本身 |

行为完全不变：仍是 `WebHomeChrome.IMMERSIVE.equals(mode)` 的模式标志判断。

## 为什么不用 public 覆盖

把方法改成 `public boolean isImmersive()` 也能通过编译，但会以不同语义覆盖
`Activity.isImmersive()`：框架方法表示 Activity 当前实际沉浸状态，本方法是
“当前 chrome 模式是否为沉浸模式”的配置标志。两者同名不同义，任何按框架语义
调用 `isImmersive()` 的代码（例如插件面的 `ImmersiveAware` 检查）都会拿到错误答案，
且极易在后续维护中被误读。重命名彻底消除碰撞，零语义风险。

## 排查

- 仓库范围内搜索 `isImmersive`：除本文件外无任何调用点引用 shell 的方法；
  其余命中均为 `PlayerSetting.isImmersiveAudioMode()`（沉浸音频，与本方法无关）、
  `VideoActivity`/`TmdbDetailActivity` 各自的 `public boolean isImmersive()`（正确覆盖
  `Activity.isImmersive()`，符合 `ImmersiveAware` 契约，见 `PluginSurface.java:87`），
  以及相关测试文件对后两者的源码断言。
- 新 B8 文件弱访问权限覆盖排查：对 `WebHomeShellActivity`、`C1ScreenActivity`、
  `B11FabHostFragment`、`FlutterNav` 的全部私有方法名与 Activity 生命周期/API 方法名
  （`onCreate`/`onResume`/`onDestroy`/`onNewIntent`/`onBackPressed`/`finish` 等）
  逐一比对，唯一碰撞就是本文件的 `isImmersive`，已修复；无其他同类隐患。

## 验证

- `grep -n 'isImmersive' WebHomeShellActivity.java`：6 处均为 `isImmersiveMode()`，
  文件内不再存在裸 `isImmersive()`。
- 本机无 Java/Android SDK，无法本地编译；Java 编译由 CI（run #153 对应头提交的后续运行）验证。
- 影响面：仅 shell 内部私有方法重命名，无 Pigeon/Dart/manifest/其他 Java 文件改动。

## 提交

- `fix(b8): run #153 Java compile failure (isImmersive access clash)`
