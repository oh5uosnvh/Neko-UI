# 1.4.2-mg-pre1-debug-dns03：快捷操作与DNS调试候选

仅供调试验收，不创建 Release/发布标签。基于正式发布 **1.4.2-mg-pre1**（`53f1f18321a5c535d5d284ce5b3dd9b471bdf258`），不是其他实验分支。包名保持 `com.nb4a.qiyiguo112`；安装版本代码为260，可覆盖dns02的255候选包。魔改协议模块和 sing-box 固定提交不变。

## 本轮界面修复与快捷操作

- 未连接VPN（包括冷启动、连接中、停止和状态恢复）统计托板立即隐藏，不先拉起再收起；启动FAB仍保留，连接后的滚动显隐不变。
- 顶部标签长按200ms弹菜单：编辑、跳转分组、分隔、导出剪切板、导出文件、分隔、清空、删除。复用原分组标准节点链接导出，目标锁定长按的groupId。
- ☴快捷分组列表取消长按编辑，卡片内右侧☷拖动手柄即时开始拖动；松手保存顺序并同步标签/分组页，单击跳转按稳定ID不串组。
- 路由卡片左下角出口文字可直接选择代理/绕过/屏蔽/配置，选中即保存；只改outbound字段，不覆盖条件、开关或排序。
- 始终显示地址开关变化，直接重绑所有缓存/恢复的分组卡片，不再依赖数据库Diff，切回配置页立即生效。

## 分组设置

基本分组和订阅分组中均始终显示四项；弹窗只显示实际参数，不包含解释文字。摘要为“未设置 / 订阅提供 / 本地文件提供 / 自定义”。顺序为：

1. 自定义服务器 DNS（原有功能完善，测速也生效）
2. **自定义直连 DNS**
3. **私有 DNS 设置**
4. **域名映射设置**

前两项接受单个 DNS，或按行/逗号分隔多个 DNS；支持 UDP/TCP/DoT/DoH/QUIC/H3，IPv4、IPv6及自定义端口。私有 DNS 是订阅解析策略，不是 Android 系统私人 DNS。

自动参数会直接填入对应编辑框；打开或原样保存不会成为手动覆盖。清空已自动提供的参数，会内部保存`{}`关闭该项，列表和弹窗显示未设置/空。填写新值作为手动覆盖，订阅更新不会覆盖。四项相互独立，清空私有策略不会清空服务器DNS。

本地文件首次导入空组时，以文件名去最后扩展名命名，例如`哦去咯会哦.TXT`→`哦去咯会哦`、`n'jinli.yaml`→`n'jinli`；追加到非空组不反复改名。导入时默认把节点地址按hosts A→B替换。映射行右侧粗体`⇆`即时批量切换“映射目标 / 原始地址”；保存原地址避免多个A指同一B时误还原。新组的映射方向用于后续导入。

私有 DNS 示例（合成域名）：

```yaml
nameserver-policy:
  '+.private.example': tcp://resolver.example:8080
proxy-server-nameserver-policy:
  '+.nodes.example':
    - https://resolver.example/dns-query
    - https://192.0.2.53/dns-query
```

可以粘贴含 `dns:` 的完整相关区块，也可直接填写 `域名模式: DNS` 映射（作为 nameserver-policy）。编辑器支持裸map、flow map、每行多余外层缩进，以及 `+.example: 'https://a.example/dns-query', 'https://192.0.2.53/dns-query'` 逗号列表。自动回显去掉dns:/hosts:包装和无关Bootstrap；节点专用策略保留proxy-server-nameserver-policy标识，避免缩写丢失作用域。完整订阅仍用严格安全解析，不使用片段归一化。

域名映射示例：

```yaml
hosts:
  node.alias.example: real.endpoint.example
  node.ip.example: [192.0.2.10, '2001:db8::10']
```

也接受不带 `hosts:` 的简写映射。支持精确域名、`+.`（根及所有子域）、`*.`（单层子域）、`.`（仅子域），大小写及尾点标准化；不支持任意中间通配、geosite/rule-set 策略键，遇到它们会报错而非静默错用。

## 运行时行为

- **hosts → 节点专属 policy → 普通 policy → 自定义服务器 DNS / YAML 节点 DNS → NekoBox 直连 DNS**。本版本明确允许普通 policy 作为节点策略的后备，也允许只有 proxy-server policy 而没有默认节点 DNS；这是兼容扩展，而非完全照搬 Mihomo 的开关限制。
- 每个出站绑定独立 resolver；同域名不同配置的订阅不会复用错误缓存。链中节点使用各自所属组，不强制继承主组。
- 普通应用查询仅应用当前订阅的 **nameserver-policy/hosts 命中项**；不会把所有域名都发给私有 DNS。未命中保留 NekoBox 的原有 DNS 分流。
- 多DNS优先使用该列表上次成功的解析器；150ms无正响应启动备用，最多3路并发，每路默认2秒、整次最多6秒或父请求更短。赢家取消其它请求，仅限同一规则列表，失败不转公网。TCP/DoT最多缓存4条空闲连接、20秒有效，复用TLS握手并保留取消关闭机制。
- 域名别名优先在导入/构建时直接替换服务器地址，原始地址单独保存；TLS SNI、证书身份或WebSocket Host保持原来语义。多IP hosts在运行时仍保留完整候选，不冻结首个IP。别名链最多16层，循环拒绝。`⇆`只切换节点server保存A还是B，不是映射开关：两种地址模式始终保留DNS hosts A:B；使用B时自然不再命中A，切回A后继续按A→B解析。
- `default-nameserver` 仅用于解析上游 DNS 自身的域名；没有配置则用系统基础解析，避免私有 DNS 自己解析自己的回环。
- 源 Clash 将 `proxy-server-nameserver` 指回本地 DNS listener（例如 7874）时，移植其 `nameserver` 上游用于**节点解析**，不假设 Android 上有该服务。保留 DoH 自定义路径和端口。
- 当前订阅的直连 DNS 只覆盖本次生成配置，不修改全局偏好。
- 存在订阅解析配置时跳过系统“强制解析”，保留动态域名。普通 URL 测速与正式连接共用解析路径。
- 不能进行映射的第三方外部协议插件会明确报错，避免假装支持而泄漏查询；内置协议及可映射插件使用真实 resolver。

## 安全和边界

不导入完整 Clash 路由、FakeIP/fallback-filter、面板或系统 hosts 设置；这些继续由 NekoBox 自身负责。规则 DNS 支持上述具体模式，不宣称兼容 Mihomo 全部 DSL。DNS URL 中带用户名/密码、查询参数和 fragment 的扩展暂不支持，会明确拒绝；自定义 DoH 路径可用。

YAML 固定使用 SnakeYAML 2.7（App 与独立测试一致）；修复2.3对跨1024字符缓冲边界 emoji 的读取异常，并显式统一64层嵌套和16MiB限制。使用 SafeConstructor，显式传递 LoaderOptions，并做事件级预检：禁止重复键、危险对象标签、循环 aliases，深度上限64、alias引用上限32、文档上限16MiB。规范化快照只保存 DNS/hosts，无完整订阅、节点密码或UUID。UI列表只显示状态，不直接暴露私有DoH路径。

订阅存储v5（来源与映射模式）、节点附加存储v3（来源、原地址、目标、自动身份字段）、分组存储v2（基本组也保存DNS BLOB），均向后读取旧数据；共享订阅仍用原v0格式。降级安装旧版软件不保证保留新字段。

## 可复现验证

```sh
python3 buildScript/test-subscription-dns.py
# 本地才用 --audit-yaml 加自己的配置；不要提交真实订阅文件。
cd libcore
go test -race -count=1 ./dnspolicy
# 完成 libcore AAR 与Android SDK准备后：
./gradlew app:testPreviewDebugUnitTest --tests 'io.nekohasekai.sagernet.group.*'
```

CI 执行解析测试、内核竞态/集成测试、Android依赖下的组级单元测试，再生成APK。内核集成包括实际TCP/DoT/DoH服务、自定义DoH路径、静默TCP超时、同域名缓存隔离、真实TLS代理握手且验证原证书/SNI。

这些检验能证明代码和配置通路有效，不能替代用户真实网络中私有解析服务的可达性验证。安装后须验收：更新五份订阅、启动/切换、真实延迟测试、直连域名、IPv6和旧数据保留。
