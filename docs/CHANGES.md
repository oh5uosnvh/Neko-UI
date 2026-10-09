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
- **锚点**：`groups()` / `jumpTo(index)` / `jumpToLastGroup()` / 弹窗行渲染 / `applyGroupCounts`（补丁 010）。
- **验证**：点 ☴ 弹出分组列表，点击即跳到该分组；每行名称后跟灰阶 `·数量`
  （`365·29` 形态，数量与分组卡片同源 `countByGroup`、同色 textColorSecondary）。

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

- **意图**：切换路径（左右滑 / 顶栏滑到某分组点击 / ☴ 点分组名）的呈现机制 =
  **官方 100% 原文**。装载触发时机同为官方原文：页面 fragment onResume
  （ViewPager2 在滑动 settle 后才把进入页 RESUME → 装载天然发生在滑动动画
  之后，滑动全程零竞争）；`onPageSelected` 零装载逻辑。历史补丁曾在
  onPageSelected + 120ms 防抖里提前装载 = 「上一页卡片未消失、下一页就出卡 +
  中途卡顿」的根因，已删除（settleEnsureJob/loadInFlight/ensureLoadedIfEmpty
  一并清除）；官方 onResume 的渲染态判空（`configurationListView.size == 0` →
  挂 adapter + reload）天然自愈「数据在但不渲染」竞态。**20/20 分片填充只是
  兜底**：`reloadProfiles` 入口武装看门狗（`armFallback`，800ms 复查）——
  官方装载偏慢（部分 70-80 节点分组）或竞态未落地、复查适配器仍空 → 自带
  读库由 `applyFirstFill`（20/20）接管流式补齐；正常装载先落地，看门狗自动
  作废。除此之外零改动。
- **文件**：`ui/ConfigurationFragment.kt`（GroupPagerAdapter 内）
- **关键符号**：`armFallback`（看门狗）、`applyFirstFill`（20/20 兜底填充）、
  `resetScrollState`。
- **验证**：dex 含 `armFallback`/`applyFirstFill`；左右滑 = 官方手感（空白停滞 →
  一次出卡），慢装载分组 800ms 内由兜底接管，大分组不再长时间白屏。
- **红线**：不做 ±1 邻页预取（曾因大分组内存压力卡顿被移除）；填充只在数据为空时触发。
- **拖动松手广播的就地重排**（`groupUpdated` 的 `orderChanged` 分支）：分组页
  松手落库广播后，标签栏/☴ 按 `userOrder` 就地 `notifyItemMoved` 重排并保持
  选中分组——**禁止 notifyDataSetChanged**（FragmentStateAdapter 全量销毁重建
  分组页 = 拖动掉帧元凶）。pager 已用分组 id 作稳定 ID
  （`getItemId`/`containsItem`），页面原位跟随不重建。

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

- **意图**：拖动路径 = **starifly 官方原文逐行对齐**：拖动中途只做
  `notifyItemMoved`（零落库、零广播），松手 `clearView → commitMove()` 统一
  落库——这是官方「首次打开拖动也丝滑」的根本原因。历史两次翻车：
  ①「每次换位 → 落库 → 广播」放拖动中途：广播打进配置页 pager，`orderChanged`
  命中 `notifyDataSetChanged()` → FragmentStateAdapter 把**所有分组页 fragment
  销毁重建**，每换位一轮 → 全程掉帧（首次打开 pager 满载时最重）；
  ② 广播回声 `groupUpdated` 重绑正在拖拽的卡片 → 未过中线提前换位。
  **修复**：中途零工作 + `suppressSelfEcho`/同实例判断双闸吞回声 + 配置页
  就地重排（notifyItemMoved）。官方语义之上只追加一件事：**松手落库后广播
  一轮**（☴ 列表与配置页标签栏需要跟随）。
- **文件**：`ui/GroupFragment.kt`
- **关键符号**：
  - `isLongPressDragEnabled() = true`（官方机制，SimpleCallback(UP|DOWN, START)）；
  - `interpolateOutOfBoundsScroll`：`speed = (maxScroll * 0.5f).toInt().coerceAtLeast(1)`
    （固定值、无插值渐加速；maxScroll = `R.dimen.item_touch_helper_max_drag_scroll_per_frame`）；
  - `onMove`：首行置 `groupAdapter.suppressSelfEcho = true`，再 `move()`；
  - `move()`：官方原文（NO_POSITION 防护 + `notifyItemMoved`），**无任何落库/广播**；
  - `clearView`：`commitMove()`（官方落位点，松手才落库）→ 置 `suppressSelfEcho = false`；
  - `commitMove()`：官方落库 + **追加一轮广播**（配置页标签栏 + ☴ 列表即时跟随）；
    `synchronized(updated)` 锁内取快照——修复快速连拖多卡的
    `ConcurrentModificationException`；
  - adapter `groupUpdated(group)`：
    `if (suppressSelfEcho || groupList[index] === group) return`（拖动中/自身
    回声双闸——同实例无需重绑，否则松手后卡片闪一下）；
  - 配置页侧（004）：`groupUpdated` 的 `orderChanged` 分支 = 按 `userOrder`
    **就地 `notifyItemMoved` 重排**（pager 已用分组 id 作稳定 ID，页面原位跟随），
    **禁止 notifyDataSetChanged**；`syncOrderFromDb()` 兜底保持。
- **验证**：dex 含 `syncOrderFromDb`；长按卡片可拖动；**首次打开分组页拖动
  全程丝滑（与切换后再拖无差别）**；必须拖过相邻卡片中线才换位；拖到边缘
  滚动速度均匀（官方最大速度的 50%）；快速连续拖多卡不崩溃；**松手后**配置页
  标签栏与 ☴ 列表顺序即时生效。
- **红线**：
  - 拖动中途（onMove 路径）**不得出现任何落库、广播、刷新、重载**；
  - 同步点只在 `clearView → commitMove()`（官方语义），广播是官方点之后的
    唯一追加物；
  - 配置页 `orderChanged` 分支**禁止 notifyDataSetChanged**（FragmentStateAdapter
    全量销毁重建分组页），只许就地 `notifyItemMoved`；
  - 边缘速度系数 **0.5f 固定**（勿改渐进插值、勿改其它系数）；
  - `updated` 的增删必须在主线程锁内，后台只碰快照；
  - 拖动保持官方长按触发，不得引入额外触发入口；
  - 撤销删除功能必须保留（见附加保留项 B 的 groupRemoved/groupAdd 广播）。

> 分组界面布局（`008-group-item-longpress-only.patch`）仅一处净化：卡片上不放置
> 任何拖动手柄图标，长按是唯一拖动入口。分组界面的功能改动只有顶栏
> [默认|排序] 连体按钮（规格 4，基线自带）。

---

## 规格第 2 项扩展：☴ 列表行 名称·数量（补丁 `010-group-list-count.patch`）

- **意图**：☴ 快速列表每行分组名后跟灰阶 `·N`（N = `countByGroup`，与分组卡片
  group_status 完全同源；颜色 = 卡片同款 `?android:attr/textColorSecondary`）。
  异步查库：行先展示名称，数量随后补上，不阻塞弹窗打开。
- **文件**：`ui/TopBarController.kt`
- **关键符号**：`applyGroupCounts`（私有方法，verify_build 必需符号）、
  `countTargets`、`SpannableString` + `ForegroundColorSpan(textColorSecondary)`。
- **验证**：dex 含 `applyGroupCounts`；☴ 行显示 `365·29` 形态，「·29」为灰阶；
  点击跳转与长按进分组设置行为不变。
- **红线**：行结构（nameCard/rowView/长按入口）不得改动；数量必须走
  `countByGroup(group.id)`，勿换数据源；灰阶必须用 `textColorSecondary` 主题色，
  不得写死色值。

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

### H. 分组卡片“更新进度条永不收起”修复（补丁 `011-group-update-progress-stuck.patch`）
- **根因（对比官方）**：官方 `GroupAdapter.groupUpdated(group)` 无条件重绑
  （`groupList[index] = group` + `notifyItemChanged(index)`）。003 为消除拖动落库后的
  卡片闪烁，追加了同实例回声去重：`groupList[index] === group` 直接 return。
  而 `startUpdate` 传入的正是分组卡片适配器列表里的**同一个对象**——
  `finishUpdate → postUpdate(proxyGroup)` 的“更新完成”广播因此被当作回声吞掉。
  表现 = 订阅内容已更新（对象字段是就地改的，中途的 tick 广播已把它画上屏），
  但卡片顶部进度条永远转圈、✏️ 按钮一直隐藏。官方源码无此问题（无去重分支）。
- **修复**：`GroupUpdater.finishUpdate` 改为 `GroupManager.postUpdate(proxyGroup.id)`——
  从数据库重取最新持久化对象广播。完成通知不再是同一实例，重绑必然发生；
  003 的拖动去重语义完全不变。
- **验证**：点卡片更新 → 订阅拉取完成 → 进度条立即收起、✏️ 恢复；
  拖动排序松手依旧无闪烁；自动更新（SubscriptionUpdater）同样走此路径一并修复。
- **红线**：`groupUpdated(group)` 的回声去重必须保留；不得把 finishUpdate 改回
  `postUpdate(proxyGroup)`（同实例广播）；003 的拖动去重与 011 的完成通知互不覆盖。

### I. 连接测试实时回显 + 取消零空档（补丁 `012-connection-test-live-results.patch`）
- **根因**：测试过程中 `TestDialog.update()` 只把结果塞进 `results` 集合并刷新弹窗，
  **不写库、不通知列表**；所有延迟都等全部测完/取消后由 `test.cancel` 串行
  `ProfileManager.updateProfile` 逐条回写（180 节点 = 180 次写库+广播 + 整页重载，
  观察为 2~3 秒滞后）。`DataStore.runningTest = false` 又排在这串回写**之后**，
  于是回写期间点 TCPing/URL Test 被 `runningTest` 静默挡住 = 空档期。
- **修复**：① `TestDialog` 新增 `scheduleFlush()`/`flushPending()`——每有结果即调度
  250ms 防抖批量回写（`AtomicBoolean` 防重入 + `resultsLock` 快照），行延迟经
  `onUpdated` 定点刷新，测试过程中列表实时变化；② 两个 `test.cancel` 均改为
  **第一行先 `runningTest = false`**，取消/兜底回写/整页同步全部后台收尾，空档期≈0。
- **验证**：TCPing/URL Test 过程中各节点延迟陆续实时刷新（≤0.3s/节点）；
  中途取消立即出结果；测完或取消后马上可再次发起测试（<0.5s）。
  进行中的 `urlTest` 实例最多再跑自身超时（默认 3s）后自行关闭，不阻塞新测试。
- **红线**：`flushPending()` 必须保持「同步取走快照、批量一次落库」语义；
  `runningTest = false` 必须在 `runOnDefaultDispatcher` **之前**执行。

### J. 连接测试提速 + 取消即停（补丁 `013-connection-test-cancel-and-speed.patch`）
- **根因**：`TestInstance.doTest` 把 `init/launch/Libcore.urlTest` 整条阻塞链跑在
  `Dispatchers.Default`——每个在测实例钉死一个 Default 线程直到 HTTP 完成/超时，
  有效并发恒等于 CPU 核数（8），`connectionTestConcurrent` 形同虚设；取消测试后
  `use{}` 块挂在 GlobalScope 不可取消，僵尸实例霸占全部 Default 线程跑满 3s 超时
  = “取消后重测 2~3 秒空档”。（012 只修了写库滞后，没动阻塞链，故未根治。）
- **修复**：`suspendCancellableCoroutine + invokeOnCancellation { closeOnce() }`
  （取消即关实例，Go 侧秒回错误）；阻塞段 `withContext` 进每轮测试专用的
  `newFixedThreadPool(concurrent.coerceIn(1,16))`，两条收尾路径都关池；
  闭池竞态由 catch 干净失败。真实 HTTP 延迟测量路径零改动。
- **验证**：取消后重测空档 ≪0.5s；并发 25 下吞吐 ≈2~3 倍；`Assert 013` 通过。
- **红线**：不得改回 `Dispatchers.Default`；`testPool` 只在 `test.cancel` 关闭；
  `closeOnce` 幂等；`Libcore.urlTest` 参数（link/timeout）不动。

### K. 清理不可用/去重接入“还原”snackbar（补丁 `014-delete-unavailable-undo.patch`）
- **根因**：官方这两处批量删除从未接 undoManager（裸删：手动移卡 + 立即删库），
  卡片删了就没了。
- **修复**：与滑动删除同契约——`adapter.remove(index)` 即时移卡 →
  `undoManager.remove(index to profile)` 出“已删除 N 项|还原” → snackbar 消失后
  才 `commit()` 真删库；还原按原位插回。被过滤隐藏的节点维持立即删除。
  `GroupFragment.isUndoReady()` 兜底 select 模式。
- **验证**：清理不可用/去重 → 还原后卡片与库完整；snackbar 消失后库已删。
- **红线**：`undoManager.remove(visible)` 入参必须是 (原始index, profile)；
  不得先删库再提示。

### L. 设置页菜单高频点击 NPE 根治（补丁 `015-settings-menu-npe-guard.patch`）
- **根因**：Group/Profile/Route 三个设置页 `val child by lazy { … as
  MyPreferenceFragmentCompat }` 非空强转，而 fragment 提交双重异步（DB 协程 +
  `commit()`）；主线程被测试回写/整页重载压住时手速快于 attach = 真机 fatal
  （日志 4 次全此模式）。
- **修复**：`child` 改空安全只读属性（`as?`），`onOptionsItemSelected` 改
  `child?.onOptionsItemSelected(item) ?: super…`——未就绪时安全落空不崩溃。
- **验证**：进设置页秒点菜单不崩；“取消测试→跳分组→新建”复现路径无 fatal。
- **红线**：不得改回 `by lazy` 非空强转。

### M. 偏好编辑弹窗统一（补丁 `016-preference-edit-unify.patch`）
- **根因**：ProfileSettingsActivity 把 PasswordSummaryProvider 类偏好分流到
  PasswordDialogFragment（裸显示、只读、无清空、无输入态）；标准
  CopyableEditTextPreferenceDialog 本身就支持明文+清空+输入态。
- **修复**：删除分流分支与 PasswordDialogFragment 死代码，密码/UUID 与其他
  参数走同一编辑弹窗（取消/清空/复制/保存 + 自动弹键盘）。
- **验证**：点 用户ID/密码 → 出现与“服务器”同款输入态弹窗，含清空；列表圆点摘要不变。
- **红线**：不得恢复按 summaryProvider 分流的弹窗路径。

### N. 底部长条启动栏 + 出站 IP 查询（补丁 `017-bottom-connect-bar.patch`）
- **改动**：layout_main 移除 fabProgress/fab/stats（官方 FAB + 实时数据托板），
  新增常驻 ConnectBar（64dp 圆角长条，左右 2dp 与卡片对齐）：
  ⓘ出站IP查询 \| 上传/下载两行 \| 实时延迟两行(点按=主连接 urlTest，ProfileManager
  实时回写联动) \| 启动按钮(ServiceButton 复用)。StatsBar/FabProgressBehavior
  源码删除；showBottomBar 设置项移除；配置列表预留 72dp+inset 滚动余量
  （滚动到底时末卡片与栏间距=卡片间距）。
- **出站 IP**：复刻 FlClash v0.8.99（ip_quality.dart）字段映射——
  ip-api.com 单源：等级(hosting→普通/其余→优质)、类型(机房/移动/住宅)、
  命中标记(proxy→代理)、组织(org?:isp)、ASN(as 前缀)、来源 ip-api.com；
  查询走 VPN 隧道（应用流量经 TUN）；弹窗=基础信息+网络 两区块，底部 刷新/确定。
- **验证**：启动/关闭/状态动画正常；速度两行实时；延迟区点按出主连接 RTT，
  列表测试时选中节点延迟实时上屏；ⓘ 弹窗刷新/确定可用。
- **红线**：ConnectBar 常驻、不得恢复 hideOnScroll；ServiceButton 状态动画
  （iconConnecting 的延迟进度环）不得动；IP 查询不得改用与 FlClash 不同的字段语义。

### O. 分组标签长按三选菜单 + 删除带还原（补丁 `018-group-tab-menu-undo.patch`）
- **改动**：配置页顶栏分组标签长按由“直接跳转”改为 PopupMenu：编辑(进分组设置)/
  跳转(原 openGroupAt)/删除(组+节点入库前快照 → 删除 → “已删除分组 %s \| 还原”
  snackbar；还原按原 id 回插组与节点，当前组被删时先切到首个剩余组，还原时回选)。
- **验证**：长按弹菜单三项各就各位；删除当前分组后列表正常落位；还原后组、节点、
  id 原样回归。
- **红线**：还原必须走“快照 + 原 id 回插”，不得新建分组（id 会变）；undo 依赖
  snackbar 存活期，超时后不可恢复属预期。

### P. 启动栏抛光与延迟接线（补丁 `019-connect-bar-polish.patch`）
- **根因（延迟卡“测试中…”）**：ConnectBar.onTestConnection 无人接线——点击只改文案。
- **修复**：MainActivity 接线（主连接 urlTest → showDelay/showDelayError）；延迟区
  内容宽（ripple 不再占半条栏）；出站 IP 弹窗加国家地区行、IP/组织/ASN 三行点击复制。
- **红线**：延迟区点击必须走主连接 urlTest；复制仅限这三行。

### Q. 分组删除还原修复（补丁 `020-group-undo-fix.patch`）
- **根因**：GroupPagerAdapter.groupUpdated(groupId) 是空实现，postReload 不刷新标签条。
- **修复**：改发 groupAdd 让 pager 把组加回（022 再升级为原位插回）。
- **红线**：不得依赖 postReload 同步 pager 的组列表。

### R. 启动栏二抛（补丁 `023-connect-bar-fit3.patch`）
- **改动**：左右留白/块间距微调、延迟区固定宽（文案切换不再左右晃）、飞机垂直居中
  （FrameLayout 内默认顶对齐是根因）、三处触摸底色统一 bg_touch_rounded（圆角矩形）。
- **红线**：触摸底色必须是圆角矩形 ripple，不得回退 borderless 圆形/直角长条。

### S. 启动按钮官方动效回归 + 四区重构（补丁 `024-service-icon-anim.patch`）
- **根因**：021 的静态图标丢了官方 AVD 动画（斜线直接出现/消失）；FAB 自绘底色
  与进度环“转圈”是源码遗留显示问题。
- **修复**：ServiceIconView = 官方动画队列引擎（AnimatedState/计数器切换/动画回调）
  移植到 AppCompatImageView——保留斜线划出/飞机形变动效，删除 FAB 底色与进度环；
  初始态 animate=false 静态首帧（与官方调用语义一致）。四区 [ⓘ|速度|弹性|延迟|启动]
  均匀分布。
- **红线**：动画队列计数逻辑不得简化；进度环不得回归；bar_fab 必须是
  ServiceIconView（FAB 自绘底色与栏底色有色差）。

### T. 出站 IP 六源对冲（补丁 `025-ip-quality-multisource.patch`）
- **根因（与 FlClash 结果不一致）**：017 只用 ip-api.com（宽松），FlClash 0.8.99
  是六源对冲，命中源带 is_datacenter/is_abuser 等严格字段。
- **修复**：IpQualityLookup 逐行复刻 FlClash（六源并发、非推断先到先得、推断兜底、
  总超时 8s）；字段映射/等级（优/普通/风险+颜色）/命中标记（Tor/滥用记录/VPN/代理，
  顿号连接）/类型（机房/移动网络/住宅/商业）全对齐；国家地区各源尽力提供。
- **红线**：源列表与 _pickType/_declaredType/_asn 逻辑必须与 FlClash v0.8.99
  ip_quality.dart 一致；等级色 risky=红/good=绿/normal=默认。

### U. 连接即预查询出站 IP + 栏宽收敛（补丁 `026-connect-bar-fit4.patch`）
- **根因**：025 只在点开 ⓘ 弹窗时才查询（首开必等）；栏宽含弹性空隙（`layout_weight`），
  视觉过宽不居中。
- **修复**：VPN 连接成功即 `IpQualityLookup.prefetch(DataStore.currentProfile)`
  （点开弹窗秒出）；失败保留上次结果；国家地区跨源合并（ident.me 命中也能出国家）；
  栏宽收窄为内容宽并居中（移除弹性空隙）。
- **红线**：prefetch 必须携带当前节点（028 升级为 profileId 绑定）；弹性空隙不得回归
  （`layout_weight="1"` 禁止出现在 layout_connect_bar.xml）。

### V. 启动栏三抛（补丁 `027-connect-bar-fit5.patch`）
- **根因**：速度区宽度随网速字符抖动；未连接时延迟区空白无引导；连接成功不自动测延迟。
- **修复**：速度区固定 `88dp` 冗余宽（框长恒定）；未连接延迟占位「… ms」（四位余量）；
  连接成功自动 ping 一次（`connectBar.showDelay(elapsed)` 手动+自动共 2 处）；
  左右留白对称（ⓘ 去内边距/飞机去 margin/栏内边距 10dp 对称）。
- **红线**：88dp 速度框不得改窄；「… ms」占位不得回退成「点击测试」；
  自动 ping 的 500ms 稳定期不得删除（配合 029）。

### W. IP 查询三修（补丁 `028-ip-cache-profile-bound.patch`）
- **根因**：026 后六源 select 按发起序等待（等齐最慢源，全源都变慢）；
  缓存不绑节点（切节点重连读到旧节点 IP）；刷新期间弹窗空白无反馈。
- **修复**：select 回归按完成序（`select<Done>` 先答先赢）；缓存绑定节点 profileId
  （`lookup(profileId)` / `cacheFor(profileId)`，切节点重连自动重查）；
  打开弹窗先显示「查询中」（`setLoadingState()`），失败回滚同节点缓存；
  `COUNTRY_GRACE_MS` 国家跨源合并宽限期。
- **红线**：`lookup(profileId)` 签名不得回退为无参（025/028 的 CI 断言绑定此签名）；
  先答先赢语义不得改回按发起序。

### X. urlTest 冷启动重试（补丁 `029-urltest-coldstart-retry.patch`）
- **根因**：x365 魔改协议冷启动首连（DNS+TLS+传输流+魔改握手）超 3s 预算必败
  ——libneko speedtest 无重试，首次测延迟必显示失败。
- **修复**：urlTest 冷启动失败 700ms 后自动重试一次（锚点 `urlTest cold-start retry`）；
  连接成功的自动 ping 前置 500ms 稳定期（锚点 `delay(500)`）。
- **红线**：重试仅一次（防雪崩）；3s 预算 / 700ms 退避 / 500ms 稳定期不得随手调整。

---

## 移植到新上游时的冲突热区（按历史经验排序）

1. `ConfigurationFragment.kt` —— 改动最频繁。策略：先保证切换路径=官方原文，
   再按 CHANGES 锚点逐个移植保留项与动态加载。
2. `MainActivity.kt` —— 若上游也改了页面管理（displayFragment 体系），缓存方案需
   重新对齐 `displayFragmentWithId/displayFragment/restoreFragments`。
3. `GroupFragment.kt` —— 若上游改了删除/撤销/拖动体系，保留「长按拖动 + 固定
   50% 边缘速度 + 松手落库广播 + 锁内快照」语义即可，实现可换。
4. 布局四件套 —— 直接采用官方新版，再叠透明 ripple / 卡片布局净化两处小偏离。
