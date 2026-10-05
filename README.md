# NekoBoxF A02 Kit

**A02 版本全部自定义改动的自包含资产库**：补丁系列 + 移植手册 + 独立构建管线。
目标：上游（matsuridayo / starifly 等）发布大更新后，**任何人或任何 AI 模型**都能
依据本仓库把 A02 的改动完整移植到新上游并构建出成品，无需本次会话的任何上下文。

- 基线：`A01` 标签（commit `f167ebc`）
- 产物：`A02` 标签（commit `48e4fbd`）
- 变更规模：16 个文件，+559 / −104

## 快速开始

### 1) 在 A01 基线上复现 A02

```bash
git clone https://github.com/oh5uosnvh/NekoBoxForAndroid upstream
cd upstream && git checkout A01
python3 /path/to/kit/scripts/apply_all.py        # 按序应用全部补丁
./run init action gradle && ./gradlew assemblePreviewDebug
python3 /path/to/kit/scripts/verify_build.py app/build/outputs/apk/**/NekoBox*.apk
```

### 2) 上游大更新后的移植（AI 作业流程）

**必读：[docs/REBASE.md](docs/REBASE.md)** —— 逐步指南。
一句话：克隆新上游 → `apply_all.py --check` 看哪些补丁失配 → 按
`docs/CHANGES.md` 里每个功能的「意图 / 锚点 / 红线」手工移植 → 校验 → 构建。

### 3) 一键云端构建

本仓库自带 GitHub Actions：`.github/workflows/build.yml`（手动触发，可指定
上游 repo/ref）。**前置：在本仓库 Settings → Secrets 添加 `GH_PAT`**（与主仓库
相同的 PAT，用于拉取私有协议 mod 源）。

## 目录结构

| 路径 | 内容 |
|---|---|
| `patches/001~008` | 按功能拆分的补丁（顺序应用） |
| `patches/full/A02-full.diff` | 全量参考 diff |
| `docs/CHANGES.md` | 每个功能：意图 / 文件 / 关键符号 / 验证标记 / 红线 |
| `docs/REBASE.md` | 上游大更新移植 SOP（AI 分步指南） |
| `docs/FILES-TOUCHED.md` | 文件 → 功能映射 + 不可触碰的官方区域 |
| `scripts/apply_all.py` | 应用补丁（--check 只检测 / 默认应用 / --reverse 回滚） |
| `scripts/verify_build.py` | APK 成品校验（dex 符号 + libgojni 协议标记） |
| `build/build-from-patches.yml` | 构建管线（也在 .github/workflows/ 下，Actions 页可直接跑） |

## 红线（改动前必读）

1. **配置页分组切换路径必须保持官方源码**——左右滑、点标签、滑动标签栏后点选。
   A02 曾在此引入预取/预热系统导致每次切换卡顿，已于 sb14 全部移除，勿再添加。
2. 顶部标签栏 = 官方上游布局 + **唯一偏离**：`tabRippleColor` 透明（需求指定：
   点按/长按不出现底色）。白色圆角短线指示器保留。
3. 长按分组名 → 跳转分组界面并定位（`openGroupAt`/`scrollToGroup`）必须保留。

详见 `docs/FILES-TOUCHED.md`。
