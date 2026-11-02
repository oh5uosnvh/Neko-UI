package io.nekohasekai.sagernet.group;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Runs the production parser, without Android classes or real resolver queries. */
public class SubscriptionDnsTest {
    private static SubscriptionDns.Config parse(String yaml) {
        return SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(yaml));
    }

    @Test public void policyAcceptsStringFlowAndBlockLists() {
        SubscriptionDns.Config config = parse("dns:\n  nameserver-policy:\n"
                + "    'Exact.Example.': 1.1.1.1\n"
                + "    '+.Example.': [8.8.8.8, 'tcp://9.9.9.9:8080']\n"
                + "    '*.Other.Example':\n      - tls://1.0.0.1\n      - 8.8.4.4\n");
        assertEquals(Collections.singletonList("1.1.1.1"), config.policy.get("exact.example"));
        assertEquals(Arrays.asList("8.8.8.8", "tcp://9.9.9.9:8080"), config.policy.get("+.example"));
        assertEquals(Arrays.asList("tls://1.0.0.1", "8.8.4.4"), config.policy.get("*.other.example"));
    }

    @Test public void endpointsKeepTransportPortAndDohPath() {
        SubscriptionDns.Endpoint tcp = SubscriptionDns.endpoint("tcp://192.0.2.1:8080");
        assertEquals("tcp", tcp.scheme);
        assertEquals(8080, tcp.port);
        SubscriptionDns.Endpoint doh = SubscriptionDns.endpoint("https://DNS.Example:8443/custom/query");
        assertEquals("dns.example", doh.host);
        assertEquals(8443, doh.port);
        assertEquals("/custom/query", doh.path);
        Map<String, Object> options = doh.serverOptions("test-dns", "bootstrap");
        assertEquals("bootstrap", options.get("domain_resolver"));
        assertFalse(options.containsKey("detour"));
    }

    @Test public void duplicateYamlKeysAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SubscriptionDns.loadDocument("dns: {}\ndns: {}\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("dns:\n  nameserver-policy:\n    a.example: 1.1.1.1\n    a.example: 8.8.8.8\n"));
    }

    @Test(timeout = 3000) public void cyclicYamlAliasesAreRejectedWithoutRecursion() {
        assertThrows(IllegalArgumentException.class,
                () -> SubscriptionDns.loadDocument("other: &loop {again: *loop}\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("hosts: {a.example: &loop [*loop]}\n"));
    }

    @Test public void dangerousYamlTagsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SubscriptionDns.loadDocument("value: !!java.net.URL ['https://invalid.example']\n"));
    }

    private static final String IMPORTED = "hosts: {node.example: 192.0.2.10}\n"
            + "dns:\n  nameserver-policy: {'+.example': 1.1.1.1}\n"
            + "  proxy-server-nameserver-policy: {node.example: 8.8.8.8}\n"
            + "  proxy-server-nameserver: 9.9.9.9\n  default-nameserver: 1.0.0.1\n";

    @Test public void patternsNormalizeCaseTrailingDotAndIdn() {
        assertEquals("+.mixed.example", SubscriptionDns.pattern(" +.MiXeD.Example. "));
        assertEquals("*.mixed.example", SubscriptionDns.pattern("*.MiXeD.Example."));
        assertEquals(".mixed.example", SubscriptionDns.pattern(".MiXeD.Example."));
        assertEquals("xn--bcher-kva.example", SubscriptionDns.domain("Bücher.Example."));
        for (Object invalid : Arrays.asList("", ".", "bad..example", "bad/example", "*bad.example", 7)) {
            assertThrows(IllegalArgumentException.class, () -> SubscriptionDns.pattern(invalid));
        }
    }

    @Test public void wildcardBoundariesAndSpecificityDoNotDependOnMapOrder() {
        Map<String, Object> patterns = new LinkedHashMap<>();
        for (String key : Arrays.asList("+.example", ".example", "*.example", "+.sub.example", "a.sub.example")) {
            patterns.put(key, Boolean.TRUE);
        }
        for (int pass = 0; pass < 2; pass++) {
            assertEquals("+.example", SubscriptionDns.bestMatch(patterns, "EXAMPLE."));
            assertEquals("*.example", SubscriptionDns.bestMatch(patterns, "X.Example."));
            assertEquals(".example", SubscriptionDns.bestMatch(patterns, "x.y.example"));
            assertEquals("+.sub.example", SubscriptionDns.bestMatch(patterns, "x.sub.example"));
            assertEquals("a.sub.example", SubscriptionDns.bestMatch(patterns, "A.Sub.Example."));
            assertNull(SubscriptionDns.bestMatch(patterns, "notexample"));
            assertNull(SubscriptionDns.bestMatch(patterns, "example.invalid"));
            List<String> reversed = new ArrayList<>(patterns.keySet());
            Collections.reverse(reversed);
            patterns.clear();
            for (String key : reversed) patterns.put(key, Boolean.TRUE);
        }
        assertFalse(SubscriptionDns.matches("*.example", "example"));
        assertFalse(SubscriptionDns.matches("*.example", "x.y.example"));
        assertFalse(SubscriptionDns.matches(".example", "example"));
        assertTrue(SubscriptionDns.matches(".example", "x.y.example"));
    }

    @Test public void hostsAcceptIpsAndMultilevelOrExternalAliases() {
        SubscriptionDns.Config c = parse("hosts:\n"
                + "  Node.Example.: [192.0.2.1, '[2001:db8::1]', 192.0.2.1]\n"
                + "  First.Example: SECOND.Example.\n  second.example: Node.Example.\n"
                + "  outside.example: not-resolved.invalid\n");
        assertEquals(Arrays.asList("192.0.2.1", "2001:db8::1"), c.hosts.get("node.example"));
        assertEquals(Collections.singletonList("second.example"), c.hosts.get("first.example"));
        assertEquals(Collections.singletonList("node.example"), c.hosts.get("second.example"));
        assertEquals(Collections.singletonList("not-resolved.invalid"), c.hosts.get("outside.example"));
        assertTrue(c.hasNodeConfig());
        assertTrue(c.hasGeneralConfig());
    }

    @Test public void hostsRejectMixedEmptyAndWrongTypedValues() {
        for (String value : Arrays.asList("[]", "[192.0.2.1, alias.example]", "[a.example, b.example]",
                "123", "true", "null", "{}", "[[192.0.2.1]]")) {
            assertThrows(IllegalArgumentException.class, () -> parse("hosts: {a.example: " + value + "}\n"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> parse("hosts: {A.Example.: 192.0.2.1, a.example: 192.0.2.2}\n"));
    }

    @Test(timeout = 3000) public void hostsRejectDirectIndirectAndWildcardCycles() {
        for (String entries : Arrays.asList("a.example: a.example", "a.example: b.example, b.example: a.example",
                "a.example: x.example, '+.example': a.example")) {
            assertThrows(IllegalArgumentException.class, () -> parse("hosts: {" + entries + "}\n"));
        }
        StringBuilder hosts = new StringBuilder("hosts:\n");
        for (int i = 0; i < 16; i++) hosts.append("  a").append(i).append(".example: a").append(i + 1).append(".example\n");
        hosts.append("  a16.example: 192.0.2.1\n");
        assertThrows(IllegalArgumentException.class, () -> parse(hosts.toString()));
    }

    @Test public void policyRejectsEmptyWrongTypedAndNormalizedDuplicates() {
        for (String value : Arrays.asList("[]", "null", "123", "true", "{}", "[1.1.1.1, false]")) {
            assertThrows(IllegalArgumentException.class,
                    () -> parse("dns: {nameserver-policy: {a.example: " + value + "}}\n"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> parse("dns: {nameserver-policy: {'+.EXAMPLE.': 1.1.1.1, '+.example': 8.8.8.8}}\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("dns: {nameserver-policy: []}\n"));
    }

    @Test public void disabledDnsAndHostsSwitchesAreRespected() {
        assertTrue(parse(IMPORTED).hasNodeConfig());
        SubscriptionDns.Config hostsOnly = parse("hosts: {a.example: 192.0.2.1}\n"
                + "dns: {enable: false, nameserver-policy: deliberately-invalid}\n");
        assertTrue(hostsOnly.policy.isEmpty());
        assertEquals(1, hostsOnly.hosts.size());
        assertTrue(parse("hosts: {a.example: a.example}\ndns: {use-hosts: false}\n").hosts.isEmpty());
    }

    @Test(timeout = 3000) public void endpointDefaultsIpv6AndDirectOptionsStayLocal() {
        String[][] cases = {
                {"192.0.2.1", "udp", "192.0.2.1", "53", "udp"},
                {"udp://resolver.invalid:5353", "udp", "resolver.invalid", "5353", "udp"},
                {"tcp://192.0.2.1:8080", "tcp", "192.0.2.1", "8080", "neko-tcp"},
                {"tls://resolver.invalid", "tls", "resolver.invalid", "853", "neko-tls"},
                {"https://resolver.invalid", "https", "resolver.invalid", "443", "https"},
                {"quic://192.0.2.1", "quic", "192.0.2.1", "853", "quic"},
                {"h3://resolver.invalid/custom", "h3", "resolver.invalid", "443", "h3"},
                {"2001:db8::1", "udp", "2001:db8::1", "53", "udp"},
                {"[2001:db8::1]", "udp", "2001:db8::1", "53", "udp"},
                {"tcp://[2001:db8::1]:8080", "tcp", "2001:db8::1", "8080", "neko-tcp"},
                {"tls://[2001:db8::1]:8853", "tls", "2001:db8::1", "8853", "neko-tls"}
        };
        for (String[] row : cases) {
            SubscriptionDns.Endpoint e = SubscriptionDns.endpoint(row[0]);
            assertEquals(row[1], e.scheme);
            assertEquals(row[2], e.host);
            assertEquals(Integer.parseInt(row[3]), e.port);
            Map<String, Object> options = e.serverOptions("upstream", "bootstrap");
            assertEquals(row[4], options.get("type"));
            assertEquals(e.host, options.get("server"));
            assertEquals(e.port, options.get("server_port"));
            assertFalse(options.containsKey("detour"));
            assertEquals(!SubscriptionDns.isIp(e.host), options.containsKey("domain_resolver"));
        }
        for (String value : Arrays.asList("local", "system")) {
            Map<String, Object> options = SubscriptionDns.endpoint(value).serverOptions("local-dns", "unused");
            assertEquals("local", options.get("type"));
            assertFalse(options.containsKey("server"));
            assertFalse(options.containsKey("domain_resolver"));
        }
        assertEquals("/dns-query", SubscriptionDns.endpoint("https://resolver.invalid").path);
        assertEquals("/private%2Fquery", SubscriptionDns.endpoint("https://resolver.invalid:44443/private%2Fquery").path);
    }

    @Test(timeout = 3000) public void endpointRejectsInvalidPortsProtocolsAndCredentialUrls() {
        for (String value : Arrays.asList("", "udp://192.0.2.1:0", "tcp://192.0.2.1:65536",
                "tls://192.0.2.1:-1", "https://resolver.invalid:", "udp://192.0.2.1:not-a-port",
                "https://u:p@resolver.invalid/query", "https://resolver.invalid/query?secret=x",
                "https://resolver.invalid/query#fragment", "udp://resolver.invalid/path",
                "http://resolver.invalid", "ftp://resolver.invalid", "dhcp://eth0", "rcode://success",
                "https://", "192.0.2.999", "udp://[2001:db8::gg]:53", "1.1.1.1\n", "res olver.invalid")) {
            assertThrows(IllegalArgumentException.class, () -> SubscriptionDns.endpoint(value));
        }
        assertThrows(IllegalArgumentException.class, () -> SubscriptionDns.endpoint(null));
        assertEquals(65535, SubscriptionDns.endpoint("udp://192.0.2.1:65535").port);
    }

    @Test public void emptyCustomDirectDnsMeansInheritAndListsAreDeduplicated() {
        for (String value : Arrays.asList(null, "", "  \t\r\n")) {
            assertTrue(SubscriptionDns.endpointList(value).isEmpty());
            assertTrue(SubscriptionDns.isValidEndpointList(value));
        }
        assertEquals(Arrays.asList("1.1.1.1", "tls://9.9.9.9"),
                SubscriptionDns.endpointList(" 1.1.1.1, tls://9.9.9.9\r\n1.1.1.1 "));
        assertFalse(SubscriptionDns.isValidEndpointList("1.1.1.1,unsupported://resolver.invalid"));
        assertEquals(Arrays.asList("1.1.1.1", "8.8.8.8"), SubscriptionDns.endpointList("1.1.1.1,,8.8.8.8"));
        assertFalse(SubscriptionDns.isValidEndpointList(String.join(",", Collections.nCopies(17, "1.1.1.1"))));
    }

    @Test public void blankOverridesInheritAndEmptyMapsDisableOnlyTheirScope() {
        SubscriptionDns.Config original = parse(IMPORTED);
        assertEquals(original.asDocument(), SubscriptionDns.effective(IMPORTED, " \n", "\t", "").asDocument());
        assertEquals(original.asDocument(), SubscriptionDns.effective(IMPORTED, null, null, null).asDocument());
        SubscriptionDns.Config noPrivate = SubscriptionDns.effective(IMPORTED, "{}", "", "");
        assertTrue(noPrivate.policy.isEmpty());
        assertTrue(noPrivate.serverPolicy.isEmpty());
        assertTrue(noPrivate.serverDns.isEmpty());
        assertEquals(original.hosts, noPrivate.hosts);
        assertEquals(original.bootstrap, noPrivate.bootstrap);
        SubscriptionDns.Config noHosts = SubscriptionDns.effective(IMPORTED, "", "{}", "");
        assertTrue(noHosts.hosts.isEmpty());
        assertEquals(original.policy, noHosts.policy);
        assertEquals(original.serverDns, noHosts.serverDns);
        SubscriptionDns.Config off = SubscriptionDns.effective(IMPORTED, "{}", "{}", "");
        assertFalse(off.hasNodeConfig());
        assertFalse(off.hasGeneralConfig());
    }

    @Test public void privateConfigFormsAndServerOverridePriorityAreStableAcrossUpdates() {
        String body = "nameserver-policy: {manual.example: 8.8.4.4}\n"
                + "proxy-server-nameserver-policy: {manual.example: 1.1.1.1}\n"
                + "proxy-server-nameserver: 192.0.2.53\n";
        String hosts = "manual.example: 192.0.2.9";
        SubscriptionDns.Config manual = SubscriptionDns.effective(IMPORTED, body, hosts, "tls://192.0.2.54:8853");
        SubscriptionDns.Config updated = SubscriptionDns.effective(IMPORTED.replace("1.1.1.1", "8.8.8.8")
                .replace("192.0.2.10", "192.0.2.11"), body, hosts, "tls://192.0.2.54:8853");
        assertEquals(manual.asDocument(), updated.asDocument());
        assertEquals(Collections.singletonList("tls://192.0.2.54:8853"), manual.serverDns);
        assertEquals(Collections.singletonList("192.0.2.53"), SubscriptionDns.effective(IMPORTED, body, "", "").serverDns);
        assertEquals(Collections.singletonList("1.0.0.1"), manual.bootstrap);
        assertEquals(Collections.singletonList("192.0.2.55"), SubscriptionDns.effective(IMPORTED,
                body + "default-nameserver: 192.0.2.55\n", "", "").bootstrap);
        assertEquals(SubscriptionDns.effective(IMPORTED, body, "", "").asDocument(),
                SubscriptionDns.effective(IMPORTED, "dns:\n  " + body.replace("\n", "\n  "), "", "").asDocument());
        assertEquals(manual.policy, SubscriptionDns.effective("", "manual.example: 8.8.4.4", "", "").policy);
        assertNull(SubscriptionDns.validatePrivateConfig(body));
        assertNull(SubscriptionDns.validateHostsConfig(hosts));
        assertNotNull(SubscriptionDns.validatePrivateConfig("manual.example: []"));
        assertNotNull(SubscriptionDns.validateHostsConfig("a.example: a.example"));
    }

    @Test public void ownListenerUsesSourceUpstreamsOnlyForNodeDns() {
        SubscriptionDns.Config c = parse("dns:\n  listen: 127.0.0.1:7874\n"
                + "  proxy-server-nameserver: ['127.0.0.1:7874']\n"
                + "  nameserver: ['127.0.0.1:7874', 'https://private-dns.invalid:44443/private/query']\n");
        assertEquals(Collections.singletonList("https://private-dns.invalid:44443/private/query"), c.serverDns);
        assertEquals(1, c.warnings.size());
        assertTrue(c.hasNodeConfig());
        assertFalse(c.hasGeneralConfig());
        assertTrue(c.policy.isEmpty());
        assertTrue(c.hosts.isEmpty());
        assertEquals(c.asDocument(), parse(c.snapshot()).asDocument());
        assertFalse(c.snapshot().contains("127.0.0.1:7874"));
        assertFalse(c.snapshot().contains("listen:"));
        SubscriptionDns.Endpoint e = SubscriptionDns.endpoint(c.serverDns.get(0));
        assertEquals(44443, e.port);
        assertEquals("/private/query", e.serverOptions("node", "bootstrap").get("path"));
    }

    @Test public void unusableOwnListenerWarnsAndUnrelatedLocalResolversAreNotDiscarded() {
        for (String address : Arrays.asList("127.0.0.1:7874", "localhost:7874", "[::1]:7874")) {
            SubscriptionDns.Config c = parse("dns: {listen: '0.0.0.0:7874', proxy-server-nameserver: ['" + address + "']}\n");
            assertTrue(c.serverDns.isEmpty());
            assertEquals(1, c.warnings.size());
        }
        SubscriptionDns.Config selfOnly = parse("dns: {listen: '127.0.0.1:7874', "
                + "proxy-server-nameserver: ['127.0.0.1:7874'], nameserver: ['127.0.0.1:7874']}\n");
        assertTrue(selfOnly.serverDns.isEmpty());
        assertEquals(1, selfOnly.warnings.size());
        SubscriptionDns.Config otherPort = parse("dns: {listen: '127.0.0.1:7874', proxy-server-nameserver: ['127.0.0.1:5353']}\n");
        assertEquals(Collections.singletonList("127.0.0.1:5353"), otherPort.serverDns);
        assertTrue(otherPort.warnings.isEmpty());
        SubscriptionDns.Config noListener = parse("dns: {proxy-server-nameserver: ['127.0.0.1:7874']}\n");
        assertEquals(Collections.singletonList("127.0.0.1:7874"), noListener.serverDns);
        assertTrue(noListener.warnings.isEmpty());
        // A source's ordinary nameserver is not a replacement for NekoBox global DNS.
        assertFalse(parse("dns: {nameserver: ['https://private-dns.invalid:44443/private/query']}\n").hasNodeConfig());
    }

    @Test public void sharedAcyclicYamlAliasesWorkButTooManyCollectionAliasesFail() {
        SubscriptionDns.Config c = parse("upstream: &up [1.1.1.1, 8.8.8.8]\n"
                + "dns:\n  nameserver-policy: {a.example: *up, b.example: *up}\n");
        assertEquals(c.policy.get("a.example"), c.policy.get("b.example"));
        String excessive = "upstream: &up [1.1.1.1]\nother: ["
                + String.join(",", Collections.nCopies(33, "*up")) + "]\n";
        assertThrows(IllegalArgumentException.class, () -> SubscriptionDns.loadDocument(excessive));
    }

    @Test public void emptyAndNonMapDocumentsAndRecursiveKeysAreHandledSafely() {
        for (String input : Arrays.asList(null, "", " \n", "{}", "null")) {
            assertTrue(SubscriptionDns.loadDocument(input).isEmpty());
        }
        for (String input : Arrays.asList("[]", "scalar", "42", "dns: [broken", "---\na: b\n---\na: c\n",
                "&key {*key: value}\n")) {
            assertThrows(IllegalArgumentException.class, () -> SubscriptionDns.loadDocument(input));
        }
        assertEquals("", parse("").snapshot());
    }

    @Test(timeout = 3000) public void yamlNesting64IsAllowedAnd65IsRejectedBeforeConstruction() {
        // The root mapping is level 1; 63 nested lists reach the inclusive 64-level limit.
        String allowed = "other: " + String.join("", Collections.nCopies(63, "["))
                + "0" + String.join("", Collections.nCopies(63, "]"));
        assertTrue(SubscriptionDns.loadDocument(allowed).containsKey("other"));
        String rejected = "other: " + String.join("", Collections.nCopies(64, "["))
                + "0" + String.join("", Collections.nCopies(64, "]"));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionDns.loadDocument(rejected));
    }

    @Test public void emojiAcrossReaderBufferBoundaryPreservesNodeText() {
        // SnakeYAML 2.3 read a surrogate past the end of its 1024-char input buffer.
        // Two real subscriptions triggered it; use synthetic text rather than private fixtures.
        for (int boundary : Arrays.asList(1024, 2048, 3072, 4096)) {
            String prefix = "node: ";
            String value = String.join("", Collections.nCopies(boundary - 1 - prefix.length(), "a")) + "\ud83d\ude80";
            assertEquals(value, SubscriptionDns.loadDocument(prefix + value + "\n").get("node"));
        }
    }

    @Test public void snapshotDoesNotRetainNodesOrCredentials() {
        SubscriptionDns.Config config = parse("proxies:\n"
                + "  - {name: private-node-sentinel, type: ss, server: hidden.invalid, port: 443, password: password-sentinel}\n"
                + "secret: api-secret-sentinel\n"
                + "hosts: {Node.Example.: 192.0.2.8}\n"
                + "dns: {nameserver-policy: {'+.Example': 1.1.1.1}}\n");
        String snapshot = config.snapshot();
        for (String secret : Arrays.asList("proxies", "private-node-sentinel", "hidden.invalid",
                "password", "api-secret-sentinel")) assertFalse(snapshot.contains(secret));
        assertEquals(config.asDocument(), parse(snapshot).asDocument());
        assertSnapshotShape(config);
    }

    private static void assertSnapshotShape(SubscriptionDns.Config config) {
        Map<?, ?> snapshot = SubscriptionDns.loadDocument(config.snapshot());
        assertTrue(new LinkedHashSet<>(Arrays.asList("hosts", "dns")).containsAll(snapshot.keySet()));
        if (snapshot.containsKey("dns")) {
            Set<String> allowed = new LinkedHashSet<>(Arrays.asList("nameserver-policy",
                    "proxy-server-nameserver-policy", "proxy-server-nameserver", "default-nameserver"));
            assertTrue(allowed.containsAll(((Map<?, ?>) snapshot.get("dns")).keySet()));
        }
        assertEquals(config.asDocument(), parse(config.snapshot()).asDocument());
    }

    /** Explicit, read-only local audit. Never emits YAML, domains, snapshots, or exception text. */
    public static void main(String[] files) {
        boolean failed = false;
        for (String filename : files) {
            Path path = Paths.get(filename);
            String name = path.getFileName().toString().replaceAll("[\\r\\n\\t]", "_");
            int hosts = -1, policy = -1, proxies = -1;
            try {
                if (Files.size(path) > 16 * 1024 * 1024) throw new IllegalArgumentException();
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                Map<?, ?> document = SubscriptionDns.loadDocument(text);
                SubscriptionDns.Config config = SubscriptionDns.fromDocument(document);
                assertSnapshotShape(config);
                // policy includes both ordinary and node-server-specific policy entries.
                hosts = config.hosts.size();
                policy = config.policy.size() + config.serverPolicy.size();
                Object nodes = document.get("proxies");
                proxies = nodes instanceof List ? ((List<?>) nodes).size() : 0;
            } catch (Exception | AssertionError | StackOverflowError rejected) {
                failed = true; // -1 counts indicate failure; never echo a sensitive parser exception.
            }
            System.out.println(name + " hosts=" + hosts + " policy=" + policy + " proxies=" + proxies);
        }
        if (failed) System.exit(1);
    }
}
