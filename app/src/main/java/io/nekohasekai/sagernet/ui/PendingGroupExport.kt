package io.nekohasekai.sagernet.ui

import android.os.Bundle

/** A document picker belongs to the group that opened it, not the current tab. */
internal class PendingGroupExport {
    var pendingExportGroupId: Long? = null
        private set

    fun begin(groupId: Long): Boolean {
        if (groupId <= 0L || pendingExportGroupId != null) return false
        pendingExportGroupId = groupId
        return true
    }

    /** Also called for a cancelled picker or a failed launch. */
    fun consume(): Long? = pendingExportGroupId.also { pendingExportGroupId = null }

    fun restoreState(savedInstanceState: Bundle?) {
        pendingExportGroupId = savedInstanceState?.getLong(STATE_GROUP_ID, 0L)
            ?.takeIf { it > 0L }
    }

    fun saveState(outState: Bundle) {
        val groupId = pendingExportGroupId
        if (groupId == null) outState.remove(STATE_GROUP_ID)
        else outState.putLong(STATE_GROUP_ID, groupId)
    }

    private companion object {
        const val STATE_GROUP_ID = "pending_profile_export_group_id"
    }
}
