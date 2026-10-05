# A02 全部改动清单（A01 → A02）

> 每个功能：**意图**（为什么改）/ **文件** / **关键符号**（移植时的锚点）/
> **验证标记**（构建后如何确认）/ **红线**（不可破坏的行为）。

---

## 1. 侧边栏页面缓存 + 启动预热
补丁：`patches/001-sidebar-cache-prewarm.patch`（MainActivity.kt）

- **意图**：侧边栏 6 个页面只创建一次并常驻（hide/show 切换），启动后错峰预热
  其余页面（强制 measure/layout + 屏幕外可见数帧），使「第一次点开」也是热切换。
- **关键符号**：`fragmentCache`、`CACHED_NAV_IDS`、`prewarmRunnable`、
  `prewarmIds`、`warmUpHiddenFragment`、`PREWARM_DELAY_MS=800`、
  `PREWARM_INTERVAL_MS=500`、`WARM_FRAME_MS=120`、`restoreFragments`、
  `displayFragmentWithId/displayFragment`、`openGroupAt`（跳分组页+定位）。
- **行为红线**：
  - 预热任务在 `drawerLayout.isOpen` 或窗口失焦（弹窗/菜单/对话框打开）时必须让路；
  - 预热间隔 500ms（勿回调回 300ms，会与启动期用户操作抢主线程）。
- **验证**：dex 含 `prewarmRunnable`、`openGroupAt`；启动后连开每页无首次卡顿。

## 2. 各页面滚动复位 + 搜索收起（问题1）
补丁：`patches/002-page-scroll-reset.patch`（ToolbarFragment/Settings/Route/Tools/Logcat/About）

- **意图**：页面被侧边栏切走（`onHiddenChanged(true)`）时复位列表滚动位置，
  返回时从顶部开始；Logcat 额外做日志复位；配置页搜索栏随页面切换收起。
- **关键符号**：`ToolbarFragment.resetScrollState()`（open 钩子）+
  各子类覆写；`ConfigurationFragment` 的 `onHiddenChanged`（collapseSearch）。
- **红线**：复位用 `scrollToPosition`（pending 语义，不产生可见跳变）；
  该钩子**不得**用于任何刷新/重载（切走瞬间主线程必须空闲）。
- **验证**：dex 含 `resetScrollState`；侧边栏来回切换后各页从顶部开始。

## 3. 分组页增强（问题：定位 / 删除同步 / 滚动复位）
补丁：`patches/003-groups-page.patch`（GroupFragment.kt）

- **意图**：
  a) `scrollToGroup(groupId)`：长按顶部栏分组名 → 跳到分组管理页并定位到该分组
     （替代官方一次性 `arguments` 方案——页面实例被缓存复用后 arguments 只生效一次）；
  b) 左滑删除分组**立即同步**配置页：滑动即广播 `groupRemoved`（原版要等撤销条
     ~2 秒超时后才广播）；点撤销广播 `groupAdd` 恢复标签；正式 commit 广播幂等；
  c) `resetScrollState` 覆写。
- **关键符号**：`scrollToGroup`、`pendingScrollGroupId`、`consumePendingScroll`、
  `onSwiped` 内 `GroupManager.iterator { groupRemoved(group.id) }`、
  `undo` 内 `GroupManager.iterator { groupAdd(item) }`、
  `groupAdd` 监听器首行 `if (groupList.any { it.id == group.id }) return`（幂等护栏，
   防撤销恢复时本地重复添加 + 重复触发订阅更新）。
- **红线**：撤销功能必须保留；护栏必须保留（否则撤销会造成分组页列表重复项）。
- **验证**：dex 含 `scrollToGroup`；删除分组后配置页标签立即消失；撤销后立即恢复。

## 4. 配置页保留项（其余=官方基线）
补丁：`patches/004-config-page-keepers.patch`（ConfigurationFragment.kt）

**总体状态：分组切换路径（左右滑/点标签/滑动标签栏后点选）= 官方 A01 原文，零自定义。**
仅保留以下四项（全部与切换动画无关）：

- a) `onHiddenChanged`：隐藏时 `collapseSearch()`；显示时把顶部标签栏同步到当前
    选中分组（修复：分组页新建分组后返回，标签栏停在旧位置、需触摸才纠正）。
    符号：`onHiddenChanged` 内 `tabLayout.setScrollPosition` 双层 post（照搬
    reload() 既有同步写法）。
- b) `resetScrollState()` 覆写：切走时当前分组列表回顶部（问题1-B 链）。
- c) `reloadProfiles` 使用 DiffUtil 增量刷新（订阅更新不再整体闪烁重绘）；
    首次加载滚动到顶部；select 模式定位到已选节点。符号：`calculateDiff`。
- d) mediator 长按入口改为 `openGroupAt(group.id)`（配合 1/3 的缓存方案）。
- **红线（最高优先级）**：
  - 不要添加任何「预取 / 预热 / 让路 / offscreenPageLimit 调整」——A02 曾引入
    导致每次切换必卡，sb14 已全量移除（符号 `prefetchJob`/`warmUpJob`/
    `pageWarmStep`/`isPagerBusy` 不得再次出现）；
  - `TabLayoutMediator` 保持官方两参构造（smoothScroll 默认值）；
  - `onResume` / `groupAdd` / `groupRemoved` / `updateSelectedCallback` = 官方原文。

## 5. 分组设置返回自动选中（问题3）
补丁：`patches/005-group-settings-autoselect.patch`（GroupSettingsActivity.kt, 2 行）

- **意图**：新建/编辑分组返回配置页后自动选中该分组。
- **符号**：写入 `DataStore.selectedGroup`（配合官方 `onPreferenceDataStoreChanged`
  的 PROFILE_GROUP 处理路径）。

## 6. 多页面状态栏内边距修复
补丁：`patches/006-insets-fix.patch`（ThemedActivity.kt）

- **意图**：页面缓存后多页面共存，每个页面视图创建时需补一次状态栏内边距，
  否则部分页面顶栏与状态栏重叠。
- **符号**：`applyStatusBarInset`、`FragmentManager.FragmentLifecycleCallbacks`
  （`navInsetCallbacks`）。

## 7. 顶部标签栏官方化（视觉）
补丁：`patches/007-tab-strip-official-ui.patch`（3 个布局）

- **意图**：标签栏视觉与官方上游 master 完全一致：
  - `layout_group_list.xml` = 官方原文（扁平标签栏、`tabIndicatorFullWidth=false`
    白色圆角短线指示器），**唯一偏离**：`tabRippleColor` 透明（点按/长按无底色）；
  - `layout_appbar.xml` = 官方原文（`android:elevation="4dp"`——与标签栏 4dp 同高，
    否则标签栏投影会在标题栏下缘形成一条阴影线）；
  - `layout_tools.xml` = 官方原文（elevation 4dp）+ 同款透明 ripple。
- **红线**：白色圆角短线指示器必须保留；无底色必须保留；不要恢复 fork 旧的
  `bg_group_tab_row` 深色圆角容器 + `bg_tab_indicator_box` 包围式方框。
- **验证**：配置页/工具页标题栏与标签栏之间无阴影线；选中分组下方有白色圆角短线。

## 8. CI：协议 mod 源钉扎
补丁：`patches/008-ci-mod-pins.patch`（build_mod.yml）

- **意图**：构建时把 anytls(shanlian)/vt/fastup/oppa 四个协议 mod 源钉到指定
  commit（经 secrets.GH_PAT 拉取私有仓库），并在 libgojni.so 里校验
  `x365`/`viewTurbo`/`fastup`/`oppa-mod`/`mihomo/1.19.25` 标记，缺失即构建失败。
- **移植注意**：新 CI 必须继承此钉扎与校验逻辑（见 `build/build-from-patches.yml`）。

---

## 移植到新上游时的冲突热区（按历史经验排序）

1. `ConfigurationFragment.kt` —— 本会话改动最频繁的文件，reload()/回调/内嵌类
   结构可能被上游重构。策略：先保证切换路径=官方原文，再把 4 个保留项按
   CHANGES.md 锚点逐个移植。
2. `MainActivity.kt` —— 若上游也改了页面管理（displayFragment 体系），
   缓存方案需要重新对齐 `displayFragmentWithId/displayFragment/restoreFragments`。
3. `GroupFragment.kt` —— 若上游改了删除/撤销体系，保留「滑动即广播 + 幂等护栏」
   语义即可，实现可换。
4. 布局三件套 —— 直接采用官方新版，再叠 1 行 ripple 偏离。
