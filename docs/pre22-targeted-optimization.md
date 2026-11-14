# pre22 定向优化（数字候选版）

## 基线与范围

- 唯一代码基线：`1.4.2-mg-pre22`，commit `f8b036f2fc0485fe232230bceda977a4ed1977b3`。
- 不合并 pre23/pre24 的全局 selector、DNS 架构、测速或卡片布局改造。
- 数字版本 `1.4.2-mg-2611142100`；base versionCode 2060，Android versionCode 10300。

## 定向改动

1. VMess：移植最后成功构建的 auto+TLS AEAD 选择、Clash 新旧 WebSocket path/headers 解析以及额外握手头持久化。显式指定的 cipher 不改变。
2. 分组列表菜单：标签成员页显式“锁定/解锁”；锁定后重开定位当前分组，解锁后重开回全部配置分组。标签失效自动退出范围。
3. 分组搜索：空查询默认出现“一键归类”；保守相似名称识别、预览确认、只排序不合并删除、可撤销，并遵守当前筛选范围。
4. 内置路由：坏记录逐条跳过；刷新全部无效时保留旧缓存；本地 geo 数据库与类别不可读时跳过相应内置条件。清理后的空规则不得变成 catch-all。保留 patched core 的 `path=geosite:.../geoip:...` 语义，不影响用户自定义规则错误的原有处理。
5. 备份：导入/导出默认仅选择“分组和配置”，文件、分享、WebDAV 一致。使用原版 `version=1` 的 Parcel/Base64 `groups` / `profiles` 数组；不写入或恢复标签组、成员关系和标签设置。配置覆盖导入在解析成功后事务替换；旧成员关系随分组删除级联清理，不让相同 ID 误绑定旧标签。

## 兼容验证与边界

- 使用用户提供的私有 pre22 样本：2 个分组、94 个配置（10 VMess、84 Trojan）。私有文件、节点内容、账号和凭据不进入 Git 或测试日志。
- 本地解码、原版投影、重读：94/94 成功，无协议被跳过。
- 编译并运行官方 `MatsuriDayo/NekoBoxForAndroid` tag `1.4.2` 与 `starifly/NekoBoxForAndroid` tag `1.4.2-rev-24` 的实际原始 group/subscription/VMess/Trojan 读取器：双方均读取 2 分组/94 配置成功。
- 这是实际代码格式验证，不代表已经在两款安装版 App 内手动导入，更不保证目标 App 支持本分支的专属协议。
- 原版不能表示的分组 selector、front/landing 与订阅 DNS/过滤扩展投影为原版默认值；UI 明示该限制。旧版可表示的 WS 额外头使用原有 customOutboundJson 槽位保存。
- 对原版确实不能表示的协议/传输或配置选项，明确中止导出并报错，不静默丢节点。路由 Parcel 在官方与第三方之间存在未版本化字段差异，因此默认不导出路由，不能声称任意全量备份跨版本等价。

## 验证

- 本地 Android 主源码和测试源码编译通过（仅跳过本地无可执行 NDK 的 buildHevTun；GitHub 不跳过）。
- 首轮全套 624 项：463 通过；136 原生绘图和25 SQLite 测试因 Linux aarch64 无支持而失败，不当作通过。
- 定向测试：VMess8、归类11、菜单8、搜索4、备份投影5、路由解析3，共39项通过；另3项真实 Parcel/JSON 回归通过。
- 订阅 DNS 独立27项回归通过。
- GitHub Actions 使用 x86_64 完整跑 group/ui/database 测试、原生 geo 回归并重新构建 AAR、ARM64/ARMv7 APK；签名沿用工作流已有证书校验，不发布/改写旧 Release。
