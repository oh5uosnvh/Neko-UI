#!/usr/bin/env python3
"""Release guard: exact menu order, no DB schema or serialized-bean drift."""
from pathlib import Path
import subprocess,sys,xml.etree.ElementTree as ET
r=Path(sys.argv[1]).resolve();ns='{http://schemas.android.com/apk/res/android}'
m=ET.parse(r/'app/src/main/res/menu/add_profile_menu.xml').getroot()
add=next(i for i in m.findall('item') if i.get(ns+'id')=='@+id/action_add').find('menu')
assert [i.get(ns+'id') for i in add.findall('item')]==['@+id/action_scan_qr_code','@+id/action_import_clipboard','@+id/action_import_file','@+id/action_new_group',None]
assert add.findall('item')[4].find('menu') is not None
for p in (r/'app/src/main/res').glob('values*/*.xml'):
 ET.parse(p)
cfg=(r/'app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt').read_text()
assert 'R.id.action_new_group ->' in cfg
assert 'putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, 0L)' in cfg
assert 'GroupTabPolicy.afterRemoval' in cfg
assert 'val scrollX = tabLayout.scrollX' in cfg
assert 'if (generation != removalGeneration' in cfg
assert 'SagerDatabase.groupDao.allGroups().firstOrNull()?.let' not in cfg
assert 'proxyGroup = SagerDatabase.groupDao.getById(groupId)!!' not in cfg
assert 'val ungrouped = groups.firstOrNull { it.ungrouped }' in (r/'app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt').read_text()
data=(r/'app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt').read_text()
assert 'getById(currentSelected) != null' in data
assert 'MIGRATION_8_9_SAFE' in (r/'app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt').read_text()
# ProxyEntity.kt may receive DAO-only, ID-only cleanup/page queries; reject only
# actual Room schema or bean serializer drift.
for path in ['app/src/main/java/io/nekohasekai/sagernet/fmt/KryoConverters.kt','app/schemas']:
 diff=subprocess.run(['git','-C',str(r),'diff','--',path],capture_output=True,text=True,check=True).stdout
 assert not diff, 'Unexpected data schema/serialization change: '+path
box=(r/'libcore/box_include.go').read_text()
assert 'RegisterVLESSOutbound' in box
assert 'snell-yf' not in box and 'RegisterSnellOutbound' not in box
print('PASS: menu order, stable-ID tab removal, lifecycle guards, unchanged Room schemas/bean serialization, retained migration and X365')
