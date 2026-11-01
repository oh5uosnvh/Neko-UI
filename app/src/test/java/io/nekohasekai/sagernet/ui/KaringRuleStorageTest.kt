package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class KaringRuleStorageTest {
    @Test fun presetDefaultsToOrButExplicitAndIsPreserved() {
        val preset = KaringRuleJson.parsePreset("""{"rules":[{"name":"default"},{"name":"explicit","or":false}]}""")
        assertTrue(preset.rules[0].or)
        assertFalse(preset.rules[1].or)
    }
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

    @Test fun storedLogicAndOpaqueFieldsSurviveNameEdit() {
        for (or in listOf(true, false)) {
            val original = KaringRuleJson.parsePreset("""{"rules":[{"or":$or,"processName":["demo.exe"],"future":{"keep":true},"rule_set_items":[{"url":"https://example.com/rules","type":"source","extra":7}]}]}""").rules.single()
            val entity = RuleEntity(name = "old").apply { applyKaringRule(original) }
            val read = entity.toKaringRule()
            entity.name = "renamed"
            entity.applyKaringRule(read)
            val result = entity.toKaringRule()
            assertEquals(or, result.or)
            assertEquals("renamed", entity.name)
            assertEquals(listOf("demo.exe"), result.processName)
            assertEquals("source", result.ruleSetItems.single().type)
            val json = JSONObject(KaringRuleJson.toJson(result))
            assertTrue(json.getJSONObject("future").getBoolean("keep"))
            assertEquals(7, json.getJSONArray("rule_set_items").getJSONObject(0).getInt("extra"))
        }
    }

    @Test fun missingEnvelopeLogicDefaultsToOrButExplicitAndStaysAnd() {
        val default = RuleEntity(config = """{"nekobox_route_v1":{}}""")
        val explicitAnd = RuleEntity(config = """{"nekobox_route_v1":{"logic":"and"}}""")
        assertTrue(default.toKaringRule().or)
        assertFalse(explicitAnd.toKaringRule().or)
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
