package io.nekohasekai.sagernet.group

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Synthetic only: mirrors the shared profile's counts and field shapes, not its secrets. */
internal object SingBoxImportFixture {
    /** Real attachment's dependency shape, with only documentation addresses and synthetic credentials. */
    fun coreProfile(): JsonObject = JsonParser.parseString("""{
      "endpoints":[],"inbounds":[],
      "outbounds":[
        {"type":"socks","tag":"node","server":"node.example.invalid","server_port":1080,
         "username":"synthetic-user","password":"synthetic-password","domain_resolver":{"server":"node-dns","strategy":"ipv4_only"}},
        {"type":"selector","tag":"auto","outbounds":["node"],"default":"node"},
        {"type":"direct","tag":"direct","domain_resolver":{"server":"node-dns","strategy":"ipv4_only"}}
      ],
      "dns":{"servers":[
        {"type":"local","tag":"local","domain_resolver":{"server":"bootstrap","strategy":"ipv4_only"}},
        {"type":"udp","tag":"udp","server":"192.0.2.53","server_port":53,"domain_resolver":{"server":"bootstrap","strategy":"ipv4_only"}},
        {"type":"https","tag":"https-ip","server":"192.0.2.54","server_port":443,"path":"/dns-query","domain_resolver":{"server":"bootstrap","strategy":"ipv4_only"}},
        {"type":"https","tag":"https-domain","server":"resolver.example.invalid","path":"/dns-query","domain_resolver":{"server":"bootstrap","strategy":"ipv4_only"}},
        {"type":"https","tag":"https-proxy","server":"192.0.2.55","path":"/custom","detour":"auto","domain_resolver":{"server":"bootstrap","strategy":"ipv4_only"}},
        {"type":"batch","tag":"node-dns","servers":["local","udp"],"strategy":"prefer_ipv4","idle_timeout":"1m"},
        {"type":"batch","tag":"bootstrap","servers":["local","https-ip","udp"]},
        {"type":"batch","tag":"general","servers":["node-dns","https-domain","https-proxy"],"timeout":"1s"}
      ],"final":"general"},
      "route":{"default_domain_resolver":{"server":"node-dns","strategy":"ipv4_only"}}
    }""").asJsonObject

    /** Same 28 VLESS + 15 Hysteria2 + URLTest graph, with valid synthetic wire fields for core decode. */
    fun coreSharedProfile(): JsonObject = sharedProfile().apply {
        val dnsGraph = coreProfile().getAsJsonObject("dns")
        add("dns", dnsGraph)
        add("route", coreProfile().getAsJsonObject("route"))
        getAsJsonArray("outbounds").forEach { value ->
            val node = value.asJsonObject
            node.remove("future_extension")
            if (node.has("server")) {
                node.add("domain_resolver", JsonParser.parseString("""{"server":"node-dns","strategy":"ipv4_only"}"""))
                // TLS identity and all other protocol fields are tested by the
                // lossless fixture above. Core startup needs no remote TLS keys.
                node.getAsJsonObject("tls")?.apply {
                    remove("utls"); remove("reality"); remove("certificate_public_key_sha256")
                }
            }
        }
    }

    fun sharedProfile(): JsonObject {
        val outbounds = JsonArray()
        repeat(43) { index ->
            val node = JsonObject().apply {
                addProperty("type", if (index < 28) "vless" else "hysteria2")
                addProperty("tag", "node-$index")
                addProperty("server", "node-$index.example.invalid")
                addProperty("server_port", 443)
                addProperty("domain_resolver", "bootstrap")
                if (index < 28) {
                    addProperty("uuid", "00000000-0000-4000-8000-000000000001")
                    addProperty("flow", "xtls-rprx-vision")
                    addProperty("packet_encoding", "xudp")
                    add("tls", JsonParser.parseString("""{"enabled":true,"server_name":"tls.example.invalid","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"synthetic-key","short_id":"0000"}}"""))
                    add("transport", JsonParser.parseString("""{"type":"ws","path":"/synthetic","headers":{"Host":"ws.example.invalid","X-Test":"value"},"max_early_data":2048,"early_data_header_name":"Sec-WebSocket-Protocol"}"""))
                } else {
                    addProperty("password", "synthetic-password")
                    add("server_ports", JsonParser.parseString("""["443:444","8443"]"""))
                    add("tls", JsonParser.parseString("""{"enabled":true,"server_name":"tls.example.invalid","insecure":false,"certificate_public_key_sha256":["synthetic-pin"]}"""))
                }
                add("future_extension", JsonParser.parseString("""{"enabled":true,"opaque":[1,2,"node-0"]}"""))
            }
            outbounds.add(node)
        }
        outbounds.add(JsonObject().apply {
            addProperty("type", "urltest"); addProperty("tag", "auto")
            add("outbounds", JsonArray().apply { repeat(43) { add("node-$it") } })
            addProperty("url", "https://test.example.invalid/generate_204")
            addProperty("interval", "3m"); addProperty("idle_timeout", "30m")
            addProperty("tolerance", 50); addProperty("interrupt_exist_connections", true)
        })
        outbounds.add(JsonParser.parseString("""{"type":"direct","tag":"direct"}"""))
        outbounds.add(JsonParser.parseString("""{"type":"block","tag":"block"}"""))
        return JsonObject().apply {
            addProperty("app_version", "synthetic")
            add("endpoints", JsonArray()); add("services", JsonArray())
            add("outbounds", outbounds)
            add("inbounds", JsonArray().apply { repeat(3) { index -> add(JsonObject().apply {
                addProperty("type", "mixed"); addProperty("tag", "mixed-$index")
                addProperty("listen", "127.0.0.1"); addProperty("listen_port", 1080 + index)
                addProperty("set_system_proxy", false)
                add("users", JsonParser.parseString("""[{"username":"local","password":"synthetic"}]"""))
            }) } })
            add("dns", JsonParser.parseString("""{"servers":[{"type":"local","tag":"local","domain_resolver":"bootstrap"},{"type":"udp","tag":"udp","server":"192.0.2.53"},{"type":"https","tag":"https","server":"dns.example.invalid","domain_resolver":"udp"},{"type":"batch","tag":"bootstrap","servers":["local","udp","https"],"strategy":"prefer_ipv4","future_dns_option":{"keep":true}},{"type":"https","tag":"remote","server":"remote.example.invalid","detour":"auto","domain_resolver":"udp"}],"final":"remote","rules":[{"domain_suffix":["example.invalid"],"server":"remote"}]}"""))
            val dnsServers = getAsJsonObject("dns").getAsJsonArray("servers")
            for ((tag, type) in listOf("udp-extra-1" to "udp", "udp-extra-2" to "udp",
                "https-extra-1" to "https", "https-extra-2" to "https", "https-extra-3" to "https", "https-extra-4" to "https",
                "hosts" to "hosts", "predefined" to "predefined", "batch-extra-1" to "batch", "batch-extra-2" to "batch", "batch-extra-3" to "batch", "fakeip" to "fakeip")) {
                dnsServers.add(JsonObject().apply {
                    addProperty("type", type); addProperty("tag", tag)
                    when (type) {
                        "udp", "https" -> { addProperty("server", "192.0.2.53"); addProperty("domain_resolver", "udp") }
                        "batch" -> add("servers", JsonArray().apply { add("udp") })
                        "hosts" -> add("predefined", JsonParser.parseString("""{"safe.example.invalid":["192.0.2.1"]}"""))
                        "predefined" -> addProperty("rcode", "NXDOMAIN")
                        "fakeip" -> addProperty("inet4_range", "198.18.0.0/15")
                    }
                })
            }
            add("log", JsonObject().apply { addProperty("level", "warn") })
            add("ntp", JsonObject().apply { addProperty("enabled", false) })
            add("route", JsonParser.parseString("""{"final":"auto","default_domain_resolver":"bootstrap","rules":[{"action":"route","outbound":"auto"}]}"""))
            add("experimental", JsonParser.parseString("""{"cache_file":{"enabled":true},"future_global":{"keep":true}}"""))
        }
    }
}

class SingBoxJsonImportTest {
    private fun obj(text: String) = JsonParser.parseString(text).asJsonObject
    private fun parse(text: String) = SingBoxJsonImport.parse(text)!!

    @Test fun emptyEndpointArraysProduce43RealNodesAndAUrltestNotOneConfig() {
        val source = SingBoxImportFixture.sharedProfile()
        assertEquals(46, source.getAsJsonArray("outbounds").size())
        assertEquals(17, source.getAsJsonObject("dns").getAsJsonArray("servers").size())
        assertEquals(3, source.getAsJsonArray("inbounds").size())
        val profiles = parse(source.toString())
        assertEquals(44, profiles.size)
        assertEquals(28, profiles.count { obj(it.config)["type"].asString == "vless" })
        assertEquals(15, profiles.count { obj(it.config)["type"].asString == "hysteria2" })
        assertEquals("urltest", obj(profiles.last().config)["type"].asString)
        assertTrue(profiles.none { it.full })
        profiles.take(43).forEachIndexed { i, profile ->
            assertEquals(source.getAsJsonArray("outbounds")[i], obj(profile.config))
            val context = obj(profile.context)
            assertFalse(context.has("inbounds")); assertFalse(context.has("experimental")); assertFalse(context.has("route"))
            assertEquals(1, context.getAsJsonArray("outbounds").size())
            assertEquals(4, context.getAsJsonObject("dns").getAsJsonArray("servers").size())
        }
    }

    @Test fun urltestKeepsMembersDnsClosureAndOpaqueFields() {
        val profile = parse(SingBoxImportFixture.sharedProfile().toString()).last()
        val prepared = SingBoxJsonImport.prepare(profile.config, profile.context, "p44_", "Selected Auto")
        assertEquals(43, prepared.outbounds.size); assertEquals(4, prepared.dnsServers.size)
        assertEquals("Selected Auto", obj(prepared.outbound)["tag"].asString)
        assertEquals("p44_o_node-0", obj(prepared.outbound).getAsJsonArray("outbounds")[0].asString)
        val node = prepared.outbounds.map(::obj).first()
        assertEquals("node-0", node["future_extension"].asJsonObject["opaque"].asJsonArray[2].asString)
        assertEquals("p44_d_bootstrap", node["domain_resolver"].asString)
        val policy = prepared.dnsServers.map(::obj).single { it["tag"].asString == "p44_d_bootstrap" }
        assertEquals("neko-policy", policy["type"].asString)
        assertFalse(policy.has("servers"))
        assertFalse(policy.has("strategy"))
        assertTrue(policy.getAsJsonArray("fallback").all { it.asString.startsWith("p44_d_") })
    }

    @Test fun everyImportedDnsBatchBecomesRunnablePolicyAndReferencesRemainClosed() {
        val profile = parse(SingBoxImportFixture.sharedProfile().toString()).first()
        val prepared = SingBoxJsonImport.prepare(profile.config, profile.context, "node_", "selected")
        val servers = prepared.dnsServers.map(::obj)
        assertTrue(servers.isNotEmpty())
        assertTrue(servers.all { it["type"].asString != "batch" })
        val tags = servers.map { it["tag"].asString }.toSet()
        servers.forEach { server ->
            server["domain_resolver"]?.let { resolver ->
                val tag = if (resolver.isJsonObject) resolver.asJsonObject["server"].asString else resolver.asString
                assertTrue("missing domain_resolver $tag", tag in tags)
            }
            server["fallback"]?.asJsonArray?.forEach { assertTrue(it.asString in tags) }
        }
        val root = obj(prepared.outbound)
        val rootResolver = root["domain_resolver"]?.asString
        if (rootResolver != null) assertTrue(rootResolver in tags)
    }

    @Test fun oldStoredBatchContextsNormalizeIdempotentlyAndKeepNodeFields() {
        val source = SingBoxImportFixture.coreProfile()
        val original = source.deepCopy()
        val root = source.getAsJsonArray("outbounds")[0].asJsonObject
        val first = SingBoxJsonImport.prepare(root.toString(), source.toString(), "first_", "node")
        assertEquals(original, source)
        val node = obj(first.outbound)
        assertEquals("synthetic-user", node["username"].asString)
        assertEquals("synthetic-password", node["password"].asString)
        assertEquals("ipv4_only", node["domain_resolver"].asJsonObject["strategy"].asString)
        assertEquals(listOf("first_d_local", "first_d_udp"), first.dnsServers.map(::obj)
            .single { it["tag"].asString == "first_d_node-dns" }.getAsJsonArray("fallback").map { it.asString })
        val local = first.dnsServers.map(::obj).single { it["type"].asString == "local" }
        assertFalse(local.has("domain_resolver"))
        val profile = parse(source.toString()).first()
        val rebuilt = SingBoxJsonImport.prepare(profile.config, profile.context, "first_", "node")
        assertEquals(first, rebuilt)
    }

    @Test fun batchMetadataAndPolicyRulesKeepTheirOwnPrivateReferences() {
        val source = """{"outbounds":[{"type":"socks","tag":"node","domain_resolver":{"server":"batch","strategy":"prefer_ipv6"}},{"type":"direct","tag":"front"}],"dns":{"servers":[{"type":"local","tag":"a"},{"type":"local","tag":"b"},{"type":"batch","tag":"batch","servers":["a"],"domain_resolver":"b","detour":"front","timeout":"1s","strategy":"prefer_ipv4","idle_timeout":"1m","unknown_batch_field":true,"rules":[{"pattern":"private.example.invalid","servers":["b"]}],"server_rules":[{"pattern":"node.example.invalid","servers":["b"]}]}]}}"""
        val p = parse(source).first()
        val prepared = SingBoxJsonImport.prepare(p.config, p.context, "p_", "selected")
        val policy = prepared.dnsServers.map(::obj).single { it["type"].asString == "neko-policy" }
        assertEquals(setOf("tag", "type", "fallback", "domain_resolver", "detour", "timeout", "rules", "server_rules"), policy.keySet())
        assertEquals("p_d_b", policy["domain_resolver"].asString)
        assertEquals("p_o_front", policy["detour"].asString)
        assertEquals("1s", policy["timeout"].asString)
        for (key in listOf("rules", "server_rules")) {
            assertEquals("p_d_b", policy.getAsJsonArray(key)[0].asJsonObject.getAsJsonArray("servers")[0].asString)
        }
        assertEquals("prefer_ipv6", obj(prepared.outbound)["domain_resolver"].asJsonObject["strategy"].asString)
    }

    @Test fun malformedBatchUpstreamsFailAtomically() {
        for (servers in listOf("[]", "[1]", "[\"\"]", "[\"missing\"]", "[\"dns\"]")) {
            val source = """{"outbounds":[{"type":"socks","tag":"node","domain_resolver":"dns"}],"dns":{"servers":[{"type":"batch","tag":"dns","servers":$servers}]}}"""
            try { parse(source); fail("malformed batch must not produce cards") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun selectorDetourDnsAndRootBackReferencesAreRemappedWithoutCyclesInTraversal() {
        val source = """{"outbounds":[{"type":"selector","tag":"select","outbounds":["leaf"],"default":"leaf","interrupt_exist_connections":true},{"type":"future-protocol","tag":"leaf","server":"leaf.example.invalid","detour":"front","domain_resolver":{"server":"dns","strategy":"prefer_ipv6"},"opaque":{"tag":"front"}},{"type":"socks","tag":"front","server":"front.example.invalid","server_port":1080}],"dns":{"servers":[{"type":"https","tag":"dns","server":"dns.example.invalid","detour":"select","domain_resolver":"bootstrap"},{"type":"local","tag":"bootstrap"}]}}"""
        val profile = parse(source).first()
        val result = SingBoxJsonImport.prepare(profile.config, profile.context, "isolated_", "selection")
        assertEquals(2, result.outbounds.size); assertEquals(2, result.dnsServers.size)
        val leaf = result.outbounds.map(::obj).single { it["type"].asString == "future-protocol" }
        assertEquals("isolated_o_front", leaf["detour"].asString)
        assertEquals("front", leaf["opaque"].asJsonObject["tag"].asString)
        assertEquals("isolated_d_dns", leaf["domain_resolver"].asJsonObject["server"].asString)
        assertEquals("prefer_ipv6", leaf["domain_resolver"].asJsonObject["strategy"].asString)
        assertEquals("selection", result.dnsServers.map(::obj).single { it["type"].asString == "https" }["detour"].asString)
        assertEquals("isolated_o_leaf", obj(result.outbound)["default"].asString)
    }

    @Test fun sourceDefaultResolverIsInheritedByNestedDialers() {
        val source = """{"outbounds":[{"type":"selector","tag":"all","outbounds":["node"]},{"type":"socks","tag":"node","server":"node.example.invalid","server_port":1080}],"dns":{"servers":[{"type":"local","tag":"local"}]},"route":{"default_domain_resolver":{"server":"local","strategy":"prefer_ipv4"}}}"""
        val p = parse(source).first()
        val prepared = SingBoxJsonImport.prepare(p.config, p.context, "p_", "all")
        assertEquals("p_d_local", obj(prepared.outbounds.single())["domain_resolver"].asJsonObject["server"].asString)
        assertEquals(1, prepared.dnsServers.size)
    }

    @Test fun nonEmptyEndpointsServicesAndCloudflaredStayWhole() {
        listOf("""{"endpoints":[{"type":"openvpn-client","tag":"vpn","future":true}],"outbounds":[{"type":"direct"}]}""",
            """{"services":[{"type":"resolved","future":true}],"outbounds":[{"type":"direct"}]}""",
            """{"inbounds":[{"type":"cloudflared","token":"synthetic"}],"outbounds":[{"type":"direct"}]}""").forEach {
            val p = parse(it).single(); assertTrue(p.full); assertEquals(obj(it), obj(p.config)); assertEquals("", p.context)
        }
    }

    @Test fun standaloneTypedOutboundsAndArraysKeepProtocolFields() {
        val typed = """{"type":"shadowsocks","tag":"ss","server":"node.example.invalid","server_port":443,"method":"2022-blake3-aes-128-gcm","password":"synthetic","future_option":true}"""
        val p = parse(typed).single(); assertFalse(p.full); assertEquals(obj(typed), obj(p.config))
        assertEquals("", p.context)
        assertEquals(2, parse("[$typed,${typed.replace("\"ss\"", "\"other\"")}]").size)
        assertEquals("direct", obj(parse("""{"type":"direct","tag":"d"}""").single().config)["type"].asString)
        assertNull(SingBoxJsonImport.parse("""{"method":"aes-128-gcm","password":"synthetic","server":"node.example.invalid","server_port":443}"""))
    }

    @Test fun invalidOrUnresolvedDependenciesNeverSilentlyDropCards() {
        listOf("""{"outbounds":[{"type":"socks","tag":"a","detour":"missing"}]}""",
            """{"outbounds":[{"type":"socks","tag":"a","domain_resolver":"missing"}]}""",
            """{"outbounds":[{"type":"selector","tag":"a","outbounds":["missing"]}]}""",
            """{"outbounds":[{"type":"socks","tag":"a"},{"type":"socks","tag":"a"}]}""",
            """{"type":"selector","tag":"a","outbounds":["missing"]}""",
            """{"outbounds":[{"type":"direct","tag":"a","detour":"missing"}]}""",
            """{"outbounds":[{"tag":"missing-type"}]}""").forEach {
            try { parse(it); fail("Must reject invalid dependency graph") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun editedRootTagDoesNotBreakStoredDnsDetourBackReference() {
        val source = """{"outbounds":[{"type":"socks","tag":"original","server":"node.example.invalid","server_port":1080,"domain_resolver":"dns"}],"dns":{"servers":[{"type":"local","tag":"dns","detour":"original"}]}}"""
        val profile = parse(source).single()
        val edited = obj(profile.config).apply { addProperty("tag", "edited") }
        val result = SingBoxJsonImport.prepare(edited.toString(), profile.context, "p_", "active")
        assertEquals("active", obj(result.dnsServers.single())["detour"].asString)
        assertEquals("active", obj(result.outbound)["tag"].asString)
        assertEquals(0, result.outbounds.size)
    }

    @Test fun multipleProfilesDoNotSharePrivateDependencyTags() {
        val profiles = parse(SingBoxImportFixture.sharedProfile().toString())
        val a = SingBoxJsonImport.prepare(profiles[0].config, profiles[0].context, "a_", "a")
        val b = SingBoxJsonImport.prepare(profiles[1].config, profiles[1].context, "b_", "b")
        val tagsA = a.dnsServers.map { obj(it)["tag"].asString }.toSet()
        val tagsB = b.dnsServers.map { obj(it)["tag"].asString }.toSet()
        assertTrue(tagsA.intersect(tagsB).isEmpty())
    }
}
