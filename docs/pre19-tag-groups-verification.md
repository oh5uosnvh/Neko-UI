# pre19 标签组深化实现与核验记录

基线：发布标签 `1.4.2-mg-pre19`，提交 `3e42f7ada352367a20d4e438cd73171c3f18a0ec`。
需求依据：用户上传《标签组功能与界面布局说明》全文及编译后标签 XML；图标已还原原有 path，不用文字占位。

## 已实现
- 配置分组为根；TagGroup + 多对多 TagMembership 联合主键、FK cascade，不复制配置实体。Room 10->11 仅新增两表与关联索引，旧根组、配置、规则表 schema 完全不变；移除 destructive migration。
- 标签名称 trim/nonempty/duplicate/self edit 校验；删除后新建同名再撤销按旧ID独立恢复不覆盖新实体；事务失败完整rollback、失败undo可重试、成功undo幂等。
- 快捷分组列表等宽“配置/标签”，标签二级名称列表、三级XML图标标题和根成员；保留当前根组选中及根排序，逐层返回、滚动像素位置与生命周期generation保护。
- 分组顶部新增XML标签入口；标签三行卡片：名称+固定右编辑/删除、完整空行、xx个分组；主体查看、编辑管理、拖动排序、名称编辑与删除确认。
- 标签成员复用原GroupHolder，区分查看/编辑；搜索根配置、空态、常驻“手动”、选中及已归类标记、明确✓提交、返回放弃确认、整批add/remove undo。
- 标签内新建/订阅/剪贴板：显式传tagId，根实体+节点+关联同一事务，不依赖全局创建上下文；取消不归类，关联失败rollback。
- 标签内顶部更新仅快照当前成员ID，IO执行原订阅更新，禁重复启动，失败提示卡片更新可重试。
- 标签内删除/左滑询问全球或仅当前标签。全球删除延迟5秒提交，所有页面和数量剔除pending roots，undo期间实体/节点/全部标签关系未破坏；提交时事务cascade。
- 标签成员拖动交换根列表中成员原位置，提交全局完整顺序，不把成员子集提升到列表顶部。
- 标签页面稳定Fragment标记和保存恢复，旋转/重建不误跳仪表盘或丢失标签层级。

## 本地通过
- XML/资源/ViewBinding/KSP、全量Kotlin/Java和tests编译。
- `git diff --check`；`bash -n run gradlew`。
- 86项相关综合回归0失败：18项事务/多对多/name/order/rollback/undo，2项10->11迁移，3项旧v8安全升级，标签UI/搜索手动/真实popup动态rename-member-delete/back/root viewport，以及pre19搜索/零流量/原拖动与undo等。
- 本机Alpine/aarch64：Robolectric自带SQLite native和legacy资源均不支持。使用官方sqlite4java原源码编译本机JNI（SQLite3.8.7），仅在workspace替换本地测试loader，真实SQL、外键cascade、trigger故障回滚和Room migration均实际执行。此adapter不写入应用，不改依赖缓存，CI使用上游正常x86_64 runtime。
- 全套本机探索因上游native font/graphics runtime在ARM64无法加载并超时，不能宣称完整三包全套通过。CI扩大至group/ui/database三包，必须使用标准x86_64运行时全部通过再交付候选APK。

## 发布边界
候选使用单独版本与提升versionCode，不覆盖已发布pre19标签/资产。尚无手动真机逐机型回归；候选APK供测试使用。
