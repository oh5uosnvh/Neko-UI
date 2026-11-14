# 最后构建 VMess 专项排查：2611142333

基于上一交付 `77371545c7a0 / 1.4.2-mg-2611142212` 的增量，不回退或合并其他架构。

## 已知事实，不替代手机端证据

- 用户最新手机截图仍显示 VMess 失败、FlClash 成功。
- 手机 Shizuku 服务未运行、无障碍关闭，不能读取当前安装版本/节点内容/失败错误；未宣称已在用户安装版完成手动验证。
- 用户旧备份的9个真实VMess为空WS路径；2个组的subscription.link均为空。此前“升级后更新原订阅”对这份本地导入组并不可执行。单修parser不恢复历史缺失字段。
- 不因独立Linux outbound连通就断言Android已正常。

## 新的完整验证

1. `CurrentVmessFullConfigTest` 真实运行 ProxyEntity/Kryo重读与 `TestInstance.buildConfig()`，而非仅出站helper；可输入私有备份及原source，但均不进入仓库。
2. 加入完整订阅DNS注册（neko-policy/neko-tls）、固定core、与APK相同boxapi.CreateProxyHttpClient、默认HTTP204 URL、同3秒预算两轮GET。仅移除Linux不支持的override_android_vpn/自动网卡标记，未冒称Android平台层验证。
3. 用新应用恢复matcher对旧备份9/9唯一匹配原配置并只补path，然后完整build，九节点两轮均HTTP204（一次请求约0.38–2.43秒，第二次约36–179ms，非APK测速读数）。
4. `vmess_probe_contract_test.go` 无外网/无私有节点：本地真实VMess WS服务端仅接受/source-only，错误根path必须失败，正确path经APK同HTTP客户端及neko-policy本地hosts两次204。纳入CI强制执行。

## 实际修复

- TestInstance此前把列表的live ProxyEntity交给会原地改Bean的buildConfig，可能改变NodeHostMapping/preserveMultiAddress/finalAddress；现在Kryo深拷贝后构建，新增快照不变断言。
- 为本地BASIC组配置菜单增加“从原配置修复旧 VMess”：用户选择FlClash原配置文件，4MiB上限，本地解析→唯一节点身份匹配→数量确认→事务检查原Bean快照→仅更新vmessBean和旧失败状态。没有后台订阅抓取、不猜path、不重建分组或节点、不覆盖流量/顺序/标签。
- 用mappedOriginalAddress作为原始端点身份，禁止不同域名共享同一hosts目标IP时误修。要求UUID/port/Host/security/alterId等一致，重复歧义不改；优先同名唯一匹配。只补path，保留旧认证headers和全部其他配置。
- 修复与导出文件选择器使用独立保存状态key，切换分组后不误修当前tab。
- 阶段异常包含构建版本和config/core-create/core-start/http-request。保留原cause内部诊断，显示和日志脱敏URL/UUID/端点/路径/token。错误提示不把合法根路径判为损坏。
- 移除DEBUG完整配置明文日志及YAML错误源码片段日志。取消不标记节点失败，内部取消完成调用方，事务写前检查取消。

## 使用和剩余边界

安装后，对旧本地导入组在配置页右上菜单选“从原配置修复旧 VMess”，选择原始YAML，确认匹配数量后重新测速。不需要导入覆盖备份。若节点已有非空path，此操作不会修改它；若仍失败，应点“不可用”读取新阶段错误。

此版本修复了可证明的历史丢字段恢复缺口、测速对象被改写和失败证据缺失，但没有手机错误日志前，不能声称已确认用户最新失败必然由这三者之一造成，更不能保证任意VMess服务器均兼容。
