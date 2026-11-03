package io.nekohasekai.sagernet.ui

import org.json.JSONObject

/** A startup snapshot is usable only for the same selected node and current service generation. */
internal data class ConnectionProbeStatus(
    val generation: Long,
    val profileId: Long,
    val phase: String,
    val elapsed: Int,
    val error: String,
) {
    companion object {
        fun parse(raw: String): ConnectionProbeStatus? = runCatching {
            val data = JSONObject(raw)
            if (data.optInt("version") != 1) return null
            val profile = data.optLong("profileId")
            val generation = data.optLong("generation")
            val phase = data.optString("phase")
            if (profile <= 0L || generation < 1L || phase !in setOf("idle", "connecting", "connected", "failed")) return null
            ConnectionProbeStatus(generation, profile, phase, data.optInt("elapsed", -1), data.optString("error"))
        }.getOrNull()
    }
}
