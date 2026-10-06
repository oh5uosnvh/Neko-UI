# Neko-UI

NekoBoxF 的 UI 定制补丁与构建管线。基线 `A01`（tag @ f167ebc，fork 原版），10 个补丁按序应用即得成品。

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

## 构建

```bash
git clone --recurse-submodules https://github.com/oh5uosnvh/NekoBoxForAndroid.git upstream
cd upstream && git checkout A01
python3 ../scripts/apply_all.py                # 应用 10 补丁（--check 仅预检）
./run init action gradle && ./gradlew assemblePreviewDebug
python3 ../scripts/verify_build.py <apk>       # 成品校验
```

云端：Actions → **Build from patches** → Run（`upstream_ref=A01`）。

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
