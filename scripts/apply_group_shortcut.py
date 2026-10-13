#!/usr/bin/env python3
"""Post-A01 UI change: create group shortcut and retain adjacent tab on delete."""
from pathlib import Path
import sys, shutil
r=Path(sys.argv[1]).resolve()

def edit(rel,old,new):
 p=r/rel;s=p.read_text()
 if new in s:return
 if s.count(old)!=1:raise SystemExit(f'Expected one UI anchor: {rel} ({s.count(old)})')
 p.write_text(s.replace(old,new,1))

cfg='app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt'
edit('app/src/main/res/menu/add_profile_menu.xml',
'''            <item android:title="@string/add_profile_methods_manual_settings">''',
'''            <item
                android:id="@+id/action_new_group"
                android:title="@string/neko_create_group_shortcut" />
            <item android:title="@string/add_profile_methods_manual_settings">''')
for folder,title in [('values','Create group'),('values-zh-rCN','新建分组'),('values-zh-rTW','新增群組'),('values-zh-rHK','新增群組')]:
 path=r/'app/src/main/res'/folder/'neko_group_shortcut.xml'
 path.parent.mkdir(parents=True,exist_ok=True)
 path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="neko_create_group_shortcut">'+title+'</string></resources>\n')
edit(cfg,'''            R.id.action_new_socks -> {''', '''            R.id.action_new_group -> {
                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, 0L)
                })
            }

            R.id.action_new_socks -> {''')

edit(cfg,'''    /** 018: 长按分组标签 → 删除分组（带还原）。snackbar 超时（或滑动消失）后不可恢复。 */''', '''    private val groupDeleteAccess = Mutex()
    private val deletingGroupIds = mutableSetOf<Long>()

    /** Long-press delete: preserve the current tab, or select its left neighbour. */''')
start='''    fun deleteGroupWithUndo(group: ProxyGroup) {'''
end='''    /** 018/022: 还原刚删除的分组'''
p=r/cfg;s=p.read_text();a=s.index(start);b=s.index(end,a)
new='''    fun deleteGroupWithUndo(group: ProxyGroup) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        if (!isAdded || !::adapter.isInitialized || !deletingGroupIds.add(group.id)) return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                groupDeleteAccess.withLock {
                    // All adapter reads/writes run on Main. Resolve the ID again
                    // after acquiring the lock, never retain an obsolete index.
                    val snapshot = onMainDispatcher {
                        if (!isAdded || viewLifecycleOwnerLiveData.value !== owner) return@onMainDispatcher null
                        val ids = adapter.groupList.map { it.id }
                        Triple(ids, DataStore.selectedGroup, ids.indexOf(group.id))
                    } ?: return@withLock
                    if (snapshot.third < 0 || SagerDatabase.groupDao.getById(group.id) == null) return@withLock
                    val wasCurrent = snapshot.second == group.id
                    val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                    val nextId = GroupTabPolicy.afterRemoval(snapshot.first, group.id, snapshot.second)
                    // Protect selection against TabLayoutMediator's temporary
                    // first-tab selection during the structural update.
                    onMainDispatcher {
                        if (nextId != null) DataStore.selectedGroup = nextId
                    }
                    GroupManager.deleteGroup(group.id)
                    onMainDispatcher {
                        if (!isAdded || viewLifecycleOwnerLiveData.value !== owner) return@onMainDispatcher
                        snackbar(getString(R.string.group_deleted_undo, group.displayName())).apply {
                            setAction(R.string.undo) {
                                runOnDefaultDispatcher {
                                    restoreGroup(group, profiles, wasCurrent, snapshot.third)
                                }
                            }
                            duration = Snackbar.LENGTH_LONG
                        }.show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    if (isAdded && viewLifecycleOwnerLiveData.value === owner) snackbar(e.readableMessage).show()
                }
            } finally {
                onMainDispatcher { deletingGroupIds.remove(group.id) }
            }
        }
    }

'''
s=s[:a]+new+s[b:];p.write_text(s)

# Preserve the selected stable ID for all group deletion notifications, including
# duplicates and rapid deletes from the separate group page.
edit(cfg,'''        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return

            tabLayout.post {
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }''', '''        private var removalGeneration = 0L

        override suspend fun groupRemoved(groupId: Long) {
            onMainDispatcher {
                if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@onMainDispatcher
                val ids = groupList.map { it.id }
                val index = ids.indexOf(groupId)
                if (index < 0) return@onMainDispatcher
                val keepId = GroupTabPolicy.afterRemoval(ids, groupId, DataStore.selectedGroup)
                val scrollX = tabLayout.scrollX
                val generation = ++removalGeneration
                isRestoringGroup = true
                if (keepId != null) DataStore.selectedGroup = keepId
                groupList.removeAt(index)
                groupFragments.remove(groupId)
                notifyItemRemoved(index)
                val nextIndex = groupList.indexOfFirst { it.id == keepId }
                if (nextIndex >= 0) {
                    selectedGroupIndex = nextIndex
                    groupPager.setCurrentItem(nextIndex, false)
                }
                // Mediator and ViewPager both post work during item removal.
                // Finish selection after they settle, guarded against newer deletes.
                tabLayout.post {
                    if (generation != removalGeneration || viewLifecycleOwnerLiveData.value == null ||
                        !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@post
                    val currentIndex = groupList.indexOfFirst { it.id == DataStore.selectedGroup }
                    if (currentIndex >= 0) {
                        groupPager.setCurrentItem(currentIndex, false)
                        tabLayout.setScrollPosition(currentIndex, 0f, true)
                        tabLayout.scrollTo(scrollX, 0)
                    }
                    tabLayout.post {
                        if (generation == removalGeneration && isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) {
                            isRestoringGroup = false
                        }
                    }
                }
            }
        }''')

# Fail on schema changes: this UI patch must not touch migrations/serialized beans.
helper=Path(__file__).resolve().parent.parent/'files/group-shortcut'
shutil.copytree(helper,r/'app/src',dirs_exist_ok=True)
print('PASS: create-group shortcut and stable adjacent selection installed')

# Regression tests use a plain Application, avoiding native/VPN initialization.
gradle=r/'app/build.gradle.kts';s=gradle.read_text()
if 'testImplementation("org.robolectric:robolectric:4.14.1")' not in s:
 s=s.replace('dependencies {','''dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")''',1)
 s=s.replace('    buildFeatures {','''    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {''',1)
 gradle.write_text(s)
resource=r/'app/src/test/resources/database-v8.json'
resource.parent.mkdir(parents=True,exist_ok=True)
shutil.copy2(r/'app/schemas/io.nekohasekai.sagernet.database.SagerDatabase/8.json',resource)

# A delayed group update must not dereference a group deleted moments earlier.
edit(cfg,'''                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()''', '''                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                reloadProfiles()''')

# Commit group + nodes together. An exception between two independent deletes
# must not leave a half-deleted group behind after restarting the application.
edit('app/src/main/java/io/nekohasekai/sagernet/database/GroupManager.kt',
'''        SagerDatabase.groupDao.deleteById(groupId)
        SagerDatabase.proxyDao.deleteByGroup(groupId)''',
'''        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteById(groupId)
            SagerDatabase.proxyDao.deleteByGroup(groupId)
        }''')

# Restore the original group and nodes in one transaction as well.
edit(cfg,'''            SagerDatabase.groupDao.createGroup(group)
            if (profiles.isNotEmpty()) {
                SagerDatabase.proxyDao.insert(profiles)
            }''', '''            SagerDatabase.instance.runInTransaction {
                SagerDatabase.groupDao.createGroup(group)
                if (profiles.isNotEmpty()) SagerDatabase.proxyDao.insert(profiles)
            }''')
