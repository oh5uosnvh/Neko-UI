# 1.4.2-mg-pre2：订阅私有 DNS 与域名映射

基于正式发布 **1.4.2-mg-pre1**（`53f1f18321a5c535d5d284ce5b3dd9b471bdf258`），不是其他实验分支。包名保持 `com.nb4a.qiyiguo112`；基准版本代码 49 → 50，即 Android 245 → 250。魔改协议模块和 sing-box 固定提交不变。

## 分组设置

“订阅设置”中的顺序为：

1. 自定义服务器 DNS（原有功能完善，测速也生效）
2. **自定义直连 DNS**
3. **私有 DNS 设置**
4. **域名映射设置**

前两项接受单个 DNS，或按行/逗号分隔多个 DNS；支持 UDP/TCP/DoT/DoH/QUIC/H3，IPv4、IPv6及自定义端口。私有 DNS 是订阅解析策略，不是 Android 系统私人 DNS。

后两项留空：自动使用 YAML 中相应配置；`{}`：禁用自动配置；填写 YAML：替换对应自动内容。用户填写的内容与订阅提取快照分开保存，更新不会把自定义值冲掉。自动配置仍要通过更新订阅提取；已有订阅升级后请先更新一次。

私有 DNS 示例（合成域名）：

```yaml
nameserver-policy:
  '+.private.example': tcp://resolver.example:8080
proxy-server-nameserver-policy:
  '+.nodes.example':
    - https://resolver.example/dns-query
    - https://192.0.2.53/dns-query
```

可以粘贴含 `dns:` 的完整相关区块，也可直接填写 `域名模式: DNS` 映射（作为 nameserver-policy）。

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
- 私有策略的多个 DNS 按顺序尝试；每个尝试默认最多 2 秒，可取消。全部失败不转公网默认 DNS；备用仅限同一规则列表。
- 域名别名在 DNS 层生成 CNAME/解析目标；不会永久替换节点服务器、TLS SNI、证书身份或 WebSocket Host。别名链最多 16 层，循环拒绝。
- `default-nameserver` 仅用于解析上游 DNS 自身的域名；没有配置则用系统基础解析，避免私有 DNS 自己解析自己的回环。
- 源 Clash 将 `proxy-server-nameserver` 指回本地 DNS listener（例如 7874）时，移植其 `nameserver` 上游用于**节点解析**，不假设 Android 上有该服务。保留 DoH 自定义路径和端口。
- 当前订阅的直连 DNS 只覆盖本次生成配置，不修改全局偏好。
- 存在订阅解析配置时跳过系统“强制解析”，保留动态域名。普通 URL 测速与正式连接共用解析路径。
- 不能进行映射的第三方外部协议插件会明确报错，避免假装支持而泄漏查询；内置协议及可映射插件使用真实 resolver。

## 安全和边界

不导入完整 Clash 路由、FakeIP/fallback-filter、面板或系统 hosts 设置；这些继续由 NekoBox 自身负责。规则 DNS 支持上述具体模式，不宣称兼容 Mihomo 全部 DSL。DNS URL 中带用户名/密码、查询参数和 fragment 的扩展暂不支持，会明确拒绝；自定义 DoH 路径可用。

YAML 使用 SafeConstructor，显式传递 LoaderOptions，并做事件级预检：禁止重复键、危险对象标签、循环 aliases，深度上限64、alias引用上限32、文档上限16MiB。规范化快照只保存 DNS/hosts，无完整订阅、节点密码或UUID。UI列表只显示状态，不直接暴露私有DoH路径。

订阅存储 v3→v4、节点附加存储 v1→v2，向后读取旧数据；共享订阅仍使用原v0格式，不额外分享私有DNS数据。降级安装旧版软件不保证保留新字段。

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
