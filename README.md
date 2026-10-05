# Neko-UI

NekoBoxF 的 UI 定制补丁与构建管线。基线 `A01`（tag @ f167ebc，fork 原版；原分支被删后已用 tag 恢复），9 个补丁按序应用即得成品。

## 功能规格（8 项）

| # | 功能 | 来源 |
|---|------|------|
| 1 | 配置页顶栏 ☰ 粗体Neko ⊙ ☴ 🔍 📄➕ ⋮ | 基线自带 |
| 2 | ☴ 列表，分组快速跳转 | 基线自带 |
| 3 | 长按分组名 → 分组页定位 | 基线 + 003 |
| 4 | 分组页 [默认\|排序] 连体按钮 | 基线自带 |
| 5 | 分组卡片动态加载 | 补丁 004 |
| 6 | 应用整体流畅度 | 001 / 004 / 006 / 007 |
| 7 | 侧边栏卡顿修复 | 补丁 001 |
| 8 | 分组卡片长按拖动排序，边缘固定 50% 最大速度 | 基线 + 003 |

## 构建

```bash
git clone --recurse-submodules https://github.com/oh5uosnvh/NekoBoxForAndroid.git upstream
cd upstream && git checkout A01
python3 ../scripts/apply_all.py                # 应用 9 补丁（--check 仅预检）
./run init action gradle && ./gradlew assemblePreviewDebug
python3 ../scripts/verify_build.py <apk>       # 成品校验
```

云端：Actions → **Build from patches** → Run（`upstream_ref=A01`）。

## 文档

| 文件 | 内容 |
|---|---|
| `docs/CHANGES.md` | 功能意图、符号锚点、红线 |
| `docs/FILES-TOUCHED.md` | 文件 ↔ 补丁映射、禁区 |
| `docs/REBASE.md` | 上游大更新移植步骤 |
| `patches/full/` | 全量参考 diff |
