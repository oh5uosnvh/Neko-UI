# URLTest 与 sing-box JSON 导入深度修复（2026-11-16）

## 用户反馈对应根因

### JSON 导入只得到一张完整配置卡片

`RawUpdater` 之前只要看到 `endpoints` 字段就进入完整配置模式。用户配置中的 `endpoints: []` 是空数组，却被当成完整配置，结果整个 JSON 被保存为一张 `sing-box config` 卡片，outbounds 没有拆成可管理节点。

修复后：

- 空 `endpoints` 不再触发完整配置模式；
- 用户附件同构配置预期导入 43 个协议节点和 1 个 URLTest 卡片；
- direct/block/DNS 作为依赖保留，不误生成节点卡片；
- URLTest/selector/detour/domain_resolver/DNS batch 的引用闭包随节点保存；
- 使用 outbound 模式保存，编辑单节点时不再出现完整顶层 JSON；
- 不支持拆解的真正 endpoint/service/cloudflared 配置继续作为完整配置；
- 未知协议字段原样保留，不用协议白名单静默丢字段；
- 损坏的引用整批明确失败，不部分导入。

### 卡片 URLTest 与控制条结果不一致、批量超时随机

控制条使用已运行的主核心和当前出站；旧卡片批测则每个节点冷启动一个临时核心。大量节点同时测试时，会同时抢占 Android CPU、DNS、socket/FD、插件临时文件和核心启动资源。即使 HTTP deadline 正确，也会产生冷启动路径差异和批次资源抖动；因此同一节点可能出现控制条约 141ms、卡片约 2290ms，超时数量也随轮次变化。

修复后：

- 内置节点批测使用一个临时 sing-box 核心，配置一个包含本批节点的 selector；
- 通过原生 `UrlTestOutbound` 按 outbound tag 直接拨号，不改变 selector 全局选中状态；
- 同一核心上的网络探测并发限制为 10，贴近官方 sing-box URLTest 批量语义；
- 外部插件、chain、完整自定义配置保留旧的隔离核心回退路径；
- 节点地址族、domain strategy、hosts 和订阅 DNS 规则与正常构建保持一致；
- 单节点 deadline、核心启动、插件就绪和请求预算不再重复续期；
- 插件 JSON/CA 临时文件使用原子唯一文件名，关闭一个实例不会删除其他实例文件；
- 取消和异常经过统一清理，避免下一轮复用未完成的资源。

## 验证

- Go 原生测试：`go test .` 通过；
- Android 定向编译：通过（使用最新 CI 生成的 libcore AAR）；
- Android 定向回归：通过；
- JSON 导入 synthetic fixture 覆盖 46 outbounds、43 协议节点、URLTest、DNS batch、selector、detour、未知字段保真；
- 统一 deadline、插件监听就绪、批次清理和临时文件隔离测试通过；
- 未在沙盒中使用用户附件凭据访问真实节点，真实 100 轮稳定性仍需新 APK 真机验证。

本修复不伪造更低延迟、不把超时阈值偷偷扩大，也不使用无界重试掩盖真实失败。
