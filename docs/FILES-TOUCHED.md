# 文件 → 功能映射（A01 → A02）

## 已改动文件（补丁覆盖）

| 文件 | 补丁 | 功能 |
|---|---|---|
| `ui/MainActivity.kt` | 001 | 侧边栏缓存/预热/恢复、openGroupAt、状态栏内边距分发 |
| `ui/ToolbarFragment.kt` | 002 | `resetScrollState()` 开放钩子 + onHiddenChanged 触发 |
| `ui/SettingsFragment.kt` | 002 | 滚动复位覆写（Preferences 列表） |
| `ui/RouteFragment.kt` | 002 | 滚动复位覆写 |
| `ui/ToolsFragment.kt` | 002 | 滚动复位覆写（ScrollView） |
| `ui/LogcatFragment.kt` | 002 | 滚动复位 + 日志文本复位 |
| `ui/AboutFragment.kt` | 002 | 滚动复位覆写（NestedScrollView） |
| `ui/GroupFragment.kt` | 003 | scrollToGroup 定位、删除即时同步、幂等护栏、滚动复位 |
| `ui/ConfigurationFragment.kt` | 004 | 仅 4 个保留项（见 CHANGES#4）；**切换路径=官方** |
| `ui/GroupSettingsActivity.kt` | 005 | 返回自动选中新分组 |
| `ui/ThemedActivity.kt` | 006 | 多页面状态栏内边距修复 |
| `res/layout/layout_appbar.xml` | 007 | = 官方原文（elevation 4dp） |
| `res/layout/layout_group_list.xml` | 007 | = 官方原文 + 透明 ripple（唯一偏离） |
| `res/layout/layout_tools.xml` | 007 | = 官方原文（elevation 4dp）+ 透明 ripple |
| `.github/workflows/build_mod.yml` | 008 | 协议 mod 源钉扎 + libgojni 校验 |
| `nb4a.properties` | 不打包 | 发版时更新 `PRE_VERSION_NAME`（A02 → 下一版） |

## 不可触碰区域（改这些 = 破坏验收标准）

> 以下均为 `ConfigurationFragment.kt` 内区域，匹配时按符号名搜索。

1. **切换路径全部符号**：`TabLayoutMediator(tabLayout, groupPager) { ... }`（两参构造）、
   `updateSelectedCallback`、`GroupPagerAdapter.reload/groupAdd/groupRemoved/
   groupUpdated/createFragment/getGroupFragment/getItemId/containsItem`、
   `GroupFragment.onResume/onViewCreated`——这些必须逐字保持官方。
2. **禁止出现的符号**（历史废案，见 REBASE.md 档案）：
   `prefetchJob`、`prefetchGroupPages`、`warmUpJob`、`startPageWarmUp`、
   `pageWarmStep`、`pageWarmCap`、`isPagerBusy`、`ensureProfilesLoaded`、
   `profilesLoadStarted`、`sharedRecycledViewPool`、`pendingStateRefreshes`、
   `drawerBusy`、`hasWindowFocus`（预热让路用）——出现即删。
3. **顶部栏三布局**：只许「官方原文 + 透明 ripple」形态；不要恢复
   `bg_group_tab_row` 容器、`bg_tab_indicator_box` 包围框、任何 elevation=0dp。
4. **允许动的小区域**（保留项，见 CHANGES#4）：
   `onHiddenChanged`（加同步逻辑）、`resetScrollState`（存在本身）、
   `reloadProfiles`（DiffUtil）、mediator 内的长按监听块（调 openGroupAt）。

## 依赖关系图

```
MainActivity(001: 缓存/openGroupAt/内边距)
     ↑ 被依赖
ConfigurationFragment(004: 长按→openGroupAt; onHiddenChanged 同步)
GroupFragment(003: scrollToGroup 被 openGroupAt 调用)
     ↑ 联动
布局(007) 独立；CI(008) 独立；其余页面(002) 独立。
```
