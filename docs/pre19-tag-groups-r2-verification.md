# pre19 标签候选 r2 修复与核验

基线 `47eb458c` (pre19-debug-tags-r1)。本轮不修改数据库schema，不覆盖正式pre19资产。

## 根因与修复
1. 标签页无法退出：MainActivity把共享 `nav_group` 当成页面相同；改为比较 `currentMainFragment === fragmentCache[id]`，左上返回、设备返回、主菜单分组均回到根缓存实例。
2. 标签页面没有正常过渡：NavigationPageContent原只找`group_list`而标签实际`tag_list`，空页面也未释放就绪；补真实adapter及empty marker观测。复用Neko PageSlideTransition、frame gate，push右进左出，back左进右出；成员→标签列表显式back方向。
3. 日间搜索和新增图标固定白：菜单建立后统一ToolbarIconPalette使用页面textColorPrimary；标签矢量加粗2dp、扩大轮廓与内部孔线，透明内底防整图tint成白矩形。
4. 标签移除自行倒计时：删除计时Job、expires和秒数字符串；使用原Activity.snackbar默认LENGTH_LONG及OutlinedSnackbar。dismiss决定undo窗口，action先消费记录防重入；不重播、不延長时长。通用group及成员撤销也恢复默认时长。
5. 标签内新建多一层自定义弹窗：移除createGroupInTag，根页与标签页同调用原GroupSettingsActivity Intent及flags，标签仅追加EXTRA_TAG_ID以维持原子关联。
6. 快捷列表页瞬间切换：保留三张真实page View并用共享PageSlideTransition，入子页右→左，back左→右；不使用fade冒充slide。
7. 快捷列表close/reopen重置：TopBarController持有page和三层稳定ID+像素offset记忆，选择成员close后保留标签上下文；删tag回退标签页，View不保留。
8. 顶部配置|标签遮首卡：真实纵向header+weight list frame；两等宽按钮是表层，移除额外围框背景条和clipToOutline；首card起点在header下方。
9. 标签快捷列表数量及排序：名称·成员组数量，实际root existence/pending deletes过滤；右侧内部≡，原拖动callback及移动恢复时序，落定后单次串行TagGroupStore.reorder，不修改root order。

## 推送前核验
- 完整Android资源、ViewBinding/KSP、Kotlin/Java与测试代码编译。
- 本机120项相关回归，0失败/错误/跳过，含真实SQLite多对多、迁移、rollback、undo，真实MainActivity/FragmentManager与标签toolbar/device返回、forward/back view位移、根缓存复用、三级返回及新增Intent、实际Snackbar默认duration和restore。
- 真实PickerSession+Room: close/reopen page/全三层viewport记忆、删tagfallback、名称数量、≡拖动单次保存、root不改、header不遮首card、双向位移。
- Main测试跳过VPN/bootstrap而不替换navigation/fragment/animator业务；使用真实父生命周期和binding。导航变异旧navId实现导致5个测试失败，旧内容gate导致9个失败，证明覆盖原bug。
- popup实际动画通过同步设置两个真实ValueAnimator的playTime验证中间位移，避免Robolectric首帧时钟误判；不弱化方向断言。
- git diff --check、bash -n run gradlew。
- 图标另外增加SDK28/35 native像素回归：白色搜索日间不得丢失、所有opaque pixels对齐页面ink、标签轮廓放大透明内部；本机native graphics不支持，CI标准x86_64运行时执行。

## 出包门禁
标准CI仍执行group/ui/database完整套件与新native图标test，全部通过后才交付双架构原签名候选APK。不声称所有真实设备100%无bug；提供自动回归证明与真机验收点。

## CI首轮阻塞与纠正
CI 42811075772 共580项，577项通过、3项失败，未交包。
- 真导航三级冷启动揭示实际源码竞态：GroupAdapter构造中的Main.immediate协程可在宿主groupAdapter赋值前完成IO并访问字段。移除构造中的launch，在GroupFragment.onViewCreated的所有者/undo/RecyclerView初始化末尾再launch。新增连续6次冷启动成员页的真实回归。
- 两个native图标测试错误要求alpha>240，但主题前景可能是87%黑色；改为要求接近主题自身alpha的像素实际存在、RGB精确一致且主题alpha>=160，并保留尺寸/透明内孔检查，不取消或跳过像素验收。
修复后本机121项相关回归全部通过，待标准CI复验图标native和全套。
