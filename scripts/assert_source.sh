#!/usr/bin/env bash
# Neko-UI 源码断言器：011–029 修复/功能锚点（从 CI workflow 抽出，语义 1:1）。
#
# 用法:
#   bash scripts/assert_source.sh <上游源码目录>
# CI 在 "Apply mod patches" 之后调用（传 upstream）；本地对已应用补丁的源码树同样可跑。
# 任何一项失败即打印 ::error:: 并 exit 1（CI 构建终止）。
set -euo pipefail

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
[ "$(grep -c 'undoManager.remove(visible)' "$F")" = "2" ] \
  || fail "014 undo channel not wired (need 2 call sites)"
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

# ------------------------------------------------ 017+019/021/023/024/026/027/028/029 启动栏体系
M=app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt
test -f app/src/main/java/io/nekohasekai/sagernet/widget/ConnectBar.kt \
  || fail "017 ConnectBar missing"
test -f app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt \
  || fail "017 OutboundIpDialogFragment missing"
grep -q 'connect_bar' app/src/main/res/layout/layout_main.xml \
  || fail "017 layout not rewired"
! grep -q 'io.nekohasekai.sagernet.widget.StatsBar' app/src/main/res/layout/layout_main.xml \
  || fail "017 old StatsBar still in layout"
! grep -q 'binding\.fab\|binding\.stats' "$M" \
  || fail "017 MainActivity still binds fab/stats"
! grep -q 'showBottomBar' app/src/main/java/io/nekohasekai/sagernet/Constants.kt \
  || fail "017 showBottomBar pref not removed"
grep -q 'connectBar.onTestConnection' app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt \
  || fail "019 delay zone not wired (would stick on testing)"
grep -q 'syncConnectBar' app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt \
  || fail "021 config-only visibility missing"
grep -q 'bg_touch_rounded' app/src/main/res/layout/layout_connect_bar.xml \
  || fail "023 unified rounded touch bg missing"
! grep -q '延迟 %d ms' app/src/main/res/values-zh-rCN/strings.xml \
  || fail "023 delay prefix not removed"
test -f app/src/main/java/io/nekohasekai/sagernet/widget/ServiceIconView.kt \
  || fail "024 ServiceIconView missing"
! grep -q 'bar_fab_progress' app/src/main/res/layout/layout_connect_bar.xml \
  || fail "024 progress ring should be gone"
grep -q 'serviceIcon.changeState(state, currentState, animate)' app/src/main/java/io/nekohasekai/sagernet/widget/ConnectBar.kt \
  || fail "024 animated engine not wired"
! grep -q 'layout_weight="1"' app/src/main/res/layout/layout_connect_bar.xml \
  || fail "026 elastic spacer should be gone"
grep -q 'layout_width="wrap_content"' app/src/main/res/layout/layout_main.xml \
  || fail "026 bar width not wrapped to content"
grep -q 'IpQualityLookup.prefetch(DataStore.currentProfile)' app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt \
  || fail "026/028 prefetch on connect missing (profile-bound)"
grep -q 'fun prefetch(forProfile: Long)' app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt \
  || fail "026/028 profile-bound prefetch missing"
grep -q 'select<Done>' app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt \
  || fail "028 first-answer-wins select missing"
grep -q 'COUNTRY_GRACE_MS' app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt \
  || fail "028 country grace missing"
grep -q 'urlTest cold-start retry' app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt \
  || fail "029 cold-start retry missing"
grep -q 'delay(500)' app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt \
  || fail "029 auto-ping settle delay missing"
grep -q 'IpQualityLookup.cacheFor(profileId)' app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt \
  || fail "028 profile-bound cache fallback missing"
grep -q 'setLoadingState()' app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt \
  || fail "028 instant loading state missing"
[ "$(grep -c 'connectBar.showDelay(elapsed)' app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt)" = "2" ] \
  || fail "027 auto-ping on connect missing (need manual+auto)"
grep -q 'bar_delay_tap">… ms' app/src/main/res/values-zh-rCN/strings.xml \
  || fail "027 delay idle placeholder wrong"
grep -q 'android:layout_width="88dp"' app/src/main/res/layout/layout_connect_bar.xml \
  || fail "027 speed zone fixed width missing"
! grep -q '点击测试' app/src/main/res/values-zh-rCN/strings.xml \
  || fail "027 old tap-to-test text still present"
! test -f app/src/main/java/io/nekohasekai/sagernet/widget/ServiceButton.kt \
  || fail "021 ServiceButton should be gone"
echo "017+019+021 bottom connect bar present"

# ------------------------------------------------ 018 分组标签菜单 + 删除还原
F=app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt
grep -q 'deleteGroupWithUndo' "$F" \
  || fail "018 group undo delete missing"
grep -q 'R.string.jump_to_group' "$F" \
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
D=app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt
grep -q 'country' "$D" || fail "019 country field missing"
grep -q 'bindCopy' "$D" || fail "019 copy-on-tap missing"
grep -q 'row_country\|ip_country_value' app/src/main/res/layout/layout_outbound_ip_dialog.xml \
  || fail "019 country row missing"
echo "019/020 polish + undo fix present"

# ------------------------------------------------ 025 出站 IP 六源对冲
L=app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt
test -f "$L" || fail "025 IpQualityLookup missing"
for src in ident.me ip-api.com ipquery.io iplocate.io ipapi.is proxycheck.io; do
  grep -q "$src" "$L" || fail "025 source $src missing"
done
grep -q 'isAbuser' "$L" || fail "025 abuser flag missing"
D=app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt
grep -q 'IpQualityLookup.lookup(profileId)' "$D" \
  || fail "025/028 dialog not wired to lookup"
echo "025 ip quality multisource present"

echo "== 源码断言全部通过（011–029）=="
