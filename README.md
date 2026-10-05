# NekoBoxF Mod Kit（A02 系列 · 最终修订 sb24）

**自包含资产库**：补丁系列 + 成品功能规格 + 移植手册 + 独立构建管线。

> 目标：任何人或任何 AI 模型，**读完本仓库一遍**即可：
> ① 理解成品 App 的完整功能规格（8 项，见下表）；
> ② 把全部改动移植到新上游（[docs/REBASE.md](docs/REBASE.md)）；
> ③ 推 CI 构建出 APK 并自动校验（本仓库自带独立工作流，或走主仓库分支 CI）。
> 全部改动的源码都是现成的——本仓库只负责「可复现」。

- 基线：`A01` 标签（commit `f167ebc`，fork 原版）
- 当前成品：`pre-1.4.2-sb24`（主仓库分支 `fix/sidebar-jank`）
- 变更规模：17 个文件，+761 / −150（其中 `nb4a.properties` 为版本号，不进补丁）

---

## 成品功能规格（8 项 = 验收标准）

> **第 1-4 项为 A01 基线自带**（fork 原生功能，补丁零涉及，升级上游时随基线走）；
> **第 5-8 项由本套补丁承载**。

| # | 功能 | 来源 | 承载位置 / 关键符号 |
|---|------|------|----------------------|
| 1 | 配置页顶栏：☰ · **粗体 Neko** · **粗体 ⊙** · ☴ · 🔍 · 📄➕ · ⋮（等距单排） | 基线自带 | `ui/TopBarController.kt`（`setTypeface(BOLD)`、`ic_topbar_*` 自绘矢量） |
| 2 | ☴ 点击弹窗列表：分组快速跳转 | 基线自带 | `TopBarController.groups()/jumpTo()/jumpToLastGroup()` |
| 3 | 顶栏长按分组名 → 跳分组页并定位到该分组 | 基线自带 | `rowView.setOnLongClickListener` → `openGroupAt(id)` → `GroupFragment.scrollToGroup()` |
| 4 | 分组页顶栏「分组」右侧 [默认\|排序] 连体按钮（过滤/排序选择器） | 基线自带 | `GroupFragment.setupFilterBar()/showFilterPicker()/showSortPicker()` |
| 5 | 配置列表分组卡片动态加载（首屏渐进渲染、空数据兜底、单飞护栏） | **补丁 004** | `ensureLoadedIfEmpty` / `applyFirstFill` / `loadInFlight` |
| 6 | 应用整体流畅度（错峰预热、视图缓存、DiffUtil 增量刷新、官方动画优先） | **补丁 001/004/006/007** | 见 CHANGES #6 |
| 7 | 侧边栏卡顿修复（页面实例缓存、错峰预热、抽屉离屏预热、让路规则） | **补丁 001** | `fragmentCache` / `prewarmRunnable` / `drawerWarmRunnable` / `warmUpHiddenFragment` |
| 8 | 分组卡片**长按**拖动排序：边缘滚动**固定为最大速度的 50%**（非渐进加速）+ 每次换位实时落库广播 + 并发崩溃防护；☷ 手柄已按需求移除 | **补丁 003/008** | `isLongPressDragEnabled=true` / `interpolateOutOfBoundsScroll → maxScroll*0.5f` / `commitMove`（锁内快照） |

## 构建路径（三选一）

### 路径 A：主仓库分支直出（日常最常用）
```bash
git clone https://github.com/oh5uosnvh/NekoBoxForAndroid.git && cd NekoBoxForAndroid
git checkout fix/sidebar-jank
# 改动源码后（全部功能源码已就位，通常无需改）：
git add -A && git commit -m "..." && git push origin fix/sidebar-jank
# push 即自动触发 .github/workflows/build_mod.yml，约 7-10 分钟出包
# 产物：Actions → 该 run → Artifacts → NekoBoxF-pre-1.4.2-sbNN-arm64-v8a-debug.apk
```

### 路径 B：本 Kit 独立云端管线（无需本地环境，可复刻到任意上游 ref）
GitHub → 本仓库 → Actions → **Build from patches** → Run workflow
（默认输入 `upstream_ref=A01` 即可）→ 产物 `NekoBoxF-patched-arm64`。
前置：本仓库 Settings → Secrets 需有 `GH_PAT`（拉取私有协议 mod 源，与主仓库同一个 PAT）。
触发也可以用 API：
```bash
curl -X POST -H "Authorization: Bearer $GH_PAT" \
  https://api.github.com/repos/oh5uosnvh/NekoBoxF-A02-Kit/actions/workflows/build.yml/dispatches \
  -d '{"ref":"main","inputs":{"upstream_ref":"A01"}}'
```

### 路径 C：本地构建（验证补丁可应用性 / 调试）
```bash
git clone --recurse-submodules https://github.com/oh5uosnvh/NekoBoxForAndroid.git upstream
cd upstream && git checkout A01
python3 /path/to/kit/scripts/apply_all.py        # 9 个补丁顺序应用（--check 只预检）
./run init action gradle && ./gradlew assemblePreviewDebug
python3 /path/to/kit/scripts/verify_build.py "$(find app/build/outputs/apk -name '*arm64-v8a*.apk' | head -1)"
```

## 目录结构

| 路径 | 内容 |
|---|---|
| `patches/001~009` | 按文件/功能拆分的补丁（顺序应用，已验证对 A01 全部 CLEAN） |
| `patches/full/A01-to-latest-full.diff` | 全量参考 diff（不含版本号文件） |
| `docs/CHANGES.md` | **按 8 项规格组织**的功能清单：意图/文件/符号锚点/验证/红线 |
| `docs/FILES-TOUCHED.md` | 文件 → 补丁 → 功能映射 + 不可触碰的官方区域 + 禁止符号 |
| `docs/REBASE.md` | 上游大更新移植 SOP（AI 分步指南） |
| `scripts/apply_all.py` | 补丁应用器（--check / 默认 / --only-clean / --reverse） |
| `scripts/verify_build.py` | APK 成品校验（dex 必需+禁止符号、libgojni.so 协议标记） |
| `build/build-from-patches.yml` | 构建管线源文件（同 `.github/workflows/build.yml`） |

## 红线（改动前必读）

1. **配置页分组切换路径必须保持官方源码**——左右滑、点标签、滑动标签栏后点选。
   历史教训：任何加在切换路径上的预取/预热/让路/瞬时跳转都引发过卡顿，已全量移除，勿再加料。
2. 顶部标签栏 = 官方上游布局 + **唯一偏离**：`tabRippleColor` 透明（点按/长按无底色）。
3. 长按分组名 → `openGroupAt`/`scrollToGroup` 跳转定位必须保留。
4. **☷ 拖动手柄已按需求移除**：长按卡片是拖动排序的唯一入口（勿恢复 `groupSort`）。
5. 拖动到边缘的自动滚动 = **固定 maxScroll×0.5f**（最终规格；不要改回渐进加速或其它系数）。
6. 拖动排序的实时广播与 `updated` 锁内快照必须保留（否则快速连拖多卡会
   ConcurrentModificationException 崩溃）。

详见 [docs/FILES-TOUCHED.md](docs/FILES-TOUCHED.md)。
