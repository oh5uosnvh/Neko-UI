# pre19 tags r3：菜单框/中轴线/三层手柄专属排序

基线：最后成功构建 `672dac56`，r2 CI 42814322688。

## 需求映射
- 原Neko圆角列表菜单框恢复：`container.background=roundedCard()`、不透明主题底色、原描边与内侧边距、outer clipToOutline；不再透出下面配置卡或顶层tab。
- 配置|标签按钮在顶部表层且opaque：新`ThemedChoiceStyle.opaqueItemCard`把原半透明中性/主题fill与原menu背景预合成opaque颜色，保留相同视觉颜色和圆角/选中描边，不改变正文原itemCard。
- 数量/更新·日期与更新按钮同中轴线：去掉status底部外边距和binding时追加底padding，父横排center_vertical并baselineAligned=false。
- 手动选择标题和返回/确认同中轴线：TextView gravity CENTER_VERTICAL、单行，row取消baselineAligned。
- root配置、tag组、tag内root配置全带≡，成员新增helper和实际move+complete root slot merge写入，与原GroupOrderStore串行writer统一。非members根位置不变、其他tag成员跟同一root顺序同步。
- 正文只滚动：只有RecyclerView真实DOWN后手柄DOWN授权holder，getDragDirs、prepareDrag、onMove都要求授权。UP/CANCEL/close清授权。
- 找到“滑动却被拖动”的恢复重抓根因：ItemTouchHelper DOWN/findAnimation/select不看flags，禁长按不足。新DOWN前退休旧helper恢复动画，正文不被旧recover劫持，普通滚动不取消邻居moveAnimator。

## 本地真实核验
资源/ViewBinding/KSP、完整Kotlin/Java与测试编译；git diff --check、bash -n run gradlew。
112项相关回归0失败/错误/跳过：所有三层真实MotionEvent正文DOWN/MOVE/长按不prepareDrag不修改order；recover期间正文DOWN不能reselect；未授权programmatic start不能move；CANCEL/close幂等singlecommit/rollback；成员≡root槽位合并单次回执、快速两次drag按最新queuedroot合并、同步root/其他tag/reopen。
布局检查两theme、1x/1.5x字体、带/不带流量：status与update View及实际textlayout轴线相同；选择title与箭头/确认同行居中；opaque顶部颜色等于原fill合成色，原正文样式不改。
真实popup保留外框opaque与headeropaque，首卡在header下，三级往返/close-reopen/viewport和原根拖动/恢复动画均回归。
测试触摸坐标采用真正恢复translation位置：old layout坐标不等于屏幕可见手柄；不弱化手柄命中断言。

## CI门禁
新增TagR3NativeGeometryTest继承同一布局/textcenter断言，在API28/35 native字体执行；group/ui/database三包全部运行，不跳过失败；全部通过再交原签名双架构候选。仍不覆盖正式pre19资产。

首轮CI 42828795331：597项，595通过、2个真实session测试在关闭重开后的异步状态/布局等待断言失败；native几何全部通过，未交包。修正test fixture每次await至少drain主线程+layout一次，show/open明确确认当前root/成员ID集合及当前读取任务完成，而非仅看可已取消旧Job.isCompleted。原视口/成员集合断言不取消不改期望，复验16项session+handle+geometry全部通过。生产代码无新增变动。
