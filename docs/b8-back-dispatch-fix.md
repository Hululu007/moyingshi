# B8 返回分发修复 — 让 FlutterFragment 处理 predictive back

- 构建：#167（头 `7d2b520c`）含 §1 返回栈修复（`ba8ef568`），但 2026-11-19 08:37 真机验收：返回还是坏（实验室/设置/⋮ 菜单返回都到桌面；首页返回是 finish 而非后台），其余三项（滑动/玻璃/设置搜索）都好了。
- 诊断：`docs/b8-back-167-diagnosis.md`（同批提交），录像 `~/workspace/user/media_library/video/a3/a33e841ffed7aa7b0e79af818dbcd92a4829e3dc33d7154f45b458ca71d3535e.mp4` 逐帧核对。
- 任务：`b8-back-dispatch`，quick-fix；分支 `b8-flutter-rewrite`，父提交 `7d2b520c`。
- 本地无 Java/Android/Flutter SDK，编译验证由 CI（`b8-c0.yml`）决定。

## 1. 根因（诊断文档 §2，父级已逐项核对）

返回按压根本到不了 Dart / host，§1 修的 host 历史在真机上一次都没跑到。断点在 Android 侧的返回分发，不在返回栈逻辑里：

1. `app/src/main/AndroidManifest.xml:36` 开了 predictive back（`android:enableOnBackInvokedCallback="true"`），SDK 36 的手机上返回手势走平台 `OnBackInvokedDispatcher`，不再走 legacy `onBackPressed()`。
2. `C1ScreenActivity` 全文件无 `OnBackPressedCallback`、无 `onBackInvoked()` 重写（仅 `:866` 注释提到原生 `HomeActivity.onBackInvoked`）。
3. `C1ScreenActivity.java:436` 的 FlutterFragment 构造没有 `.shouldAutomaticallyHandleOnBackPressed(true)`（默认关闭）。
4. C1 唯一的返回接线是 legacy `onBackPressed()` 重写（`:1052-1063`），predictive back 开启后系统返回不再经过它：手势 → dispatcher → 无 enabled 回调 → 平台默认 `finish()`。

§1 的 host 历史（`sRouteHistory` / `handleRootBack`）、`recordPush` / `recordSwitch`、Dart 各页 `PopScope` + `closeScreen` 委托，诊断时逐行核对全部正确，无需改动。原生直播返回正常（原生 Activity 的 `onBackInvoked`）反证断点只在 Flutter 宿主这一层。

## 2. 修复（F1，`C1ScreenActivity.java` 一个文件，两处）

```diff
-            flutterFragment = FlutterFragment.withCachedEngine(ENGINE_ID).build();
+            flutterFragment = FlutterFragment.withCachedEngine(ENGINE_ID)
+                    .shouldAutomaticallyHandleOnBackPressed(true)
+                    .build();
```

```diff
-    @Override
-    public void onBackPressed() {
-        if (flutterFragment != null) {
-            flutterFragment.onBackPressed();
-        } else {
-            // B8 back stack: no fragment, no Dart to consult — the
-            // host root-back handler owns the fallback (pop or
-            // background), never a bare finish.
-            handleRootBack();
-        }
-    }
-
```

另顺带修一处因删除重写而失效的注释：`handleRootBack()` 的 Javadoc 原写“`{@link #onBackPressed()}` fallback delegate”，改为说明 fragment 自动返回处理先把按压转给 Dart。无其他代码改动。

- 未用 F2 替代（手动 `OnBackPressedCallback`）：`shouldAutomaticallyHandleOnBackPressed` 在 `withCachedEngine` 构造链上可直接调用，无需回退。
- 未动：manifest 的 `enableOnBackInvokedCallback="true"`（F3 明确不做）、host 历史、Dart `PopScope` / `closeScreen`、Pigeon 契约。

## 3. 为什么删掉的 else 分支（fragment 为 null 时调 `handleRootBack()`）没有损失

fragment 在 `onCreate` 里创建并加入 `FragmentManager`；返回按压只能发生在 `onCreate` 完成、界面可见之后，此时 `flutterFragment` 恒非 null。`flutterFragment == null` 的窗口只存在于 `onCreate` 完成前——该窗口内 dispatcher 上没有任何已注册回调，系统走默认行为，与删除前的行为一致（删除前 legacy 重写在该窗口同样到不了，因为 predictive back 不走它）。因此删除重写不丢任何可达的 fallback。

## 4. 验证证据

- `git diff`：`C1ScreenActivity.java` 仅 +3/−13（builder 一行改三行 + 删 12 行重写 + Javadoc 措辞调整），`git diff --check` 干净。
- 改后 grep：`shouldAutomaticallyHandleOnBackPressed(true)` 在 `:437`；文件内再无 `onBackPressed()` 方法重写（仅剩 `:868` 注释提及原生 `HomeActivity.onBackInvoked`）；`handleRootBack()` 仍在 `:877`，供 C1/B3 的 `closeScreen` 委托调用。
- 未验证：本机无 SDK，`shouldAutomaticallyHandleOnBackPressed` 与 `withCachedEngine` 在 Flutter 3.50 上的组合由 CI 编译覆盖；若编译期报错，改走诊断文档 §5 的 F2（手动 `OnBackPressedCallback`）。
- 真机验收：诊断文档 §6 的 V1–V7（关键判据 V4：首页返回后立即重进应瞬间恢复、无加载圈，证明是 `moveToBackground` 而非 `finish`）。

## Recovery anchor

- 目标：Flutter 各屏上的返回按压到达 Dart/host 处理，而非平台默认 `finish()`。
- 状态：修复已提交（见提交记录），待 CI 构建 + 真机验收 V1–V7。
- 下一步：父级推送后按诊断文档 §6 验收；若 CI 编译报错，改走 F2。
