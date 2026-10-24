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
# The pager must also select the newly inserted group; ViewPager's default
# first-page callback otherwise races and puts the title back on old group 0.
p='app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt'
s=(r/p).read_text()
old='''                val newIndex = groupList.size - 1
                notifyItemInserted(newIndex)
                tabLayout.getTabAt(newIndex)?.select()'''
new='''                val newIndex = groupList.size - 1
                DataStore.selectedGroup = group.id
                selectedGroupIndex = newIndex
                notifyItemInserted(newIndex)
                groupPager.setCurrentItem(newIndex, false)
                tabLayout.getTabAt(newIndex)?.select()'''
if old in s: s=s.replace(old,new,1)
(r/p).write_text(s)
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
