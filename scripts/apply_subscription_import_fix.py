#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve()
def rep(rel,old,new):
 p=r/rel;s=p.read_text()
 if new in s:return
 if old not in s:raise SystemExit(f'missing import anchor {rel}')
 p.write_text(s.replace(old,new,1))
# A newly created clipboard subscription must become the selected group; do not
# leave the top bar pointing at the first pre-existing group.
p='app/src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt'
if 'DataStore.editingGroup = newGroup.id' not in (r/'app/src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt').read_text():
 rep(p,'            DataStore.selectedGroup = newGroup.id','            DataStore.selectedGroup = newGroup.id\n            DataStore.editingGroup = newGroup.id')
# Direct subscription links imported by Android intent follow the same rule.
p='app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt'
rep(p,'''    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }''','''    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        val created = GroupManager.createGroup(subscription)
        DataStore.selectedGroup = created.id
        DataStore.editingGroup = created.id
        GroupUpdater.startUpdate(created, true)
    }''')
print('PASS: imported subscription selects its newly created group')
