package io.nekohasekai.sagernet.bg

import android.content.Intent
import io.nekohasekai.sagernet.database.DataStore

/** Shared by the UI and :bg processes; do not cache the persisted epoch in memory. */
object ServiceStartPolicy {
    private const val EPOCH_KEY = "serviceUserStopEpoch"
    const val EXTRA_EPOCH = "service_control_epoch"
    const val EXTRA_CAUSE = "service_start_cause"
    const val USER = "user"
    const val RELOAD = "reload"
    const val BOOT = "boot"

    val fence = ServiceRestartFence(
        { DataStore.configurationStore.getLong(EPOCH_KEY, 0L) },
        { DataStore.configurationStore.putLong(EPOCH_KEY, it) },
    )

    fun tag(intent: Intent, cause: String, epoch: Long = fence.snapshot()): Intent =
        intent.putExtra(EXTRA_CAUSE, cause).putExtra(EXTRA_EPOCH, epoch)

    fun permits(intent: Intent?): Boolean {
        val epoch = intent?.takeIf { it.hasExtra(EXTRA_EPOCH) }?.getLongExtra(EXTRA_EPOCH, 0L)
        return if (intent?.getStringExtra(EXTRA_CAUSE) == USER && epoch != null)
            fence.permitsUserStart(epoch)
        else fence.permitsAutomatic(epoch)
    }

    fun admit(intent: Intent?): Boolean {
        if (!permits(intent)) return false
        return if (intent?.getStringExtra(EXTRA_CAUSE) == USER)
            fence.admitUserStart(intent.getLongExtra(EXTRA_EPOCH, 0L))
        else true
    }
}
