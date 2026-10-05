# 全部改动清单（A01 → 最终修订）

> 结构 = 按「成品功能规格（8 项）」组织 + 附加保留项 + 移植冲突热区。
> 每项：**意图**（为什么）/ **文件** / **关键符号**（移植锚点）/ **验证标记** / **红线**。
> 补丁文件名对应 `patches/` 下同名 `.patch`。

---

## 0. 规格第 1-4 项：基线自带（A01 已含，补丁零涉及）

> 这四项是 fork 原生功能。**升级上游时它们随基线走，不需要移植**；
> 只有当新上游缺失/重构了这些文件时，才按下述锚点找回。

### 规格1 配置页顶栏
- **文件**：`ui/TopBarController.kt`（基线原文）
- **锚点**：`setTypeface(Typeface.DEFAULT, Typeface.BOLD)`（Neko 标题粗体）；
  自绘矢量 `ic_topbar_drawer/jump/group/search/add/more`；等权 `Space` 单排布局。
- **验证**：顶栏一排 ☰ Neko ⊙ ☴ 🔍 📄➕ ⋮，间隔一致；标题与 ⊙ 为粗体形态。

### 规格2 ☴ 点击列表
- **锚点**：`groups()` / `jumpTo(index)` / `jumpToLastGroup()` / 弹窗行渲染。
- **验证**：点 ☴ 弹出分组列表，点击即跳到该分组。

### 规格3 长按分组名
- **锚点**：弹窗行 `rowView.setOnLongClickListener` → `openGroupAt(group.id)`
  （MainActivity）→ `GroupFragment.scrollToGroup(groupId)`（补丁 003 提供定位）。
- **验证**：长按某个分组名 → 切到分组管理页并滚动定位到该分组卡片。

### 规格4 [默认|排序] 连体按钮
- **文件**：`ui/GroupFragment.kt`（基线原文）
- **锚点**：`setupFilterBar()` / `showFilterPicker()` / `showSortPicker()` /
  `topbarSegmentBackground()`（双 cell + 中缝 divider）。
- **验证**：分组页顶栏「分组」右侧有连体按钮，分别弹出过滤/排序选择器。

---

## 规格第 5 项：分组卡片动态加载（补丁 `004-config-page.patch`）

- **意图**：配置页首次进入/切到大分组时首屏不空白：数据级空检查兜底 + 首屏
  渐进渲染（每帧 30 条分片填充，数据随后整体缓存），杜绝官方「进大分组白屏片刻」。
- **文件**：`ui/ConfigurationFragment.kt`（GroupPagerAdapter 内）
- **关键符号**：`ensureLoadedIfEmpty`、`applyFirstFill`、`loadInFlight`
  （AtomicBoolean 单飞护栏）、`resetScrollState`。
- **验证**：dex 含 `ensureLoadedIfEmpty`/`applyFirstFill`；进入 500+ 节点分组首屏立即可见。
- **红线**：不做 ±1 邻页预取（曾因大分组内存压力卡顿被移除）；填充只在数据为空时触发。

---

## 规格第 6 项：应用整体流畅度（补丁 001 / 004 / 006 / 007）

- **意图**：把所有非切换路径的开销挪出用户操作时刻：
  - **错峰预热**（001）：启动后按 300ms 起步、250ms 步进，把其余侧边栏页面在
    屏幕外「微露」80ms 强制完成首次 measure/layout/draw——首次点开=热切换；
  - **让路规则**（001）：抽屉打开或窗口失焦（弹窗/菜单/对话框）时预热自动暂停，
    关闭后从断点继续——用户操作永远优先；
  - **DiffUtil 增量刷新**（004）：订阅更新只重绘差异项，不再整列表闪烁；
  - **insets 修复**（006）：多页面共存时每个页面补一次状态栏内边距，顶栏不重叠；
  - **官方观感**（007）：标签栏/标题栏 elevation 与官方一致，无阴影线。
- **关键符号**：`PREWARM_DELAY_MS=300`、`PREWARM_INTERVAL_MS=250`、
  `WARM_FRAME_MS=80`、`prewarmRunnable`、`warmUpHiddenFragment`、`calculateDiff`、
  `applyStatusBarInset`。
- **验证**：冷启动后逐个点开每个侧边栏页面无首次卡顿；订阅更新时列表不闪。
- **红线**：预热让路条件（`drawerLayout.isOpen` / 窗口失焦）必须保留；
  `WARM_FRAME_MS/INTERVAL` 勿回调大值。

---

## 规格第 7 项：侧边栏卡顿修复（补丁 `001-sidebar-cache-prewarm.patch`）

- **意图**：官方每次点侧边栏菜单 = new Fragment + replace()，事务恰好在抽屉
  关闭动画期间执行 → 必卡。修复 = 页面实例缓存常驻（hide/show 切换）+ 启动
  错峰预热 + **☰ 抽屉内容离屏预热**（`drawerWarmRunnable`：关闭状态的抽屉从未
  绘制过，首次打开要现画整棵菜单树——用 `buildLayer()` 离屏构建一次绘制列表）。
- **文件**：`ui/MainActivity.kt`
- **关键符号**：`fragmentCache`、`CACHED_NAV_IDS`、`restoreFragments`、
  `displayFragmentWithId/displayFragment`、`prewarmRunnable`、`drawerWarmRunnable`、
  `openGroupAt`（跳分组页+定位，配合规格 3）、`STATE_NAV_ID`（进程恢复）。
- **验证**：dex 含 `fragmentCache`/`drawerWarmRunnable`/`openGroupAt`；
  打开 App 立刻点 ☰ 无明显卡顿；每个菜单页首次点开流畅。
- **红线**：缓存页面必须处理 `onHiddenChanged`（见补丁 002 链）；仪表盘（WebView 页）
  保持官方「每次重建」语义，不进缓存。

---

## 规格第 8 项：分组卡片长按拖动排序（补丁 `003-groups-page-drag.patch`）

- **意图**：分组管理页卡片拖动排序为基线官方机制（长按卡片触发，SimpleCallback
  UP|DOWN）。补丁仅两处增强：拖到列表上下边缘的自动滚动**固定为最大速度的
  50%**（非渐进加速）；每次换位**立即落库并广播**（配置页标签栏与 ☴ 列表实时
  跟随，拖完立刻返回必然生效），并做并发写防护（快速连拖多卡不崩）。
- **文件**：`ui/GroupFragment.kt`
- **关键符号**：
  - `isLongPressDragEnabled() = true`（官方机制，SimpleCallback(UP|DOWN, START)）；
  - `interpolateOutOfBoundsScroll`：`speed = (maxScroll * 0.5f).toInt().coerceAtLeast(1)`
    （固定值、无插值渐加速；maxScroll = `R.dimen.item_touch_helper_max_drag_scroll_per_frame`）；
  - `move()`：每次换位末尾调用 `commitMove()`（实时同步）+ NO_POSITION/越界防护；
  - `commitMove()`：`synchronized(updated)` 锁内取快照后清空，后台只遍历快照——
    修复快速连拖多卡的 `ConcurrentModificationException`；落库后
    `GroupManager.iterator { groupUpdated(group) }` 广播；
  - 配置页侧兜底：`syncOrderFromDb()`（004）——每次配置页显示时按数据库
    userOrder 就地对齐一次（广播竞态保险丝，只在集合一致、仅顺序不同时动作）。
- **验证**：dex 含 `syncOrderFromDb`；长按卡片可拖动；拖到边缘滚动速度均匀
  （约为官方最大滚动速度的一半）；快速连续拖多张卡片不崩溃；拖完立刻返回
  配置页顺序已生效。
- **红线**：
  - 边缘速度系数 **0.5f 固定**（勿改渐进插值、勿改其它系数）；
  - `updated` 的增删必须在主线程锁内，后台只碰快照；
  - 拖动保持官方长按触发，不得引入额外触发入口；
  - 撤销删除功能必须保留（见附加保留项 B 的 groupRemoved/groupAdd 广播）。

> 分组界面布局（`008-group-item-longpress-only.patch`）仅一处净化：卡片上不放置
> 任何拖动手柄图标，长按是唯一拖动入口。分组界面的功能改动只有顶栏
> [默认|排序] 连体按钮（规格 4，基线自带）。

---

## 附加保留项（规格外的既有修复，一并随补丁携带）

### A. 各页滚动复位（补丁 `002-page-scroll-reset.patch`）
- **意图**：页面被侧边栏切走（`onHiddenChanged(true)`）时复位列表滚动，
  返回时从顶部开始；Logcat 额外复位日志文本；配置页隐藏时收起搜索栏。
- **符号**：`ToolbarFragment.resetScrollState()`（open 钩子）+ 各子类覆写。
- **红线**：复位用 `scrollToPosition`（pending 语义）；该钩子**不得**用于刷新/重载。

### B. 分组删除即时同步 + 幂等护栏（补丁 `003` 内）
- **意图**：左滑删除分组**立即**广播 `groupRemoved`（官方等撤销条 2 秒超时）；
  点撤销广播 `groupAdd` 恢复；正式 commit 广播幂等。
- **符号**：`onSwiped` 内 `GroupManager.iterator { groupRemoved(group.id) }`、
  `undo` 内 `groupAdd` 广播、`groupAdd` 监听器首行
  `if (groupList.any { it.id == group.id }) return`（防撤销恢复时重复添加）。
- **红线**：撤销功能与幂等护栏必须保留。

### C. 分组设置返回自动选中（补丁 `005-group-settings-autoselect.patch`，2 行）
- **符号**：`GroupSettingsActivity` 返回前写 `DataStore.selectedGroup`。

### D. 多页面状态栏内边距（补丁 `006-insets-fix.patch`）
- **符号**：`ThemedActivity.applyStatusBarInset`（配合 001 的 FragmentLifecycleCallbacks）。

### E. 标签栏官方化（补丁 `007-tab-strip-official-ui.patch`，3 个布局）
- `layout_group_list.xml` = 官方原文 + **唯一偏离** `tabRippleColor` 透明；
  `layout_appbar.xml`/`layout_tools.xml` = 官方原文（elevation 4dp）。
- **红线**：白色圆角短线指示器保留；勿恢复旧的深色圆角容器/包围式方框。

### F. 分组卡片布局净化（补丁 `008-group-item-longpress-only.patch`）
- 卡片上不放置任何拖动手柄图标；长按卡片是唯一拖动入口（拖动本身=基线官方机制）。

### G. CI 协议 mod 源钉扎 + 构建环境修复（补丁 `009-ci-mod-pins-gosumdb.patch`）
- anytls(shanlian)/vt/fastup/oppa 四个协议 mod 源钉到指定 commit（secrets.GH_PAT）；
  libgojni.so 校验 `x365`/`viewTurbo`/`fastup`/`oppa-mod`/`mihomo/1.19.25` 标记；
  `GOSUMDB=off`（sum.golang.org 对 runner 不稳定，本地 go.sum 哈希校验仍生效）。
- **移植注意**：新 CI 必须继承钉扎、校验与 GOSUMDB 三件套
  （参考 `build/build-from-patches.yml`）。

---

## 移植到新上游时的冲突热区（按历史经验排序）

1. `ConfigurationFragment.kt` —— 改动最频繁。策略：先保证切换路径=官方原文，
   再按 CHANGES 锚点逐个移植保留项与动态加载。
2. `MainActivity.kt` —— 若上游也改了页面管理（displayFragment 体系），缓存方案需
   重新对齐 `displayFragmentWithId/displayFragment/restoreFragments`。
3. `GroupFragment.kt` —— 若上游改了删除/撤销/拖动体系，保留「长按拖动 + 固定
   50% 边缘速度 + 实时广播 + 锁内快照」语义即可，实现可换。
4. 布局四件套 —— 直接采用官方新版，再叠透明 ripple / 卡片布局净化两处小偏离。
