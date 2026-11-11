package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class GroupDnsUiTest {
    private val imported = """
        proxies:
          - name: do-not-display-node
            password: do-not-display-password
        hosts:
          node.example: [192.0.2.10, '2001:db8::10']
        dns:
          proxy-server-nameserver: [https://resolver.example/dns-query, tcp://192.0.2.53:5353]
          direct-nameserver: [223.5.5.5, tls://1.1.1.1]
          nameserver-policy:
            '+.corp.example': [https://private.example/query, 192.0.2.54]
          proxy-server-nameserver-policy:
            node.example: tcp://192.0.2.55:53
          default-nameserver: [9.9.9.9]
    """.trimIndent()

    private fun fields(auto: GroupDnsUi.Automatic) =
        listOf(auto.server, auto.direct, auto.privateDns, auto.hosts)

    @Test fun emptyNewGroupsHaveFourUnsetEditorsRegardlessOfType() {
        for (isSubscription in listOf(false, true)) {
            for (auto in fields(GroupDnsUi.automatic(""))) {
                val state = GroupDnsUi.state("", auto, "", isSubscription)
                assertEquals(GroupDnsUi.Summary.UNSET, state.summary)
                assertEquals("", state.content)
                assertFalse(state.hasAutomatic)
                assertNull(state.edited(""))
            }
        }
    }

    @Test fun absentAutoFieldsDoNotClaimToBeProvided() {
        val auto = GroupDnsUi.automatic("hosts: {node.example: 192.0.2.1}")
        for (value in listOf(auto.server, auto.direct, auto.privateDns)) {
            assertEquals("", value)
            assertEquals(GroupDnsUi.Summary.UNSET, GroupDnsUi.state("", value, "file", true).summary)
        }
        assertEquals(GroupDnsUi.Summary.FILE, GroupDnsUi.state("", auto.hosts, "file", true).summary)
    }

    @Test fun explicitSourcesOverrideTheGroupType() {
        for (isSubscription in listOf(false, true)) {
            assertEquals(GroupDnsUi.Summary.SUBSCRIPTION,
                GroupDnsUi.state("", "1.1.1.1", "subscription", isSubscription).summary)
            assertEquals(GroupDnsUi.Summary.FILE,
                GroupDnsUi.state("", "1.1.1.1", "file", isSubscription).summary)
        }
    }

    @Test fun legacyMissingSourceFallsBackToGroupTypeOnlyWhenContentExists() {
        assertEquals(GroupDnsUi.Summary.FILE, GroupDnsUi.state(null, "1.1.1.1", null, false).summary)
        assertEquals(GroupDnsUi.Summary.SUBSCRIPTION, GroupDnsUi.state("", "1.1.1.1", "", true).summary)
        assertEquals(GroupDnsUi.Summary.UNSET, GroupDnsUi.state(null, null, "subscription", true).summary)
    }

    @Test fun manualOverridesAreCustomAndPreserveExactText() {
        val raw = "# Keep indentation and trailing newline\nhosts:\n  node.example: 192.0.2.20\n"
        for (source in listOf("file", "subscription", "")) {
            val state = GroupDnsUi.state(raw, "hosts: {node.example: 192.0.2.1}", source, false)
            assertEquals(GroupDnsUi.Summary.CUSTOM, state.summary)
            assertEquals(raw, state.manual)
            assertEquals(raw, state.content)
            assertNull(state.edited(raw))
        }
    }

    @Test fun whitespaceManualValuesStillInheritWithoutBeingNormalizedOnSave() {
        val state = GroupDnsUi.state(" \n ", "1.1.1.1", "file", false)
        assertEquals("1.1.1.1", state.content)
        assertEquals(GroupDnsUi.Summary.FILE, state.summary)
        assertNull(state.edited(state.content))
        assertEquals(" \n ", state.manual)
    }

    @Test fun disabledManualValuesHaveEmptyEditorsForEveryField() {
        for (auto in fields(GroupDnsUi.automatic(imported))) {
            for (manual in listOf("{}", "  {}\n")) {
                val state = GroupDnsUi.state(manual, auto, "subscription", true)
                assertEquals(GroupDnsUi.Summary.UNSET, state.summary)
                assertEquals("", state.content)
                assertFalse(state.hasContent)
                assertEquals(auto.isNotBlank(), state.hasAutomatic)
                assertNull(state.edited(""))
                assertEquals(manual, state.manual)
            }
        }
    }

    @Test fun anEmptyAutomaticMapIsNotDisplayedAsImportedContent() {
        val state = GroupDnsUi.state("", " {} \n", "subscription", true)
        assertEquals("", state.content)
        assertEquals(GroupDnsUi.Summary.UNSET, state.summary)
        assertFalse(state.hasAutomatic)
        assertNull(state.edited(""))
    }

    @Test fun savingAutomaticContentUnchangedNeverCreatesManualOverrides() {
        for (auto in fields(GroupDnsUi.automatic(imported))) {
            val state = GroupDnsUi.state("", auto, "subscription", true)
            assertEquals(auto, state.content)
            assertNull(state.edited(state.content))
            assertEquals("", state.manual)
        }
    }

    @Test fun clearingAutomaticContentWritesDisableSentinelForEveryField() {
        for (auto in fields(GroupDnsUi.automatic(imported)).filter { it.isNotBlank() }) {
            val state = GroupDnsUi.state("", auto, "subscription", true)
            assertEquals("{}", state.edited(""))
            assertEquals("{}", state.edited(" \n"))
        }
    }

    @Test fun clearingManualContentOverAutoDoesNotResurrectAuto() {
        val state = GroupDnsUi.state("192.0.2.1", "1.1.1.1", "file", false)
        val edited = state.edited("")
        assertEquals("{}", edited)
        val reopened = GroupDnsUi.state(edited, state.automatic, "file", false)
        assertEquals("", reopened.content)
        assertEquals(GroupDnsUi.Summary.UNSET, reopened.summary)
    }

    @Test fun clearingCustomWithoutAutoKeepsAnEmptyManualValue() {
        assertEquals("", GroupDnsUi.state("192.0.2.1", "", "", false).edited(""))
    }

    @Test fun changeComparisonUsesTheActualInitialEditorText() {
        val state = GroupDnsUi.state("", "1.1.1.1\n", "file", false)
        assertNull(state.edited("1.1.1.1", initialContent = "1.1.1.1"))
        assertEquals("1.1.1.2", state.edited("1.1.1.2", initialContent = "1.1.1.1"))
    }

    @Test fun editedManualTextIsNotTrimmedOrReformatted() {
        val state = GroupDnsUi.state("", "hosts: {node.example: 192.0.2.1}", "file", false)
        val raw = "hosts:\n  node.example:\n    - 192.0.2.2\n"
        assertEquals(raw, state.edited(raw))
        assertNull(SubscriptionDns.validateHostsConfig(raw))
    }

    @Test fun autoEditorsContainOnlyTheirSupportedConfigFields() {
        val auto = GroupDnsUi.automatic(imported)
        assertEquals("", auto.server)
        assertEquals("", auto.direct)
        assertEquals(auto, GroupDnsUi.automatic(SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(imported))))
        assertTrue(SubscriptionDns.isValidEndpointList(auto.server))
        assertTrue(SubscriptionDns.isValidEndpointList(auto.direct))
        assertNull(SubscriptionDns.validatePrivateConfig(auto.privateDns))
        assertNull(SubscriptionDns.validateHostsConfig(auto.hosts))
        val privateConfig = SubscriptionDns.effective("", auto.privateDns, "", "")
        assertEquals(listOf("https://private.example/query", "192.0.2.54"), privateConfig.policy["+.corp.example"])
        assertEquals(listOf("tcp://192.0.2.55:53"), privateConfig.serverPolicy["node.example"])
        assertTrue(privateConfig.bootstrap.isEmpty())
        assertFalse(auto.privateDns.contains("default-nameserver"))
        val inherited = SubscriptionDns.effective(imported, auto.privateDns, "", "")
        assertEquals(listOf("9.9.9.9"), inherited.bootstrap)
        assertTrue(privateConfig.serverDns.isEmpty())
        assertTrue(privateConfig.directDns.isEmpty())
        assertTrue(privateConfig.hosts.isEmpty())
        assertFalse(auto.hosts.startsWith("hosts:"))
        assertFalse(auto.privateDns.startsWith("dns:"))
        val hosts = SubscriptionDns.effective("", "", auto.hosts, "")
        assertEquals(listOf("192.0.2.10", "2001:db8::10"), hosts.hosts["node.example"])
        assertTrue(hosts.policy.isEmpty())
        for (text in fields(auto)) {
            assertFalse(text.contains("do-not-display"))
            assertFalse(text.contains("proxies:"))
        }
    }

    @Test fun manualResolversRemainCustomAcrossLocalAndRemoteAutomaticRefresh() {
        val original = GroupDnsUi.automatic(imported)
        val next = GroupDnsUi.automatic(imported.replace("9.9.9.9", "192.0.2.53"))
        for (source in listOf("file", "subscription")) {
            for (auto in listOf(original, next, GroupDnsUi.automatic(""))) {
                for (field in listOf(auto.server, auto.direct)) {
                    val blank = GroupDnsUi.state("", field, source, true)
                    assertEquals(GroupDnsUi.Summary.UNSET, blank.summary)
                    assertEquals("", blank.content)
                    assertNull(blank.edited(""))
                    val custom = GroupDnsUi.state("tls://192.0.2.54", field, source, true)
                    assertEquals(GroupDnsUi.Summary.CUSTOM, custom.summary)
                    assertEquals("tls://192.0.2.54", custom.content)
                    assertNull(custom.edited(custom.content))
                    assertEquals("", custom.edited(""))
                }
            }
        }
    }

    @Test fun invalidOldAutomaticDefaultsDoNotHideValidPrivatePolicies() {
        val auto = GroupDnsUi.automatic("dns: {proxy-server-nameserver: invalid, direct-nameserver: [], nameserver-policy: {node.example: 192.0.2.53}}")
        assertEquals("", auto.server); assertEquals("", auto.direct)
        assertTrue(auto.privateDns.contains("node.example"))
    }

    @Test fun bootstrapAloneDoesNotClaimPrivateDomainPolicyWasProvided() {
        val auto = GroupDnsUi.automatic("dns: {default-nameserver: [9.9.9.9]}")
        assertEquals("", auto.privateDns)
        assertEquals("", auto.server)
        assertEquals("", auto.direct)
        assertEquals("", auto.hosts)
        assertEquals(GroupDnsUi.Summary.UNSET,
            GroupDnsUi.state("", auto.privateDns, "file", false).summary)
    }

    @Test fun brokenImportedSnapshotsDoNotCrashOrCreateFakeSettings() {
        for (text in listOf("[bad, root]", "dns: [invalid]", "hosts: {broken}", "x: &cycle [*cycle]")) {
            assertEquals(GroupDnsUi.Automatic(), GroupDnsUi.automatic(text))
        }
        val custom = GroupDnsUi.state("1.1.1.1", GroupDnsUi.automatic("dns: [invalid]").server, "file", false)
        assertEquals(GroupDnsUi.Summary.CUSTOM, custom.summary)
        assertEquals("1.1.1.1", custom.content)
    }

    @Test fun hostsButtonRequiresUsableMappingsNotJustNonblankText() {
        for (text in listOf("", " \n", "{}", "hosts: {}", "# no mappings", "hosts: [bad]")) {
            assertFalse(text, GroupDnsUi.hasHosts(text))
        }
        assertTrue(GroupDnsUi.hasHosts("node.example: 192.0.2.1"))
        assertTrue(GroupDnsUi.hasHosts("hosts: {node.example: target.example}"))
        assertFalse(GroupDnsUi.hasHosts("hosts: {a.example: b.example, b.example: a.example}"))
    }

    @Test fun originalDomainModeCanReapplyAutomaticHostsAfterEditing() {
        val auto = GroupDnsUi.automatic(imported).hosts
        val initial = GroupDnsUi.state("", auto, "file", false)
        val originalMode = GroupDnsUi.hostsState(initial, mapped = false)
        assertFalse(originalMode.mapped)
        assertTrue(originalMode.canSwap)
        assertNull(initial.edited(initial.content))
        val edited = GroupDnsUi.state(initial.edited("node.example: 192.0.2.8"), auto, "file", false)
        assertEquals(GroupDnsUi.HostsState(mapped = false, canSwap = true),
            GroupDnsUi.hostsState(edited, mapped = false))
        val cleared = GroupDnsUi.state(edited.edited(""), auto, "file", false)
        assertEquals(GroupDnsUi.HostsState(mapped = false, canSwap = false),
            GroupDnsUi.hostsState(cleared, mapped = false))
        val reedited = GroupDnsUi.state(cleared.edited("node.example: 192.0.2.9"), auto, "file", false)
        assertTrue(GroupDnsUi.hostsState(reedited, mapped = false).canSwap)
        assertTrue(GroupDnsUi.hostsState(initial, mapped = true).canSwap)
        assertFalse(GroupDnsUi.hostsState(GroupDnsUi.state("", "", "", false), mapped = false).canSwap)
    }

    @Test fun clearingHostsDisablesButtonAndEditingEnablesItAgain() {
        val auto = GroupDnsUi.automatic(imported).hosts
        val initial = GroupDnsUi.state("", auto, "file", false)
        assertTrue(GroupDnsUi.hasHosts(initial.content))
        val cleared = GroupDnsUi.state(initial.edited(""), auto, "file", false)
        assertFalse(GroupDnsUi.hasHosts(cleared.content))
        val custom = GroupDnsUi.state(cleared.edited("node.example: 192.0.2.2"), auto, "file", false)
        assertTrue(GroupDnsUi.hasHosts(custom.content))
        assertEquals(GroupDnsUi.Summary.CUSTOM, custom.summary)
    }
}
