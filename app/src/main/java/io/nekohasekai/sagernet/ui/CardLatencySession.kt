package io.nekohasekai.sagernet.ui

import android.os.SystemClock
import io.nekohasekai.sagernet.database.ProxyEntity
import java.util.concurrent.ConcurrentHashMap

/** Measurement lifetime, not RecyclerView binding lifetime, owns pending/result state. */
internal class CardLatencySession {
    class Entry(val generation: Long, val started: Long) {
        @Volatile var result: ProxyEntity? = null
        @Volatile var revealed = false
        fun remaining(now: Long) = (150L - (now - started)).coerceAtLeast(0L)
    }
    private val entries = ConcurrentHashMap<Long, Entry>()
    fun begin(ids: Collection<Long>, generation: Long, now: Long = SystemClock.uptimeMillis()) {
        entries.clear()
        ids.forEach { entries[it] = Entry(generation, now) }
    }
    fun complete(profile: ProxyEntity, generation: Long) {
        entries[profile.id]?.takeIf { it.generation == generation }?.result = profile
    }
    fun get(id: Long) = entries[id]
    fun clear() = entries.clear()
}
