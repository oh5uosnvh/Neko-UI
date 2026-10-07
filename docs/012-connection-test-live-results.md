# 012：连接测试实时回显 + 取消/测完零空档

日期：2026-10-07。基线：A01 + 补丁 001-011。改动仅 `ui/ConfigurationFragment.kt`。

## 根因（源码实证，文件行号为构建源码）

- **测试门**：`DataStore.runningTest`（`DataStore.kt:49`，普通布尔）。入口 `if (runningTest) return else runningTest = true`（`ConfigurationFragment.kt:1217/1358`）。
- **过程不回写**：`TestDialog.update()`（:1140-1146）只 `results.add(profile)` + 刷新弹窗文本，全程不写库、不通知列表。
- **收尾串行回写**：`test.cancel`（:1330-1344 / :1399-1414）在后台协程里 `test.results.forEach { ProfileManager.updateProfile(it) }` 逐条写库+广播，随后 `GroupManager.postReload`（整页重载），**最后才 `runningTest = false`**（:1343/:1412）。
  - 症状1：180 节点 = 串行 180 次写库广播 + 整页重载 → 延迟变化滞后 2~3 秒（自然完成与中途取消同路径）。
  - 症状2：`runningTest` 在整串回写结束后才放行 → 期间点 TCPing/URL Test 被静默挡掉 = 空档期。
- 取消后进行中的 `urlTest` 挂在不可取消的 `suspendCoroutine`（`TestInstance.kt:25-40`），最多再跑自身超时（`connectionTestTimeout` 默认 3000ms，`DataStore.kt:200`）即自行关闭，不阻塞新测试。

## 修复

1. `TestDialog` 新增 `flushScheduled`(AtomicBoolean) + `resultsLock` + `scheduleFlush()`/`flushPending()`：每有结果即调度 250ms 防抖批量回写——`synchronized(resultsLock)` 取快照 → `ProfileManager.updateProfile(snapshot)`（列表版，一次写库+逐条 onUpdated）。行延迟经适配器 `onUpdated(profile, noTraffic=false)` 定点 `notifyItemChanged`（:2376+）实时刷新。
2. 两处 `test.cancel`：**第一行（主线程）先 `DataStore.runningTest = false`**；取消任务/兜底 `flushPending()`/`postReload` 全部留在后台协程。空档期 ≈ 弹窗关闭耗时（≪0.5s）。
3. 自然完成路径 `testJobs.joinAll(); runOnMainDispatcher { test.cancel() }` 不变——同一修复同时覆盖“测完”与“取消”。

## 红线

- `flushPending()` 保持「同步取快照、批量一次落库」语义；勿改回逐条写。
- `runningTest = false` 必须在 `runOnDefaultDispatcher` 之前（主线程同步执行）。
- 进行中结果在取消后丢弃（与官方一致）；防抖窗口内新测试开始属预期，新测试会覆写 status。

## 验证

- 本地：全新 A01 树按序应用 12 补丁全 APPLIED；与对照树 sha256 全量一致（`6e8846b7…`）。
- CI：[Neko-UI run #23](https://github.com/oh5uosnvh/Neko-UI/actions/runs/37580822392) 成功（head `63ebb0ac`），"Assert 012 connection-test fixes are in place" 通过。
- APK：`NekoBoxF-mod-r23-arm64-v8a.apk`，24,981,660 字节，SHA-256 `e3c7a031dd4889366a9643abd23e898a11015be18971e4be5db79ff0d3827154`，19 个 dex，ZIP 完整。
- 待真机：测试过程中延迟逐个实时刷新；取消即时出结果；测完/取消后立即重测无空档。
