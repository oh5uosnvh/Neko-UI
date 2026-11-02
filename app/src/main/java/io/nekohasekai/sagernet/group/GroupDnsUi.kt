package io.nekohasekai.sagernet.group

/** Display-only DNS state; opening an editor never promotes imported data to a manual override. */
object GroupDnsUi {
    enum class Summary { UNSET, SUBSCRIPTION, FILE, CUSTOM }

    data class Automatic(
        val server: String = "",
        val direct: String = "",
        val privateDns: String = "",
        val hosts: String = "",
    )

    /** Only extract supported DNS fields, never the source document or node credentials. */
    fun automatic(imported: String?): Automatic = try {
        automatic(SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(imported.orEmpty())))
    } catch (_: IllegalArgumentException) {
        Automatic()
    }

    fun automatic(config: SubscriptionDns.Config): Automatic {
        fun compact(values: Map<String, List<String>>): Map<String, Any> =
            values.mapValues { (_, items) -> if (items.size == 1) items.first() else items }
        val privateText = when {
            config.serverPolicy.isEmpty() && config.policy.isNotEmpty() -> SubscriptionDns.dumpFlow(compact(config.policy))
            else -> buildList {
                if (config.policy.isNotEmpty()) add("nameserver-policy: " + SubscriptionDns.dumpFlow(compact(config.policy)))
                if (config.serverPolicy.isNotEmpty()) add("proxy-server-nameserver-policy: " + SubscriptionDns.dumpFlow(compact(config.serverPolicy)))
            }.joinToString("\n")
        }
        return Automatic(
            server = config.serverDns.joinToString("\n"),
            direct = config.directDns.joinToString("\n"),
            privateDns = privateText,
            hosts = if (config.hosts.isEmpty()) "" else SubscriptionDns.dump(compact(config.hosts)).trimEnd(),
        )
    }

    data class HostsState(val mapped: Boolean, val canSwap: Boolean)

    /** Original-domain mode must still allow applying the available mappings again. */
    fun hostsState(state: State, mapped: Boolean): HostsState =
        HostsState(mapped = mapped, canSwap = hasHosts(state.content))

    fun hasHosts(content: String): Boolean = try {
        SubscriptionDns.effective("", "", content, "").hosts.isNotEmpty()
    } catch (_: IllegalArgumentException) {
        false
    }

    data class State(
        val manual: String,
        val automatic: String,
        val content: String,
        val summary: Summary,
    ) {
        val hasAutomatic: Boolean get() = automatic.isNotBlank()
        val hasContent: Boolean get() = content.isNotBlank()

        /** null means leave the original manual value untouched. */
        fun edited(raw: String, initialContent: String = content): String? = when {
            raw == initialContent -> null
            raw.isBlank() && hasAutomatic -> "{}"
            else -> raw
        }
    }

    fun state(
        manual: String?,
        automatic: String?,
        source: String?,
        isSubscription: Boolean,
    ): State {
        val original = manual.orEmpty()
        val auto = automatic.orEmpty().takeUnless { it.trim() == "{}" }.orEmpty()
        val disabled = original.trim() == "{}"
        val custom = original.isNotBlank() && !disabled
        val content = when {
            disabled -> ""
            custom -> original
            auto.isNotBlank() -> auto
            else -> ""
        }
        val summary = when {
            content.isBlank() -> Summary.UNSET
            custom -> Summary.CUSTOM
            source == "subscription" -> Summary.SUBSCRIPTION
            source == "file" -> Summary.FILE
            isSubscription -> Summary.SUBSCRIPTION
            else -> Summary.FILE
        }
        return State(original, auto, content, summary)
    }
}
