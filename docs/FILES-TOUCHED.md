# 文件 → 补丁 → 功能映射（A01 → 最终修订）

## 已改动文件（补丁 001–029 覆盖）

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
| `group/GroupUpdater.kt` | 011 | finishUpdate 广播数据库最新对象——修复“更新完成但卡片进度条永不收起”（003 同实例回声去重吞掉了完成通知） |
| `ui/ConfigurationFragment.kt` | 012 | TestDialog 防抖批量实时回写（250ms）+ 两处 cancel 先放行 runningTest——修复延迟 2~3 秒滞后与测试空档期 |
| `bg/proto/TestInstance.kt` | 013 | suspendCancellableCoroutine + invokeOnCancellation 即时关实例（僵尸秒死）；阻塞段 withContext 进专用池 |
| `bg/proto/UrlTest.kt` | 013 | 接收每轮专用阻塞线程池，不再占用 Dispatchers.Default |
| `ui/ConfigurationFragment.kt` | 013 | urlTest 创建 newFixedThreadPool(concurrent∈[1,16])；test.cancel 关池 |
| `ui/ConfigurationFragment.kt` | 014 | 清理不可用/去重接 undoManager“还原”snackbar（可见卡可还原，隐藏卡立即删）+ isUndoReady 兜底 |
| `ui/GroupSettingsActivity.kt` | 015 | child 空安全（as?）+ 菜单事件护栏——修高频点击 NPE fatal |
| `ui/profile/ProfileSettingsActivity.kt` | 015 | 同上 |
| `ui/RouteSettingsActivity.kt` | 015 | 同上 |
| `ui/profile/ProfileSettingsActivity.kt` | 016 | 密码类偏好并入标准编辑弹窗；删除 PasswordDialogFragment |
| `widget/ConnectBar.kt`、`layout_main.xml`、`layout_connect_bar.xml` | 017 | 底部长条启动栏替代 FAB+StatsBar；删除 StatsBar/FabProgressBehavior；移除 showBottomBar |
| `ui/OutboundIpDialogFragment.kt`、`layout_outbound_ip_dialog.xml` | 017/019/025 | 出站 IP 查询弹窗；国家地区行+三行点击复制（019）；数据源=025 六源对冲 |
| `ui/ConfigurationFragment.kt` | 017 | 移除旧底部滚动驱动；列表预留启动栏滚动余量 |
| `ui/ConfigurationFragment.kt` | 018 | 分组标签长按三选菜单；删除分组带还原（按原 id 回插） |
| `ui/MainActivity.kt` | 019 | 延迟区点击接线 onTestConnection（修永远“测试中”） |
| `ui/ConfigurationFragment.kt` | 020/022 | 删除分组还原：pager 同步修复 → 按删除前位置原位插回（restoreGroupAt） |
| `layout_connect_bar.xml`、`bg_touch_rounded.xml` | 023 | 三处触摸底色统一圆角矩形；延迟区固定宽；间距/留白微调 |
| `widget/ServiceIconView.kt` | 024 | 官方 AVD 动画引擎纯图标版（斜线/形变动画队列）；删除进度环遗留；四区均匀分布 |
| `ui/IpQualityLookup.kt` | 025 | FlClash 0.8.99 六源对冲查询（ident.me/ip-api/ipquery/iplocate/ipapi.is/proxycheck），字段/等级/命中标记全对齐 |
| `ui/IpQualityLookup.kt` | 026/028 | 连接即预查询（`prefetch(forProfile)`，026 发起、028 绑定 profileId）；缓存按节点隔离（`cacheFor(profileId)`）；select 回归完成序=先答先赢；`COUNTRY_GRACE_MS` 国家跨源宽限 |
| `ui/MainActivity.kt` | 026/027/029 | 连接成功自动 ping（`connectBar.showDelay(elapsed)` 手动+自动 2 处）+ urlTest 冷启动 700ms 重试 + 自动 ping 前 500ms 稳定期 |
| `widget/ConnectBar.kt`、`layout_connect_bar.xml`、`layout_main.xml` | 026/027 | 去弹性空隙（`layout_weight` 移除）、栏宽收窄为内容宽并居中；速度区固定 `88dp`；未连接延迟占位「… ms」 |
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
- 长按入口之外不得再引入手柄/按钮触发；
- `groupUpdated(group)` 的同实例回声去重保留（防松手闪烁）——**配套前提**是 011：
  更新完成的广播必须来自数据库新对象（`GroupUpdater.finishUpdate`），两者成对存在，
  移植时缺一就会出现“进度条永不收起”或“松手闪烁”二选一的回归。

## 依赖关系图

```
MainActivity(001: 缓存/预热/抽屉预热/openGroupAt/内边距)
     ↑ 被依赖
ConfigurationFragment(004: 长按→openGroupAt; onHiddenChanged 同步; 动态加载; syncOrderFromDb)
GroupFragment(003: scrollToGroup 被 openGroupAt 调用; 拖动/删除同步)
     ↑ 联动
TopBarController(010: ☴ 行数量后缀, 独立) | IpQualityLookup(025/026/028: 出站 IP, MainActivity↔OutboundIpDialogFragment 共用缓存) | 布局 007 独立 | 008 独立 | 005/006 独立 | 009(CI) 独立 | 002 其余页面 独立
基线自带: TopBarController（规格1-4 主体）、GroupFragment.setupFilterBar（规格4）
```
