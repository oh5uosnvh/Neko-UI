#!/usr/bin/env python3
from pathlib import Path
import sys,subprocess,xml.etree.ElementTree as ET
r=Path(sys.argv[1]).resolve(); base=r/'app/src/main'
def text(rel):return (base/rel).read_text()
cfg=text('java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt')
grp=text('java/io/nekohasekai/sagernet/ui/GroupFragment.kt')
menu=text('java/io/nekohasekai/sagernet/ui/GroupTabMenu.kt')
main=text('java/io/nekohasekai/sagernet/ui/MainActivity.kt')
clear=text('java/io/nekohasekai/sagernet/ui/GroupClearAction.kt')
assert 'GroupClearAction.confirm(it, group)' in cfg
assert 'GroupClearAction.confirm(activity, proxyGroup)' in grp
assert '.setAction(R.string.undo)' in clear and 'Snackbar.LENGTH_LONG' in clear
assert 'GroupClearStore.clear(' in clear and 'GroupClearStore.restore(' in clear
assert 'listOf(R.string.edit, R.string.jump_to_group, R.string.neko_clear, R.string.delete)' in menu
assert 'DashPathEffect' in menu and 'rows.maxOf' in menu
assert 'topBar.showGroupPicker(toolbar)' in cfg and 'R.id.neko_picker_groups' in cfg
button=text('java/io/nekohasekai/sagernet/widget/ServiceButton.kt')
assert 'FloatingActionButton' in button and 'animationQueue' in button
assert 'isIndeterminate' not in button and 'BaseProgressIndicator' not in button
layout=text('res/layout/layout_main.xml')
assert 'widget.ServiceButton' in layout and 'layout_anchor="@id/connect_bar"' in layout
assert 'CircularProgressIndicator' not in layout
assert 'repeat(2)' in main and 'handshakeJob?.cancel()' in main
assert 'DataStore.currentProfile == profileId' in main
assert 'binding.fab.changeState' in main and 'refreshNetworkCard()' in main
card=text('java/io/nekohasekai/sagernet/widget/ConnectBar.kt')
assert 'NetworkPrivacy.display' in card and 'NetworkPrivacy.flag' in card
assert 'q.level' not in card and 'setTextColor' not in card
stats=text('java/io/nekohasekai/sagernet/widget/StatsBar.kt')
original=subprocess.check_output(['git','-C',str(r),'show','f167ebc88e9a733e65380e13dab39726eba917b2:app/src/main/java/io/nekohasekai/sagernet/widget/StatsBar.kt'],text=True)
assert stats.replace('open class StatsBar','class StatsBar') == original, 'official StatsBar behavior drifted'
assert ') : StatsBar(' in card and 'binding.connectBar.syncMainControls(' in main
assert 'setupBottomBarScrollDriver()' in cfg and 'mainActivity.driveBottomBar(' in cfg
network_layout=text('res/layout/layout_connect_bar.xml')
assert 'MaterialCardView' not in network_layout and 'stroke' not in network_layout
lookup=text('java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt')
assert '.execute()' not in lookup and 'invokeOnCancellation { call.cancel() }' in lookup
assert 'live.value = entry' in lookup and 'generation != epoch' in lookup
assert 'updates.collect' in main and 'COUNTRY_GRACE_MS' not in lookup
privacy=text('java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt')
assert 'R.id.neko_hide_ip_switch' in privacy and 'NetworkPrivacy.hidden' in privacy
for p in (base/'res').rglob('*.xml'): ET.parse(p)
for p in (base/'java').rglob('*.kt'):
 s=p.read_text(); assert '\n<<<<<<< ' not in s and '\n>>>>>>> ' not in s, str(p)
for p in ['app/schemas','app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt','app/src/main/java/io/nekohasekai/sagernet/fmt/KryoConverters.kt']:
 d=subprocess.check_output(['git','-C',str(r),'diff','--',p],text=True);assert not d, 'schema/serialization change: '+p
print('PASS: both clear entries/undo, compact dashed menu, official non-spinning FAB, two handshakes, private neutral IP card, chain picker, no schema conflicts')
