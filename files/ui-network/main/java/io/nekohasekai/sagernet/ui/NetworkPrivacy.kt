package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

object NetworkPrivacy {
    const val HIDE_IP = "hide_ip_address"
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("neko_network_privacy", Context.MODE_PRIVATE)
    fun hidden(context: Context): Boolean = prefs(context).getBoolean(HIDE_IP, false)
    fun display(ip: String?, hidden: Boolean): String = if (hidden) "*****" else ip?.takeIf { it.isNotBlank() } ?: "—"
    fun flag(country: String?): String {
        val name = country?.trim().orEmpty()
        if (name.isEmpty()) return "🌐"
        val aliases = mapOf("香港" to "HK", "中国香港" to "HK", "台湾" to "TW", "中国台湾" to "TW", "澳门" to "MO", "中国澳门" to "MO")
        val code = aliases[name] ?: Locale.getISOCountries().firstOrNull { code ->
            code.equals(name, true) || listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.getDefault()).any {
                Locale("", code).getDisplayCountry(it).equals(name, true)
            }
        } ?: return "🌐"
        return code.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + (it-'A'))) }.joinToString("")
    }
}
