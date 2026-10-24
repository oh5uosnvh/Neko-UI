#!/usr/bin/env python3
from pathlib import Path
import sys
p=Path(sys.argv[1]).resolve()/'app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt'
s=p.read_text()
old='''                val newIndex = groupList.size - 1
                notifyItemInserted(newIndex)
                tabLayout.getTabAt(newIndex)?.select()'''
new='''                val newIndex = groupList.size - 1
                // A newly imported subscription is the active group. Do not
                // leave the tab/title pointing at the first pre-existing group.
                DataStore.selectedGroup = group.id
                selectedGroupIndex = newIndex
                notifyItemInserted(newIndex)
                groupPager.setCurrentItem(newIndex, false)
                tabLayout.getTabAt(newIndex)?.select()'''
if old not in s and new not in s: raise SystemExit('missing groupAdd anchor')
p.write_text(s.replace(old,new,1) if old in s else s)
print('PASS: imported subscription selects its own group')
