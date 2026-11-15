# URLTest 深度优化（2026-11-16）

## 对照 sing-box 官方实现

官方 `common/urltest.URLTest` 的核心语义：

- 单次 `HEAD` 请求，不下载网页正文；
- 先通过默认出站 `DialContext` 建立连接；
- 默认使用 `https://www.gstatic.com/generate_204`；
- 以首个响应完成为成功；
- 对需要“首次写入才握手”的出站，测量起点在连接拨号后重置；
- URLTest 组内部采用固定批量并发并保存历史结果，未到 interval 不重复测试；
- 内核默认 TCP 超时为 15 秒，正式客户端并不直接把用户的 3000ms 设置传入官方函数。

## 本项目问题

- 原 `libneko/speedtest.UrlTest` 使用两次 GET，并把第二次结果覆盖第一次；
- 普通 URLTest、启动后的控制条测速都绕过官方 `common/urltest`；
- 控制条点击路径额外执行一次冷启动重试，3000ms 会被放大为约 6250ms，原生清理阻塞时还可能更久；
- `boxapi.CreateProxyHttpClient` 有独立 3 秒 TLS/响应头定时器，容易和用户总预算产生双重超时语义。

## 本次修改

- `libcore/box.go` 的有核心测速改为直接调用官方 `common/urltest.URLTest`；
- 在官方调用外包一层用户配置的端到端 context deadline；
- 无核心的 direct 测试保留轻量 HTTP 探测；
- HTTP 探测改为单次 HEAD、禁止重定向跟随、关闭隐藏阶段定时器、复制 transport 避免污染共享客户端；
- 控制条去除第二次冷启动重试；
- 启动探测去除隐藏二次尝试，3000ms 只允许一次 3000ms 预算。

## 验证

- `libcore` Go 测试：通过；
- Android Kotlin 编译在本地被缺失/未生成的 native bindings 阻断（`ProbeContext`、`newProbeContext` 等绑定缺失），不是本次 Go 代码编译错误；CI 会先生成 libcore bindings 再构建 Android。
