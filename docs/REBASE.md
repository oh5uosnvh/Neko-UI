# 上游大更新移植 SOP（给 AI 模型的分步指南）

适用场景：上游（matsuridayo/NekoBoxForAndroid、starifly/NekoBoxForAndroid 或其他
分支）发布了大更新，需要把 A02 的全部改动移植到新版本上。

## 前置输入

- 新上游源码树（git clone 后 checkout 到目标 ref/tag）
- 本仓库（补丁 + 手册）
- 用户的验证标准：**流畅度不回退、官方观感不回退、全部保留项功能可用**

## 核心原则（先读三遍）

1. **分组切换路径必须保持官方原文**。A02 的历史教训：任何加在切换路径上的
   「优化」（预取/预热/让路/瞬时跳转/自定义监听器）都引发过卡顿或生硬问题，
   最终全部移除。移植时只移植 `docs/CHANGES.md` 列出的 8 项，**不要顺手加料**。
2. 每个 A02 改动都有「意图 + 锚点 + 红线」。上游代码变了 = 锚点可能变了 =
   需要在**新代码里找到等价位置**重新实现，而不是机械打补丁。
3. 每完成一项就构建 + 跑 `verify_build.py`，不要攒到最后。

## 步骤

### 第 0 步：建立工作树

```bash
git clone <新上游repo> work && cd work
git checkout <目标ref>
git remote add kit <本kit仓库> && git fetch kit   # 可选：便于取补丁
```

### 第 1 步：预检（哪些补丁还能直接用）

```bash
python3 /path/to/kit/scripts/apply_all.py --check
```

输出会把 8 个补丁分成 `CLEAN（可直接 apply）` 与 `CONFLICT（需要手工移植）`。

### 第 2 步：应用全部 CLEAN 补丁

```bash
python3 /path/to/kit/scripts/apply_all.py --only-clean
```

（或逐个 `git apply patches/NNN-*.patch`，跳过 CONFLICT 的。）

### 第 3 步：逐个手工移植 CONFLICT 补丁

对每个 CONFLICT 的补丁：

1. 打开对应补丁文件，读改动上下文；
2. 打开 `docs/CHANGES.md` 找到该功能的「意图 / 关键符号 / 红线」；
3. 在新上游代码中搜索等价锚点（符号名通常仍在；若上游重命名，按意图定位）；
4. 以**最小侵入**方式重新实现（新代码里加同样的小块，不要重构上游代码）；
5. 红线检查：切换路径是否仍=官方？指示器/无底色是否保留？长按是否保留？

### 第 4 步：静态校验

```bash
grep -rn "openGroupAt\|scrollToGroup\|resetScrollState\|calculateDiff\|collapseSearch" \
  app/src/main/java/io/nekohasekai/sagernet/ui/ | wc -l    # 应 >10
grep -rn "prefetchJob\|warmUpJob\|pageWarmStep\|isPagerBusy\|pendingStateRefreshes" \
  app/src/main/java/ | wc -l                               # 必须 = 0
```

第二个 grep 非零 = 历史废案回流，**立即删除**。

### 第 5 步：构建

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./run init action gradle
./gradlew assemblePreviewDebug
```

（libcore 构建需要 Go 1.24 + NDK r25c + `GH_PAT`；参考
`build/build-from-patches.yml` 的云端流程。）

### 第 6 步：成品校验

```bash
python3 /path/to/kit/scripts/verify_build.py <apk路径>
```

必须全绿：dex 符号（openGroupAt/scrollToGroup/resetScrollState/calculateDiff）
+ libgojni 协议标记（x365/viewTurbo/fastup/oppa-mod/mihomo 伪装）。

### 第 7 步：真机验收清单（人工/AI 通过 ADB 验证）

- [ ] 侧边栏首次点开 6 个页面：无卡顿
- [ ] 配置页左右滑切换分组：流畅、无空白、无阴影线
- [ ] 滑动标签栏后点远处分组：官方呈现方式
- [ ] 点/长按分组名：无底色；长按跳分组界面且定位正确
- [ ] 分组页新建分组 → 返回：标签栏位置正确、无需触摸
- [ ] 分组页左滑删除分组 → 返回：配置页标签立即消失；撤销后返回：标签恢复
- [ ] 设置/日志页来回切换：滚动复位生效
- [ ] 订阅刷新：列表增量更新不闪烁

### 第 8 步：发版

更新 `nb4a.properties` 的 `PRE_VERSION_NAME`（如 A03），commit + tag + Release，
资产命名遵循 `NekoBoxF-<版本>-arm64-v8a.apk`。

## 历史废案档案（防复发）

以下方案均实施过并被证明有缺陷，**禁止以任何形式回归**：

| 废案 | 症状 | 版本 |
|---|---|---|
| 配置页视图创建即查库（预取） | 切换动画期间碎片加载 → 每次切换必卡 | sb4~sb13 |
| offscreenPageLimit 渐进扩满 | 与弹窗动画抢主线程 | sb10~sb13 |
| 自定义 TabLayout 监听器做中心化/瞬时跳转 | 双重滚动冲突 / 生硬硬切 | sb5~sb7 |
| smoothScroll=false 全局瞬时切换 | 相邻切换生硬 | sb12 |
| TabLayout 高程 0dp + 包围式方框 | 标题栏下缘出现阴影线 | A01 原有，sb16 修 |
