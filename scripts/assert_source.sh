#!/usr/bin/env bash
# Neko-UI 源码断言器：A01 基线上的 011–033 修复/功能锚点（CI 同款）。
#
# 用法:
#   bash scripts/assert_source.sh <上游源码目录>
# CI 在 "Apply mod patches" 之后调用（传 upstream）；本地对已应用补丁的源码树同样可跑。
# 任何一项失败即打印 ::error:: 并 exit 1（CI 构建终止）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

UP="${1:-.}"
cd "$UP"

fail() { echo "::error::$1"; exit 1; }

# ------------------------------------------------ 011 分组更新进度条永不收起
F=app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt
grep -q 'GroupManager.postUpdate(proxyGroup.id)' "$F" \
  || fail "011 fix missing (progress bar sticky bug)"
! grep -q 'GroupManager.postUpdate(proxyGroup)$' "$F" \
  || fail "011 not applied (old same-instance broadcast)"
echo "011 group-update progress fix present"

# ------------------------------------------------ 012 连接测试实时回显
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
grep -q 'fun scheduleFlush()' "$F" \
  || fail "012 live flush missing"
[ "$(grep -c 'test.flushPending()' "$F")" = "2" ] \
  || fail "012 cancel flush wiring wrong"
! grep -q 'test.results.forEach' "$F" \
  || fail "012 not applied (old sequential write-back)"
echo "012 connection-test fixes present"

# ------------------------------------------------ 013 连接测试提速 + 取消即停
T=app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt
grep -q 'suspendCancellableCoroutine' "$T" \
  || fail "013 cancellable test missing"
grep -q 'invokeOnCancellation' "$T" \
  || fail "013 close-on-cancel missing"
grep -q 'doTest(context: CoroutineContext)' "$T" \
  || fail "013 dedicated dispatcher missing"
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
grep -q 'newFixedThreadPool' "$F" \
  || fail "013 test pool missing"
grep -q 'UrlTest(testPool)' "$F" \
  || fail "013 pool not wired into UrlTest"
echo "013 connection-test speed/cancel fixes present"

# ------------------------------------------------ 014 清理不可用接还原
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
[ "$(grep -c 'undoManager.remove(' "$F")" -ge 2 ] \
  || fail "014 undo channel not wired"
grep -q 'fun isUndoReady()' "$F" \
  || fail "014 isUndoReady missing"
echo "014 delete-unavailable undo present"

# ------------------------------------------------ 015 设置菜单 NPE 护栏
for f in app/src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt \
         app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt \
         app/src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt; do
  grep -q 'as? MyPreferenceFragmentCompat' "$f" \
    || fail "015 null-safe child missing in $f"
  ! grep -q 'val child by lazy' "$f" \
    || fail "015 old unsafe lazy cast still present in $f"
done
echo "015 settings menu NPE guards present"

# ------------------------------------------------ 016 偏好编辑弹窗统一
P=app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt
! grep -q 'PasswordDialogFragment.newInstance' "$P" \
  || fail "016 not applied (password dialog branch still present)"
test ! -f app/src/main/java/io/nekohasekai/sagernet/ui/profile/PasswordDialogFragment.kt \
  || fail "016 dead PasswordDialogFragment still exists"
echo "016 preference editor unify present"

# Current requested UI supersedes the historical compact 017-029 bar.

# ------------------------------------------------ 018 分组标签菜单 + 删除还原
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
grep -q 'deleteGroupWithUndo' "$F" \
  || fail "018 group undo delete missing"
grep -q 'R.string.jump_to_group' app/src/main/java/io/nekohasekai/sagernet/ui/TopBarController.kt \
  || fail "018 tab long-press menu missing"
grep -q 'group_deleted_undo' "$F" \
  || fail "018 undo snackbar string missing"
echo "018 group tab menu + undo present"

# ------------------------------------------------ 019/020/022 抛光 + 还原原位
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
grep -q 'restoreGroupAt' "$F" \
  || fail "020/022 undo pager sync missing"
grep -q 'fun restoreGroupAt' "$F" \
  || fail "022 in-position restore missing"
# 出站 IP 面板已按要求整体移除（025/028/019 不再适用）

# ------------------------------------------------ 030 显示开关即时生效（托板回归官方，不再断言定制 TapTargets）
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
grep -q 'val alwaysShowAddress get() = DataStore.alwaysShowAddress' "$F" \
  || fail "030 live getter missing"
! grep -q 'val alwaysShowAddress by lazy' "$F" \
  || fail "030 old lazy cache still present"
grep -q 'lastAppliedAlwaysShowAddress' "$F" \
  || fail "030 onResume refresh missing"

grep -q 'displayPrefListener' "$F" \
  || fail "031 display pref listener missing"
grep -q 'page.adapter?.reloadProfiles()' "$F" \
  || fail "031 page refresh propagation missing"

# ------------------------------------------------ 033 滚动条拖动锚定
E=app/src/main/java/io/nekohasekai/sagernet/widget/ExactScrollRecyclerView.kt
test -f "$E" || fail "033 ExactScrollRecyclerView missing"
grep -q 'grabOffset' "$E" || fail "033 scrollbar grab offset missing"
grep -q 'trackingTouch' "$E" || fail "033 scrollbar touch tracking missing"
grep -q 'getScrollBarThumbHeight' "$E" || fail "033 scrollbar hit bounds missing"

# ------------------------------------------------ 托板 = starifly 官方源码（StatsBar + ServiceButton FAB）
M=app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt
grep -q 'binding.fab.initProgress(binding.fabProgress)' "$M" \
  || fail "official fab init missing"
grep -q 'binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }' "$M" \
  || fail "official stats manual-test click missing"
grep -q 'private fun syncMainControls(' "$M" \
  || fail "official syncMainControls missing"
grep -q 'fun driveBottomBar(' "$M" \
  || fail "official driveBottomBar missing"
! grep -q 'ConnectBar\|ServiceIconView\|connectBar\|bar_delay' "$M" \
  || fail "custom tray residue in MainActivity"
W=app/src/main/java/io/nekohasekai/sagernet/widget
test -f "$W/StatsBar.kt" || fail "official StatsBar.kt missing"
test -f "$W/ServiceButton.kt" || fail "official ServiceButton.kt missing"
test ! -f "$W/ConnectBar.kt" || fail "custom ConnectBar.kt still present"
test ! -f "$W/ServiceIconView.kt" || fail "custom ServiceIconView.kt still present"
test ! -f app/src/main/res/layout/layout_connect_bar.xml \
  || fail "custom layout_connect_bar.xml still present"
grep -q 'setupBottomBarScrollDriver' \
  app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt \
  || fail "official stats scroll driver missing"
grep -q 'app:key="showBottomBar"' app/src/main/res/xml/global_preferences.xml \
  || fail "official showBottomBar preference missing"
grep -q 'name="show_bottom_bar"' app/src/main/res/values/strings.xml \
  || fail "official show_bottom_bar string missing"
# 官方托板无自动测速：连接成功不触发任何 urlTest
! grep -q '027/029' "$M" \
  || fail "connect-time auto urlTest still present"
! grep -rn 'fun onTestConnection' app/src/main/java/io/nekohasekai/sagernet/widget/ \
  || fail "custom tray test hook still present"

echo "== 源码断言全部通过（011–033 + 官方托板）=="
