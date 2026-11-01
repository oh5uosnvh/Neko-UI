package io.nekohasekai.sagernet.assets

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore

/**
 * Persistent settings for the Karing resource families.
 *
 * These values are read by both the UI and the VPN process. Keep them in the
 * existing Room-backed public configuration store rather than SharedPreferences:
 * every read queries SQLite and every write is synchronous, so neither process
 * can observe a stale in-memory preference snapshot.
 */
class KaringResourceSettings(
    context: Context,
    private val store: BooleanStore = DatabaseBooleanStore,
) {
    private val appContext = context.applicationContext

    interface BooleanStore {
        fun getBoolean(key: String): Boolean?
        fun putBoolean(key: String, value: Boolean)
    }

    private object DatabaseBooleanStore : BooleanStore {
        override fun getBoolean(key: String): Boolean? = DataStore.configurationStore.getBoolean(key)
        override fun putBoolean(key: String, value: Boolean) =
            DataStore.configurationStore.putBoolean(key, value)
    }

    init {
        migrateLegacyPreferences()
    }

    data class Options(val enabled: Boolean, val autoAppendRegion: Boolean, val useRemote: Boolean)

    fun get(family: KaringRuleFamily): Options = Options(
        enabled = store.getBoolean(key(family, ENABLED)) ?: true,
        autoAppendRegion = family != KaringRuleFamily.ACL &&
            (store.getBoolean(key(family, AUTO_APPEND_REGION)) ?: true),
        useRemote = store.getBoolean(key(family, USE_REMOTE)) ?: false,
    )

    fun setEnabled(family: KaringRuleFamily, value: Boolean) =
        store.putBoolean(key(family, ENABLED), value)

    fun setAutoAppendRegion(family: KaringRuleFamily, value: Boolean) {
        require(family != KaringRuleFamily.ACL)
        store.putBoolean(key(family, AUTO_APPEND_REGION), value)
    }

    fun setUseRemote(family: KaringRuleFamily, value: Boolean) =
        store.putBoolean(key(family, USE_REMOTE), value)

    private fun migrateLegacyPreferences() {
        val legacy = appContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        for (family in KaringRuleFamily.values()) {
            migrateBoolean(legacy, family, ENABLED)
            migrateBoolean(legacy, family, AUTO_APPEND_REGION)
            migrateBoolean(legacy, family, USE_REMOTE)
        }
        // The old file is no longer read. commit() makes removal visible before
        // another process can perform the same one-time compatibility migration.
        legacy.edit().clear().commit()
    }

    private fun migrateBoolean(
        legacy: android.content.SharedPreferences,
        family: KaringRuleFamily,
        name: String,
    ) {
        val value = legacy.getAll()[legacyKey(family, name)] as? Boolean ?: return
        val databaseKey = key(family, name)
        if (store.getBoolean(databaseKey) == null) {
            store.putBoolean(databaseKey, value)
        }
    }

    private fun key(family: KaringRuleFamily, name: String) =
        "$DATABASE_PREFIX${family.wireName}.$name"

    private fun legacyKey(family: KaringRuleFamily, name: String) =
        "${family.wireName}.$name"

    companion object {
        private const val LEGACY_PREFERENCES = "karing_resource_settings"
        private const val DATABASE_PREFIX = "karingResource."
        private const val ENABLED = "enabled"
        private const val AUTO_APPEND_REGION = "auto_append_region"
        private const val USE_REMOTE = "use_remote"

        fun remoteBase(family: KaringRuleFamily): String = when (family) {
            KaringRuleFamily.GEOSITE -> "https://github.com/KaringX/karing-ruleset/raw/sing/geo/geosite"
            KaringRuleFamily.GEOIP -> "https://github.com/KaringX/karing-ruleset/raw/sing/geo/geoip"
            KaringRuleFamily.ACL -> "https://github.com/KaringX/karing-ruleset/raw/sing/ACL4SSR"
        }

        fun remoteUrl(family: KaringRuleFamily, code: String): String {
            require(Regex("^[A-Za-z0-9][A-Za-z0-9@._-]{0,127}$").matches(code))
            return "${remoteBase(family)}/$code.srs"
        }
    }
}
