# 013：连接测试提速 + 取消即停（真机 2~3 秒空档根因）

日期：2026-10-08。基线：A01 + 补丁 001-012。改动：`bg/proto/TestInstance.kt`、
`bg/proto/UrlTest.kt`、`ui/ConfigurationFragment.kt`（urlTest 部分）。

## 根因（源码实证）

- 单实例测试（`TestInstance.doTest`）把 `init() → launch() → Libcore.urlTest`
  整条**阻塞链**跑在 `runOnDefaultDispatcher`（= `GlobalScope.launch(Dispatchers.Default)`）。
  `Libcore.urlTest` 是 JNI 阻塞调用（真实 HTTP GET，上限 `connectionTestTimeout`）。
  - 后果1：每个在测实例钉死一个 Default 线程直到测完/超时。Default 池 = CPU 核数
    （本机 8）→ **有效并发恒等于 8，`connectionTestConcurrent` 开再高也快不起来**。
  - 后果2：取消测试只 cancel 了 worker 协程；`use { … }` 块挂在 GlobalScope 上
    **不可取消**，25 个僵尸实例继续跑满各自超时（默认 3s），期间霸占**全部**
    Default 线程 → 新测试的 `mainJob`（DB 查询也在 Default）排队 ≈2~3s →
    “弹窗出来了但一直加载，之后才开始测试”。与并发设置无关。
- 012 已修“写库串行滞后”，但没动这条阻塞链——空档期从 2~3s 降到 2s+，未根治。

## 修复

1. `TestInstance.doTest(context)`：
   - `suspendCancellableCoroutine` + `invokeOnCancellation { closeOnce() }`——取消
     瞬间关闭实例，Go 侧 urlTest 立即报错返回，僵尸秒死（不等超时）。
   - 阻塞段 `withContext(context)` 进**专用阻塞池**；外层 coroutine 跑 Default。
     闭池竞态（取消早于提交）表现为 `RejectedExecutionException`，被 catch 干净失败。
2. `ConfigurationFragment.urlTest()`：创建 `Executors.newFixedThreadPool(
   concurrent.coerceIn(1, 16)).asCoroutineDispatcher()`；`UrlTest(testPool)`；
   两条收尾路径（自然测完 / 用户取消）都经 `test.cancel` → `testPool.close()`。
   - 上限 16（≈2×CPU）：测试实例为无入站轻量配置，16 路内存安全。
   - 真实 HTTP 延迟测量路径（经代理 GET 测试 URL）**零改动**——提速来自并发
     结构，不改单次测量的任何环节。
3. `pingTest`/TCPing 不动：worker 直接跑 `Dispatchers.IO`（64 线程），不受核数封顶。

## 红线

- `closeOnce()` 必须 `AtomicBoolean` 幂等；`invokeOnCancellation` 注册必须在
  阻塞段提交之前。
- `testPool` 只允许在 `test.cancel` 里关闭；不要改回 `Dispatchers.Default`。
- `Libcore.urlTest` 的调用参数（link/timeout）不得动。

## 验证

- 取消后重测：空档期 ≈ 弹窗 dismiss（≪0.5s），无等满超时的加载期。
- 并发 25 设置下实测吞吐 ≈ 旧实现 2~3 倍（16 路 vs 8 路），死节点批次明显变快。
- CI：`Assert 013 …` 通过 + 真机验证待做。
