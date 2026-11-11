package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CustomRuleUrlContractTest {
    private val app = sequenceOf(File("app"), File(".")).first { File(it, "src/main").exists() }
    @Test fun customUrlImmediatelyFollowsConfigAndUsesRealSourcePersistence() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(app, "src/main/res/xml/route_preferences.xml"))
        val keys = (0 until xml.getElementsByTagName("*").length).map {
            (xml.getElementsByTagName("*").item(it) as org.w3c.dom.Element).getAttribute("app:key")
        }.filter { it.isNotEmpty() }
        assertEquals("routeRuleUrl", keys[keys.indexOf("serverConfig") + 1])
        val editor = File(app, "src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt").readText()
        assertTrue(editor.contains("DataStore.routeRuleUrl = sourceUrl"))
        assertTrue(editor.contains("sourceUrl = requestedUrl"))
        val builder = File(app, "src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(builder.contains("RemoteRulePolicy.effective(it)"))
        assertTrue(builder.contains("RemoteRulePolicy.waitingWithoutConditions(it)"))
        val updater = File(app, "src/main/java/io/nekohasekai/sagernet/database/RemoteRouteUpdater.kt").readText()
        assertTrue(updater.contains("allRules().filter { it.sourceUrl.isNotBlank() }"))
    }
}
