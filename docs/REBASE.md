# 上游大更新移植 SOP（给 AI 模型的分步指南）

适用场景：上游（matsuridayo/NekoBoxForAndroid、starifly/NekoBoxForAndroid 或其他
分支）发布了大更新，需要把本套改动移植到新版本上。

## 前置输入

- 新上游源码树（git clone 后 checkout 到目标 ref/tag）
- 本仓库（补丁 + 手册）
- 用户的验收标准：**8 项功能规格全部可用（见 README 表格）、流畅度不回退、
  官方观感不回退**

## 核心原则（先读三遍）

1. **配置页分组切换路径必须保持官方原文**。历史教训：任何加在切换路径上的
   「优化」（预取/预热/让路/瞬时跳转/自定义监听器）都引发过卡顿，最终全部移除。
   移植时只移植 `docs/CHANGES.md` 列出的内容，**不要顺手加料**。
2. 每个改动都有「意图 + 锚点 + 红线」。上游代码变了 = 锚点可能变了 = 需要在
   **新代码里找到等价位置**重新实现，而不是机械打补丁。
3. 每完成一项就构建 + 跑 `scripts/verify_build.py`，不要攒到最后。

## 步骤

### 第 0 步：建立工作树

```bash
git clone <新上游repo> work && cd work
git checkout <目标ref>
git remote add kit <本kit仓库> && git fetch kit   # 可选：便于取补丁
git submodule update --init --recursive
```

### 第 1 步：预检（哪些补丁还能直接用）

```bash
python3 /path/to/kit/scripts/apply_all.py --check
```

输出会把 9 个补丁分成 `CLEAN（可直接 apply）` 与 `CONFLICT（需手工移植）`。
基线自带的规格 1-4（TopBarController / setupFilterBar）不在此列——直接在新上游
里按 CHANGES #0 的锚点核对它们是否仍然存在。

### 第 2 步：应用全部 CLEAN 补丁

```bash
python3 /path/to/kit/scripts/apply_all.py --only-clean
```

（或逐个 `git apply patches/NNN-*.patch`，跳过 CONFLICT 的。）

### 第 3 步：逐个手工移植 CONFLICT 补丁

对每个 CONFLICT 的补丁：

1. 打开 `docs/CHANGES.md` 对应章节，读「意图 / 关键符号 / 红线」；
2. 在新上游代码里搜索等价锚点（符号名 / 官方原始写法）；
3. 以「语义等价」为准重新实现——**官方写法优先于本套写法**（上游可能已用更
   好的方式实现同语义）；
4. 完成一个补丁就 `git add -A && git commit`，保持每补丁一提交。

### 第 4 步：构建 + 自动校验

```bash
./run init action gradle && ./gradlew assemblePreviewDebug
python3 /path/to/kit/scripts/verify_build.py "$(find app/build/outputs/apk -name '*arm64-v8a*.apk' | head -1)"
```

`verify_build.py` 会检查：
- dex **必需符号**：`openGroupAt`/`scrollToGroup`/`resetScrollState`/`calculateDiff`/
  `ensureLoadedIfEmpty`/`applyFirstFill`/`syncOrderFromDb`/`drawerWarmRunnable`；
- dex **禁止符号**（历史废案 + 已移除功能）：`prefetchJob`/`pageWarmStep`/
  `isPagerBusy`/`sortButton`/`groupSort` 等；
- libgojni.so 协议 mod 标记：`x365`/`viewTurbo`/`fastup`/`oppa-mod`/`mihomo/1.19.25`。

### 第 5 步：人工验收（8 项规格逐条过）

按 README 的规格表逐条验收，重点：

1. 顶栏 8 元素等距、Neko 与 ⊙ 粗体；☴ 列表可跳转；
2. 长按分组名跳到分组页并定位；
3. 分组页 [默认|排序] 两个选择器可用；
4. 进入大分组首屏不空白（动态加载）；
5. 冷启动后逐页点开无首次卡顿；打开 App 立刻点 ☰ 不卡；
6. 分组页**长按**卡片拖动排序（无 ☷）；拖到边缘滚动速度均匀（最大速度的 50%）；
   快速连拖多卡不崩溃；拖完立刻返回配置页顺序已生效；
7. 配置页左右滑/点标签切换 = 官方手感。

### 第 6 步：收尾

- 更新 `nb4a.properties` 的 `PRE_VERSION_NAME`；
- 若补丁有改动，回写本 kit 仓库（保持补丁与源码一致）；
- 在 CI 上用新基线跑一次 `Build from patches` 全链路验证。

## 冲突热区速查

| 文件 | 风险 | 策略 |
|---|---|---|
| `ConfigurationFragment.kt` | 最高（改动最频繁） | 先恢复官方原文，再按锚点逐项移植 |
| `MainActivity.kt` | 高（页面管理可能重构） | 对齐 displayFragmentWithId/displayFragment/restoreFragments |
| `GroupFragment.kt` | 中（删除/撤销/拖动可能重构） | 保语义（长按拖动+50%+实时广播+锁快照），实现可换 |
| 布局四件套 | 低 | 采用官方新版 + 两处小偏离（透明 ripple / 无 ☷） |
