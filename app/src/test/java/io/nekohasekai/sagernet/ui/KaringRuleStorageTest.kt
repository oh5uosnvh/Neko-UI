package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KaringRuleStorageTest {
    @Test fun presetCamelCaseAndUnknownFieldsSurviveStorage() {
        val preset = KaringRuleJson.parsePreset("""{"rules":[{"processName":["app.exe"],"future":{"x":1},"rule_set_items":[{"url":"https://example.com/a.json","type":"source","future":true}]}]}""")
        val input = preset.rules.single()
        assertEquals(listOf("app.exe"), input.processName)
        val entity = RuleEntity().apply { applyKaringRule(input) }
        val output = entity.toKaringRule()
        assertEquals(input.processName, output.processName)
        assertEquals("source", output.ruleSetItems.single().type)
        val raw = JSONObject(KaringRuleJson.toJson(output))
        assertEquals(1, raw.getJSONObject("future").getInt("x"))
        assertTrue(raw.getJSONArray("rule_set_items").getJSONObject(0).getBoolean("future"))
    }

    @Test fun incompleteEnvelopeFallsBackToLegacyColumns() {
        val entity = RuleEntity(config = """{"nekobox_route_v1":{"logic":"and","future":42},"custom":true}""",
            domains = "full:a.example\ndomain:b.example\nkeyword:foo\nregexp:bar",
            ip = "192.0.2.0/24", port = "80,100:200", source = "10.0.0.0/8",
            sourcePort = "53", network = "tcp", protocol = "http", packages = setOf("app"))
        val model = entity.toKaringRule()
        assertEquals(listOf("a.example"), model.domain)
        assertEquals(listOf("b.example"), model.domainSuffix)
        assertEquals(listOf("192.0.2.0/24"), model.ipCidr)
        assertEquals(listOf("80"), model.port)
        assertEquals(listOf("100:200"), model.portRange)
        assertEquals(listOf("53"), model.sourcePorts)
        assertEquals(listOf("tcp"), model.network)
        assertEquals(listOf("app"), model.packageNames)
        entity.applyKaringRule(model)
        assertEquals(model, entity.toKaringRule())
        val config = JSONObject(entity.config)
        assertTrue(config.getBoolean("custom"))
        assertEquals(42, config.getJSONObject(KARING_CONFIG_KEY).getInt("future"))
        assertTrue(entity.domains.contains("full:a.example"))
    }

    @Test fun editorExtensionKeepsUnknownAndProcessFields() {
        val entity = RuleEntity(config = """{"custom":{"foo":1},"nekobox_route_v1":{"process_name":["x"],"future":5}}""")
        RouteRuleExtension.from(entity).apply { domain = listOf("example.com") }.writeTo(entity)
        val root = JSONObject(entity.config)
        assertEquals(1, root.getJSONObject("custom").getInt("foo"))
        assertEquals(5, root.getJSONObject(KARING_CONFIG_KEY).getInt("future"))
        assertEquals("x", root.getJSONObject(KARING_CONFIG_KEY).getJSONArray("process_name").getString(0))
    }

    @Test fun invalidCustomConfigIsNotOverwritten() {
        val entity = RuleEntity(config = "not-json")
        try {
            entity.applyKaringRule(KaringRuleModel())
            fail("expected an error")
        } catch (_: IllegalArgumentException) {
            assertEquals("not-json", entity.config)
        }
    }
}
