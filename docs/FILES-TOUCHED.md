# 文件 → 补丁 → 功能映射（A01 → 最终修订）

## 已改动文件（补丁覆盖，共 17 个 + 版本文件）

| 文件 | 补丁 | 功能 |
|---|---|---|
| `ui/MainActivity.kt` | 001 | 侧边栏实例缓存/错峰预热/抽屉离屏预热/让路、openGroupAt、状态栏内边距分发、进程恢复 |
| `ui/ToolbarFragment.kt` | 002 | `resetScrollState()` 开放钩子 + onHiddenChanged 触发 |
| `ui/SettingsFragment.kt` | 002 | 滚动复位覆写（Preferences 列表） |
| `ui/RouteFragment.kt` | 002 | 滚动复位覆写 |
| `ui/ToolsFragment.kt` | 002 | 滚动复位覆写（ScrollView） |
| `ui/LogcatFragment.kt` | 002 | 滚动复位 + 日志文本复位 |
| `ui/AboutFragment.kt` | 002 | 滚动复位覆写（NestedScrollView） |
| `ui/GroupFragment.kt` | 003 | scrollToGroup 定位、删除即时同步+幂等护栏、**长按拖动=官方手感（中途零落库零广播）+ suppressSelfEcho 回声双闸 + 固定 50% 边缘速度 + clearView 落库后广播 + 锁内快照防崩溃**、滚动复位 |
| `ui/ConfigurationFragment.kt` | 004 | 官方一次性装载 + 20/20 兜底分片（防一直白屏）、DiffUtil 增量刷新、拖动松手广播就地重排（notifyItemMoved，禁 notifyDataSetChanged）、syncOrderFromDb 顺序兜底、标签栏回中同步、切换路径=官方 |
| `ui/GroupSettingsActivity.kt` | 005 | 返回自动选中新分组 |
| `ui/TopBarController.kt` | 010 | ☴ 列表行 名称·数量（`applyGroupCounts`，灰阶 textColorSecondary，异步 countByGroup） |
| `ui/ThemedActivity.kt` | 006 | 多页面状态栏内边距修复 |
| `res/layout/layout_appbar.xml` | 007 | = 官方原文（elevation 4dp） |
| `res/layout/layout_group_list.xml` | 007 | = 官方原文 + 透明 ripple（唯一偏离） |
| `res/layout/layout_tools.xml` | 007 | = 官方原文（elevation 4dp）+ 透明 ripple |
| `res/layout/layout_group_item.xml` | 008 | 卡片布局净化：不放置拖动手柄图标（长按=唯一拖动入口） |
| `.github/workflows/build_mod.yml` | 009 | 协议 mod 源钉扎 + libgojni 校验 + GOSUMDB=off |
| `nb4a.properties` | 不打包 | 发版时更新 `PRE_VERSION_NAME`（sbNN） |

## 基线自带（A01 已有、补丁不触碰——升级上游随基线走）

| 功能 | 文件 | 锚点符号 |
|---|---|---|
| 规格1 顶栏（粗体 Neko/⊙/☴/等距单排） | `ui/TopBarController.kt` | `setTypeface(BOLD)`、`ic_topbar_*` |
| 规格2 ☴ 快速跳转列表（行结构/跳转/长按编辑） | `ui/TopBarController.kt` | `groups()/jumpTo()`、`rowView.setOnLongClickListener` |
| 规格3 长按分组名跳转 | `ui/TopBarController.kt` | `rowView.setOnLongClickListener` |
| 规格4 [默认\|排序] 连体按钮 | `ui/GroupFragment.kt` | `setupFilterBar()/showFilterPicker()/showSortPicker()` |

> 注：TopBarController 同时被补丁 010 以**纯增量**方式触碰（只加行内数量后缀，
> 不改行结构）；上表所列锚点符号仍属基线原文。

## 不可触碰区域（改这些 = 破坏验收标准）

> 以下均为 `ConfigurationFragment.kt` 内区域，匹配时按符号名搜索。

1. **切换路径全部符号**：`TabLayoutMediator(tabLayout, groupPager) { ... }`（两参构造）、
   `updateSelectedCallback`、`GroupPagerAdapter.reload/groupAdd/groupRemoved/
   groupUpdated/createFragment/getGroupFragment/getItemId/containsItem`、
   `GroupFragment.onResume/onViewCreated`——这些必须逐字保持官方。
2. **禁止出现的符号**（历史废案，出现即删）：
   `prefetchJob`、`prefetchGroupPages`、`warmUpJob`、`startPageWarmUp`、
   `pageWarmStep`、`pageWarmCap`、`isPagerBusy`、`ensureProfilesLoaded`、
   `profilesLoadStarted`、`sharedRecycledViewPool`、`pendingStateRefreshes`、
   `drawerBusy`、`sortButton`、`groupSort`（已移除的拖动手柄资源符号）。
3. **顶部栏三布局**：只许「官方原文 + 透明 ripple」形态；不要恢复
   `bg_group_tab_row` 容器、`bg_tab_indicator_box` 包围框、任何 elevation=0dp。
4. **允许动的小区域**（保留项）：
   `onHiddenChanged`（同步逻辑）、`resetScrollState`（存在本身）、
   `reloadProfiles`（DiffUtil）、mediator 内的长按监听块（调 openGroupAt）、
   GroupPagerAdapter 内的兜底装载块（armFallback/applyFirstFill）、
   `syncOrderFromDb`（顺序兜底）。

## GroupFragment 拖动区红线（规格 8）

- `SimpleCallback(UP or DOWN, START)` + `isLongPressDragEnabled()=true`（官方机制）；
- `interpolateOutOfBoundsScroll` 固定 `maxScroll * 0.5f`（最终规格，勿改渐进/系数）；
- `move()` 末尾无落库（官方原文）+ NO_POSITION 防护；落库/广播只在 `clearView → commitMove()`；
- `commitMove()` = `synchronized(updated)` 快照 → 后台落库 → 广播；
- 长按入口之外不得再引入手柄/按钮触发。

## 依赖关系图

```
MainActivity(001: 缓存/预热/抽屉预热/openGroupAt/内边距)
     ↑ 被依赖
ConfigurationFragment(004: 长按→openGroupAt; onHiddenChanged 同步; 动态加载; syncOrderFromDb)
GroupFragment(003: scrollToGroup 被 openGroupAt 调用; 拖动/删除同步)
     ↑ 联动
TopBarController(010: ☴ 行数量后缀, 独立) | 布局 007 独立 | 008 独立 | 005/006 独立 | 009(CI) 独立 | 002 其余页面 独立
基线自带: TopBarController（规格1-4 主体）、GroupFragment.setupFilterBar（规格4）
```
