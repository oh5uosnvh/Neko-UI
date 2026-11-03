package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Selection is immediate in UI; disk writes are serialized and latest-only outside animation frames. */
internal class GroupSelectionWriter(private val save: (Long) -> Unit) {
    private val updates = Channel<Long>(Channel.CONFLATED)
    init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { for (id in updates) save(id) } }
    fun close() { updates.close() }
    fun submit(id: Long) { if (id > 0L) updates.trySend(id) }
}
