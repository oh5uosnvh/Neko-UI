# Neko-UI

NekoBoxF 的 UI 定制补丁与构建管线。基线 `A01`（tag @ f167ebc，fork 原版），28 个补丁按序应用即得成品。

**当前版本：[Neko 0.01](../../releases/tag/v0.01)** · APK 见 [Releases](../../releases)

## 功能规格（8 项）

| # | 功能 | 来源 |
|---|------|------|
| 1 | 配置页顶栏 ☰ 粗体Neko ⊙ ☴ 🔍 📄➕ ⋮ | 基线自带 |
| 2 | ☴ 列表分组快速跳转；行 = `名称·数量`（灰阶，同分组卡片计数源） | 基线 + 010 |
| 3 | 长按分组名 → 分组页定位 | 基线 + 003 |
| 4 | 分组页 [默认\|排序] 连体按钮 | 基线自带 |
| 5 | 分组卡片呈现 = 官方一次性装载；20/20 分片仅兜底（防长时间/一直白屏） | 补丁 004 |
| 6 | 应用整体流畅度（侧边栏缓存/预热、DiffUtil 增量、insets、官方观感） | 001 / 004 / 006 / 007 |
| 7 | 侧边栏卡顿修复 | 补丁 001 |
| 8 | 分组卡片长按拖动排序 = 官方手感；松手落库后广播一轮（配置页/☴ 跟随） | 基线 + 003 |

## 补丁索引（按序应用）

| 补丁 | 内容 |
|---|---|
| 001 | 侧边栏实例缓存/错峰预热/抽屉离屏预热 |
| 002 | 各页滚动复位（onHiddenChanged 钩子） |
| 003 | 分组页拖动排序 + scrollToGroup 定位 + 删除即时同步 |
| 004 | 配置页官方装载 + 20/20 兜底 + DiffUtil 增量 + 松手广播就地重排 |
| 005 | 分组设置返回自动选中 |
| 006 | 多页面状态栏内边距修复 |
| 007 | 标签栏官方化（官方原文 + 透明 ripple） |
| 008 | 分组卡片布局净化（长按为唯一拖动入口） |
| 009 | CI 协议 mod 源钉扎 + GOSUMDB（管线已内建同等逻辑） |
| 010 | ☴ 列表行 名称·数量（applyGroupCounts） |
| 011 | 分组更新完成进度条永不收起修复（finishUpdate 广播数据库新对象） |
| 012 | 连接测试实时回显（250ms 防抖批量回写）+ 取消/测完零空档 |
| 013 | 连接测试提速（专用阻塞池，有效并发不再被核数封顶）+ 取消即停（invokeOnCancellation 关实例，僵尸秒死） |
| 014 | 清理不可用/去重接入“还原”snackbar（与滑动删除同契约） |
| 015 | 设置页菜单高频点击 NPE 根治（child 空安全 + 护栏） |
| 016 | 偏好编辑弹窗统一：密码/UUID 与普通参数同款（可输入+清空+复制），删除 PasswordDialogFragment |
| 017 | 底部长条启动栏（ⓘ出站IP \| 上传/下载 \| 实时延迟 \| 启动按钮）替代 FAB+实时数据托板；出站 IP 查询弹窗（复刻 FlClash 0.8.99）；移除 showBottomBar 设置 |
| 018 | 顶栏分组标签长按 → 编辑/跳转/删除三选菜单；删除分组带「还原」 |
| 019 | 启动栏抛光：延迟测试接线（修永远“测试中”）、延迟区收窄、出站IP弹窗加国家地区+IP/组织/ASN点击复制 |
| 020 | 分组删除还原修复（pager 只认 groupAdd） |
| 021 | 启动栏：按钮改静态图标+进度环（底色统一）、栏左右 16dp、延迟区固定宽（防文字晃动）、空白区防触摸穿透、仅配置页显示 |
| 022 | 分组还原原位插回（对齐节点卡片撤销语义） |
| 023 | 启动栏二抛：左右留白/间距微调、飞机垂直居中、延迟值去“延迟”前缀、三处触摸底色统一圆角矩形 |
| 024 | 启动按钮恢复官方 AVD 动画引擎（斜线划出/飞机形变，动画队列），去进度环遗留；四区布局 [ⓘ\|速度\|延迟\|启动] 均匀分布 |
| 025 | 出站 IP 识别复刻 FlClash 六源对冲（ident.me/ip-api/ipquery/iplocate/ipapi.is/proxycheck），字段/等级/命中标记与官方一致（优/普通/风险、滥用记录等） |
| 026 | VPN 连接即预查询出站 IP（点开秒出）、失败保留上次结果、国家地区跨源合并、栏宽收窄为内容宽并居中（去弹性空隙） |
| 027 | 启动栏三抛：速度区固定冗余宽（框长恒定）、未连接延迟占位“… ms”（四位余量）、连接成功自动 ping、左右留白对称 |
| 028 | IP 查询三修：先答先赢回归（select 按完成序，修 026 等全源导致的慢）、缓存绑定节点（切节点重连自动重查）、刷新立现“查询中”+失败回滚缓存 |

## 构建

```bash
git clone --recurse-submodules https://github.com/oh5uosnvh/NekoBoxForAndroid.git upstream
cd upstream && git checkout A01
python3 ../scripts/apply_all.py                # 应用 28 补丁（--check 仅预检）
./run init action gradle && ./gradlew app:assemblePreviewRelease   # release=R8 混淆+收缩，APK ≈16MB
python3 ../scripts/verify_build.py <apk>       # 成品校验
```

云端：Actions → **Build from patches** → Run（`upstream_ref=A01`）。
CI 产出 **release 签名包**（稳定 keystore 存于私有 Nekobox-MG `keystore/`，
覆盖安装友好；debug 构建的 runner 随机调试签名已废弃）。

## 校验

```bash
python3 scripts/verify_build.py <apk>
```

- dex 必需符号 9 项（含 `armFallback`/`applyGroupCounts`）
- 废案符号零残留（prefetchJob/sortButton/groupSort 等历史方案）
- libgojni 协议 mod 标记：x365 / viewTurbo / fastup / oppa-mod / mihomo 伪装

## 发布

1. CI 全绿 → 下载 artifact `NekoBoxF-patched-arm64`；
2. `verify_build.py` PASS 后改名 `Neko-<版本>-arm64-v8a.apk`；
3. Releases → New release → tag `v<版本>` → 上传 APK + 更新本文件版本号与版本历史。

## 文档

| 文件 | 内容 |
|---|---|
| `docs/CHANGES.md` | 功能意图、符号锚点、红线 |
| `docs/FILES-TOUCHED.md` | 文件 ↔ 补丁映射、禁区 |
| `docs/REBASE.md` | 上游大更新移植步骤 |
| `patches/full/` | 全量参考 diff |

## 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.01 | 2026-10-06 | 首个发布：8 项规格全量；拖动排序回归官方手感（松手落库+广播）；☴ 行名称·数量；20/20 兜底装载 |
