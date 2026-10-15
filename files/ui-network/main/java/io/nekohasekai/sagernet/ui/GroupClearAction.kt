package io.nekohasekai.sagernet.ui

import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The tab menu and group card deliberately share this command. */
object GroupClearAction {
    private val access = Mutex()
    private val running = mutableSetOf<Long>() // Main thread only

    fun confirm(activity: ThemedActivity, group: ProxyGroup) {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
            .setMessage(R.string.clear_profiles_message)
            .setPositiveButton(R.string.yes) { _, _ -> clear(activity, group) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun clear(activity: ThemedActivity, group: ProxyGroup) {
        if (!running.add(group.id)) return
        activity.lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    access.withLock {
                        val selected = DataStore.selectedProxy
                        val nodes = GroupClearStore.clear(SagerDatabase.instance, group.id) ?: return@withLock null
                        if (nodes.any { it.id == selected }) DataStore.selectedProxy = 0L
                        GroupManager.postReload(group.id)
                        nodes to selected
                    }
                } ?: return@launch
                activity.snackbar(activity.getString(R.string.neko_group_cleared, group.displayName()))
                    .setAction(R.string.undo) {
                        activity.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    access.withLock {
                                        // Never resurrect a deleted group, overwrite new nodes, or
                                        // duplicate IDs after a subscription has already reloaded.
                                        if (!GroupClearStore.restore(SagerDatabase.instance, group.id, saved.first)) return@withLock
                                        if (DataStore.selectedProxy == 0L && saved.first.any { it.id == saved.second }) {
                                            DataStore.selectedProxy = saved.second
                                        }
                                        GroupManager.postReload(group.id)
                                    }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { Logs.w(e); activity.snackbar(e.readableMessage).show() }
                        }
                    }.setDuration(Snackbar.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w(e); activity.snackbar(e.readableMessage).show() }
            finally { running.remove(group.id) }
        }
    }
}
