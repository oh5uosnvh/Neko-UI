package io.nekohasekai.sagernet.group

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Android-free resource contract tests, also runnable by the small standalone JVM runner. */
class GroupDnsUiResourceTest {
    private val app = sequenceOf(File("."), File("app")).firstOrNull {
        File(it, "src/main/res/xml/group_preferences.xml").isFile
    } ?: error("Run from the app module or repository root")
    private val appNs = "http://schemas.android.com/apk/res-auto"
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val keys = setOf("subscriptionServerDns", "subscriptionDirectDns", "subscriptionPrivateDns", "subscriptionHosts")

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(app, "src/main/res/$path")).documentElement

    private fun elements(root: Element): List<Element> = buildList {
        add(root)
        val nodes = root.getElementsByTagName("*")
        for (i in 0 until nodes.length) add(nodes.item(i) as Element)
    }

    @Test fun allFourDnsPreferencesHaveNoDependenciesOrMessages() {
        val root = xml("xml/group_preferences.xml")
        val category = elements(root).single { it.getAttributeNS(appNs, "key") == "groupSubscription" }
        val dns = elements(root).filter { it.getAttributeNS(appNs, "key") in keys }
        assertEquals(keys, dns.map { it.getAttributeNS(appNs, "key") }.toSet())
        assertEquals(4, dns.size)
        assertFalse(category.hasAttributeNS(appNs, "isPreferenceVisible"))
        dns.forEach {
            assertSame(category, it.parentNode)
            assertFalse(it.hasAttributeNS(appNs, "dependency"))
            assertFalse(it.hasAttributeNS(appNs, "dialogMessage"))
            assertFalse(it.hasAttributeNS(appNs, "isPreferenceVisible"))
            assertEquals("@string/subscription_dns_unset", it.getAttributeNS(appNs, "summary"))
        }
        assertEquals("io.nekohasekai.sagernet.widget.HostsSwapPreference",
            dns.single { it.getAttributeNS(appNs, "key") == "subscriptionHosts" }.tagName)
    }

    @Test fun dnsCategoryStaysVisibleAndOnlySubscriptionItemsAreHidden() {
        val all = elements(xml("xml/group_preferences.xml"))
        val subscription = all.single { it.getAttributeNS(appNs, "key") == "groupSubscription" }
        val update = all.single { it.getAttributeNS(appNs, "key") == "subscriptionUpdate" }
        val subscriptionKeys = elements(subscription).map { it.getAttributeNS(appNs, "key") }.toSet()
        assertEquals(setOf("groupSubscription", "subscriptionLink", "subscriptionForceResolve",
            "subscriptionDeduplication", "subscriptionFilterMode", "subscriptionFilterRegex") + keys,
            subscriptionKeys)
        assertEquals(setOf("subscriptionUpdate", "subscriptionUpdateWhenConnectedOnly",
            "subscriptionUserAgent", "subscriptionAutoUpdate", "subscriptionAutoUpdateDelay"),
            elements(update).map { it.getAttributeNS(appNs, "key") }.toSet())
        assertTrue(elements(update).none { it.getAttributeNS(appNs, "key") in keys })
        val source = File(app, "src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt").readText()
        assertFalse(source.contains("groupSubscription.isVisible"))
        assertFalse(source.contains("subscriptionUpdate.isVisible"))
        val hiddenKeys = source.substringAfter("val subscriptionOnlyKeys = listOf(").substringBefore(")")
        for (key in listOf("LINK", "FORCE_RESOLVE", "DEDUPLICATION", "FILTER_MODE",
            "UPDATE_WHEN_CONNECTED_ONLY", "USER_AGENT", "AUTO_UPDATE", "AUTO_UPDATE_DELAY")) {
            assertTrue(hiddenKeys.contains("Key.SUBSCRIPTION_$key"))
        }
        for (key in listOf("SERVER_DNS", "DIRECT_DNS", "PRIVATE_DNS", "HOSTS")) {
            assertFalse(hiddenKeys.contains("Key.SUBSCRIPTION_$key"))
        }
        assertTrue(source.contains("subscriptionOnlyKeys.forEach"))
    }

    @Test fun swapIsA48dpBoldTextButtonCenteredIn64dpWidget() {
        val root = xml("layout/layout_hosts_swap_widget.xml")
        assertEquals("64dp", root.getAttributeNS(androidNs, "layout_width"))
        assertEquals("48dp", root.getAttributeNS(androidNs, "layout_height"))
        val button = elements(root).single { it.getAttributeNS(androidNs, "id") == "@+id/hosts_swap" }
        assertEquals("androidx.appcompat.widget.AppCompatButton", button.tagName)
        assertEquals("48dp", button.getAttributeNS(androidNs, "layout_width"))
        assertEquals("48dp", button.getAttributeNS(androidNs, "layout_height"))
        assertEquals("center", button.getAttributeNS(androidNs, "layout_gravity"))
        assertEquals("⇆", button.getAttributeNS(androidNs, "text"))
        assertEquals("24sp", button.getAttributeNS(androidNs, "textSize"))
        assertEquals("bold", button.getAttributeNS(androidNs, "textStyle"))
        assertEquals("?attr/colorPrimary", button.getAttributeNS(androidNs, "textColor"))
        assertFalse(button.getAttributeNS(androidNs, "contentDescription").isEmpty())
        val source = File(app, "src/main/java/io/nekohasekai/sagernet/widget/HostsSwapPreference.kt").readText()
        assertTrue(source.contains("widgetLayoutResource = R.layout.layout_hosts_swap_widget"))
        assertTrue(source.contains(": EditTextPreference("))
        assertTrue(source.contains("var onSwap: () -> Unit"))
        assertTrue(source.contains("var canSwap: Boolean = false"))
    }

    @Test fun allThreeLocalesUseShortSourceSummariesAndRemoveDialogMessages() {
        val locales = mapOf(
            "values" to listOf("Not set", "Provided by subscription", "Provided by local file", "Custom"),
            "values-zh-rCN" to listOf("未设置", "订阅提供", "本地文件提供", "自定义"),
            "values-zh-rTW" to listOf("未設定", "訂閱提供", "本機檔案提供", "自訂"),
        )
        val names = listOf("subscription_dns_unset", "subscription_dns_from_subscription", "subscription_dns_from_file", "subscription_dns_custom")
        for ((locale, summaries) in locales) {
            val strings = elements(xml("$locale/strings.xml")).filter { it.tagName == "string" }
            for ((name, text) in names.zip(summaries)) {
                assertEquals(text, strings.single { it.getAttribute("name") == name }.textContent)
            }
            for (field in listOf("server_dns", "direct_dns", "private_dns", "hosts")) {
                assertTrue(strings.none { it.getAttribute("name") == "subscription_${field}_message" })
            }
        }
    }
}
