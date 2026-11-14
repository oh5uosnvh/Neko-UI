# 新手机日志驱动的 VMess 修复 2611150034

基于cc395ee / 2611142333。输入用户新原YAML和手机日志，不把Linux出站连通等同手机问题解决。

## 手机直接证据

- 日志头确认实际安装2611142333，versionCode10310，Android15/SDK35/ARM64。
- 两段真实运行配置分别对应原YAML VMess索引8与3、旧节点ID9与4。
- 真实transport.path均为根路径；源对应Path非根。Host/UUID/port/security/alterId相符；服务器是源hosts映射后的目标。
- 日志是正常VPN服务运行配置和DNS代理EOF，没有本次测速阶段ProbeFailure记录；不能把0.28秒DNS EOF直接认定为超时、并发或DNS实现根因。
- 新YAML与此前挂载原YAML结构及字节一致，不是源文件变更。

## 对照复现

从手机日志私下提取完整outbound和DNS：保持UUID/Host/端口/cipher/映射地址/DNS不变，实际根path失败，单改源Path后两轮HTTP204。Linux仅去掉Android平台标记、入站/用户geo规则文件依赖及引用这些geo集的DNS条件，非Android手动实测。

## 当前版本流程缺口

普通文件导入RawUpdater.parseRaw后进入ProfileBatchStore.append，只新增ProxyEntity；selectImportedGroup只选组，不选新节点，当前旧节点ID仍选中。启动BaseService按selectedProxy读旧行，所以用户重新导入源YAML也可能继续启动旧根path。

专用“修复旧VMess”是独立入口，普通导入不会调用。源码匹配器对可用旧Bean9/9唯一匹配（运行日志对应ID4/9身份本身唯一）；但没有当前DB导出，不能凭运行JSON证明用户此前是否操作过专用入口。

## 修复

1. 普通本地原YAML文件导入当前BASIC组时，先检测原组旧VMess与源的唯一匹配及路径不一致。显示明确选择“修复现有节点/仍然追加/取消”。修复选择只补原节点路径，不追加重复行，不改selectedProxy、节点ID/组/顺序/流量/标签/headers及其他参数。无候选按原方式导入。流式大量节点链接导入不改变。
2. 新共享VmessSourceRepairPlan绑定group/id/Bean字节快照；确认后同事务提交，过期编辑/移动/删除/改协议跳过；取消回滚整事务。返回真正提交ID集合供活动核心重载判断。
3. 专用入口与普通导入共用同计划。不猜路径：只对空或默认根path、无customJSON的VMess WS，源提供明确非根path且身份唯一，用户确认后才改。合法根path源保持不动；任意其他非空路径不改。
4. 原修复只刷新列表，已运行box可能继续使用旧配置；现在若真正修复的是currentProfile且服务已运行，调用原reloadService重新构建，不启动已停止服务。
5. CardLatencySession缓存此前可能遮住数据库中已清除的失败状态；现在定向invalidate已修复ID，并拒绝边测速边提交修复，避免历史不可用反复覆盖。

## 验证

- 本地实际Android主/测试源码编译通过。
- 本地matcher根path和非根源边界、完整TestInstance配置、修复状态缓存定向失效测试通过。
- 新增实际Room六项计划回归：相同ID不新增重复、保留确认期间流量/顺序、快照绑定、跨组与custom保护、精确已写ID集合、事务中途取消全回滚。x86 CI执行，不把aarch64 SQLite环境失败当通过。
- 原生真实VMess WS/同HTTP客户端回归继续CI运行。
- 手机日志及YAML私有内容只在/tmp本地做比较，不提交或加入APK/源码包。

## 使用

覆盖安装→停止当前批量测速→选原Bitz分组→正常“从文件导入”→选择原YAML→选择“修复现有节点”→应显示修复数（原九节点样本为9/9）。不需删组、覆盖备份或手工猜path。若当时VPN已在运行且使用修复节点，将重载原核心。然后重新测速。

本轮已确认手机确实下发错path且复现只改path即可连通。仍需用户安装后实测验证同旧ID的新运行配置已经携带源Path；不能宣称已远程操作用户手机完成安装和验证。
