package io.nekohasekai.sagernet.group;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.AliasEvent;

import java.io.StringReader;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Android-free subscription DNS model. Never stores a complete subscription or node credentials. */
public final class SubscriptionDns {
    private SubscriptionDns() {}
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_SERVERS = 16;
    private static final int MAX_ALIAS_DEPTH = 16;

    public static final class Config {
        public final Map<String, List<String>> policy = new LinkedHashMap<>();
        public final Map<String, List<String>> serverPolicy = new LinkedHashMap<>();
        public final Map<String, List<String>> hosts = new LinkedHashMap<>();
        public final List<String> serverDns = new ArrayList<>();
        public final List<String> directDns = new ArrayList<>();
        public final List<String> bootstrap = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public boolean hasNodeConfig() {
            return !policy.isEmpty() || !serverPolicy.isEmpty() || !hosts.isEmpty() || !serverDns.isEmpty();
        }
        public boolean hasGeneralConfig() { return !policy.isEmpty() || !hosts.isEmpty(); }
        public Map<String, Object> asDocument() {
            Map<String, Object> doc = new LinkedHashMap<>();
            if (!hosts.isEmpty()) doc.put("hosts", hosts);
            Map<String, Object> dns = new LinkedHashMap<>();
            if (!policy.isEmpty()) dns.put("nameserver-policy", policy);
            if (!serverPolicy.isEmpty()) dns.put("proxy-server-nameserver-policy", serverPolicy);
            if (!serverDns.isEmpty()) dns.put("proxy-server-nameserver", serverDns);
            if (!directDns.isEmpty()) dns.put("direct-nameserver", directDns);
            if (!bootstrap.isEmpty()) dns.put("default-nameserver", bootstrap);
            if (!dns.isEmpty()) doc.put("dns", dns);
            return doc;
        }
        public String snapshot() { return asDocument().isEmpty() ? "" : dump(asDocument()); }
    }

    public static Map<?, ?> loadDocument(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyMap();
        if (text.length() > 16 * 1024 * 1024) throw invalid("YAML exceeds 16 MiB");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(32);
        options.setNestingDepthLimit(64);
        options.setCodePointLimit(16 * 1024 * 1024);
        Object root;
        try {
            DumperOptions dumper = new DumperOptions();
            // Share the same limits with both constructor and composer.
            Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options);
            validateYamlEvents(yaml, text);
            root = yaml.load(text);
        } catch (RuntimeException e) { throw invalid("Invalid or unsafe YAML"); }
        if (root == null) return Collections.emptyMap();
        if (!(root instanceof Map)) throw invalid("Expected a YAML mapping");
        return (Map<?, ?>) root;
    }

    private static void validateYamlEvents(Yaml yaml, String text) {
        // Event parsing is iterative: reject deep input/cyclic aliases before object construction.
        List<String> activeAnchors = new ArrayList<>();
        int aliases = 0;
        int events = 0;
        for (Event event : yaml.parse(new StringReader(text))) {
            if (++events > 1000000) throw invalid("YAML structure too large");
            if (event instanceof CollectionStartEvent) {
                activeAnchors.add(((CollectionStartEvent) event).getAnchor());
                if (activeAnchors.size() > 64) throw invalid("YAML nesting exceeds 64 levels");
            } else if (event instanceof CollectionEndEvent) {
                activeAnchors.remove(activeAnchors.size() - 1);
            } else if (event instanceof AliasEvent) {
                if (++aliases > 32 || activeAnchors.contains(((AliasEvent) event).getAnchor())) {
                    throw invalid("Cyclic or excessive YAML aliases");
                }
            }
        }
    }

    public static Config fromDocument(Map<?, ?> root) {
        return fromDocument(root, false);
    }

    /** Remote Clash selectors may use geosite/rule-set, unsupported by host matcher.
     * Ignore ONLY known incompatible selectors, with a warning; never turn them
     * into catch-all rules. User-authored editor policies remain strictly validated.
     */
    public static Config fromSubscriptionDocument(Map<?, ?> root) {
        return fromDocument(root, true);
    }

    private static Config fromDocument(Map<?, ?> root, boolean remote) {
        Config result = new Config();
        Object dnsObject = root.get("dns");
        Map<?, ?> dns = dnsObject == null ? Collections.emptyMap() : asMap(dnsObject, "dns");
        if (!Boolean.FALSE.equals(dns.get("use-hosts"))) readHosts(root.get("hosts"), result.hosts);
        if (!Boolean.FALSE.equals(dns.get("enable"))) {
            readPolicy(dns.get("nameserver-policy"), result.policy, remote, result.warnings);
            readPolicy(dns.get("proxy-server-nameserver-policy"), result.serverPolicy, remote, result.warnings);
            result.serverDns.addAll(remote ? sourceEndpoints(dns.get("proxy-server-nameserver"), result.warnings) : endpoints(dns.get("proxy-server-nameserver")));
            result.directDns.addAll(remote ? sourceEndpoints(dns.get("direct-nameserver"), result.warnings) : endpoints(dns.get("direct-nameserver")));
            result.bootstrap.addAll(remote ? sourceEndpoints(dns.get("default-nameserver"), result.warnings) : endpoints(dns.get("default-nameserver")));
            // Clash often points node DNS back at its own listener. NekoBox does not run that listener.
            int listenPort = listenerPort(dns.get("listen"));
            if (listenPort != 0) {
                boolean removed = result.serverDns.removeIf(value -> {
                    Endpoint e = endpoint(value);
                    return e.port == listenPort && (e.host.equals("localhost") || e.host.equals("::1") || e.host.startsWith("127."));
                });
                if (removed) {
                    // Emulate that source listener's upstream set for node lookups only.
                    // In particular this retains subscription-private DoH paths and nonstandard ports.
                    List<String> sourceUpstreams = new ArrayList<>(endpoints(dns.get("nameserver")));
                    sourceUpstreams.removeIf(value -> {
                        Endpoint e = endpoint(value);
                        return e.port == listenPort && (e.host.equals("localhost") || e.host.equals("::1") || e.host.startsWith("127."));
                    });
                    result.serverDns.addAll(sourceUpstreams);
                    result.warnings.add(sourceUpstreams.isEmpty()
                        ? "Source YAML DNS listener has no usable upstream; using NekoBox fallback"
                        : "Source YAML loopback DNS listener replaced with its upstreams for node resolution");
                }
            }
        }
        validateAliases(result.hosts);
        return result;
    }

    public static Config effective(String imported, String privateOverride, String hostsOverride, String serverOverride) {
        Config config = fromDocument(loadDocument(imported));
        if (privateOverride != null && !privateOverride.trim().isEmpty()) {
            Config custom = parsePrivate(privateOverride);
            config.policy.clear(); config.policy.putAll(custom.policy);
            config.serverPolicy.clear(); config.serverPolicy.putAll(custom.serverPolicy);
            // Private policy editor does not silently erase the separate server-DNS field.
            Map<?, ?> customDocument = loadEditorDocument(privateOverride, true);
            Map<?, ?> customDns = customDocument.containsKey("dns") ? asMap(customDocument.get("dns"), "dns") : customDocument;
            if (customDns.containsKey("proxy-server-nameserver")) {
                config.serverDns.clear(); config.serverDns.addAll(custom.serverDns);
            }
            // A private override without bootstrap inherits the safe imported bootstrap.
            if (!custom.bootstrap.isEmpty()) { config.bootstrap.clear(); config.bootstrap.addAll(custom.bootstrap); }
        }
        if (hostsOverride != null && !hostsOverride.trim().isEmpty()) {
            config.hosts.clear(); config.hosts.putAll(parseHosts(hostsOverride));
        }
        if (serverOverride != null && !serverOverride.trim().isEmpty()) {
            config.serverDns.clear(); config.serverDns.addAll(endpointList(serverOverride));
        }
        validateAliases(config.hosts);
        return config;
    }

    private static Config parsePrivate(String text) {
        Map<?, ?> root = loadEditorDocument(text, true);
        Map<?, ?> dns;
        if (root.containsKey("dns")) dns = asMap(root.get("dns"), "dns");
        else if (root.containsKey("nameserver-policy") || root.containsKey("proxy-server-nameserver-policy") ||
                root.containsKey("proxy-server-nameserver") || root.containsKey("default-nameserver")) dns = root;
        else dns = Collections.singletonMap("nameserver-policy", root);
        return fromDocument(Collections.singletonMap("dns", dns));
    }

    private static Map<String, List<String>> parseHosts(String text) {
        Map<?, ?> root = loadEditorDocument(text, false);
        Map<String, List<String>> result = new LinkedHashMap<>();
        readHosts(root.containsKey("hosts") ? root.get("hosts") : root, result);
        validateAliases(result);
        return result;
    }

    /** Relax only flat editor snippets; full subscription YAML keeps its strict parser. */
    private static Map<?, ?> loadEditorDocument(String text, boolean dnsValues) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyMap();
        if (text.length() > 16 * 1024 * 1024) throw invalid("YAML exceeds 16 MiB");
        Map<String, Object> flat = new LinkedHashMap<>();
        boolean eligible = true;
        for (String line : text.split("\\r?\\n")) {
            String row = line.trim();
            if (row.isEmpty() || row.startsWith("#")) continue;
            int split = editorColon(row);
            if (split < 1 || row.substring(split + 1).trim().isEmpty()) { eligible = false; break; }
            String rawKey = row.substring(0, split).trim();
            String key;
            try {
                if (rawKey.startsWith("\"") || rawKey.startsWith("'")) {
                    Object parsed = loadDocument("key: " + rawKey + "\n").get("key");
                    key = pattern(parsed);
                } else key = pattern(rawKey);
            } catch (IllegalArgumentException e) { eligible = false; break; }
            String valueText = row.substring(split + 1).trim();
            Object value;
            try {
                value = loadDocument("value: " + valueText + "\n").get("value");
                if (dnsValues && value instanceof String && valueText.contains(",")) {
                    try { endpoint(((String) value).trim()); }
                    catch (IllegalArgumentException e) {
                        value = loadDocument("value: [" + valueText + "]\n").get("value");
                    }
                }
            } catch (IllegalArgumentException e) {
                if (!dnsValues || !valueText.contains(",")) { eligible = false; break; }
                try { value = loadDocument("value: [" + valueText + "]\n").get("value"); }
                catch (IllegalArgumentException badList) { eligible = false; break; }
            }
            if (flat.containsKey(key)) throw invalid("Duplicate normalized editor key");
            flat.put(key, value);
            if (flat.size() > MAX_ENTRIES) throw invalid("Editor exceeds 4096 entries");
        }
        if (eligible && !flat.isEmpty()) return flat;
        return loadDocument(text);
    }

    private static int editorColon(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '\"') { i++; continue; }
                if (c == quote) {
                    if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') { i++; continue; }
                    quote = 0;
                }
            } else if (c == '\'' || c == '\"') quote = c;
            else if (c == ':' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) return i;
        }
        return -1;
    }

    public static String validatePrivateConfig(String text) {
        try { parsePrivate(text); return null; } catch (IllegalArgumentException e) { return e.getMessage(); }
    }
    public static String validateHostsConfig(String text) {
        try { parseHosts(text); return null; } catch (IllegalArgumentException e) { return e.getMessage(); }
    }
    public static boolean isValidEndpointList(String text) {
        try { endpointList(text); return true; } catch (IllegalArgumentException e) { return false; }
    }
    public static List<String> endpointList(String text) {
        if (text == null || text.trim().isEmpty() || text.trim().equals("{}")) return Collections.emptyList();
        return endpoints(Arrays.asList(text.trim().split("[\\r\\n,]+")));
    }
    private static List<String> endpoints(Object value) {
        if (value == null) return Collections.emptyList();
        List<?> values = value instanceof List ? (List<?>) value : Collections.singletonList(value);
        if (values.isEmpty() || values.size() > MAX_SERVERS) throw invalid("DNS list must contain 1..16 servers");
        Set<String> result = new LinkedHashSet<>();
        for (Object v : values) {
            if (!(v instanceof String) || ((String) v).trim().isEmpty()) throw invalid("DNS endpoint must be a non-empty string");
            String s = ((String) v).trim(); endpoint(s); result.add(s);
        }
        return new ArrayList<>(result);
    }

    private static List<String> sourceEndpoints(Object value, List<String> warnings) {
        if (value == null) return Collections.emptyList();
        List<?> input = value instanceof List ? (List<?>) value : Collections.singletonList(value);
        List<Object> cleaned = new ArrayList<>();
        for (Object item : input) {
            if (item instanceof String) {
                String endpoint = ((String) item).trim();
                int fragment = endpoint.indexOf('#');
                if (fragment >= 0) {
                    // Clash DNS fragment options are not part of a DoH/DoT URL.
                    // Remove source-specific routing/TLS options, never downgrade
                    // Neko's certificate verification based on remote YAML.
                    warnings.add("Source Clash DNS fragment options ignored; strict TLS retained");
                    endpoint = endpoint.substring(0, fragment);
                }
                cleaned.add(endpoint);
            } else cleaned.add(item);
        }
        return endpoints(cleaned);
    }

    private static void readPolicy(Object value, Map<String, List<String>> target, boolean remote, List<String> warnings) {
        if (value == null) return;
        Map<?, ?> map = asMap(value, "DNS policy");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object raw = entry.getKey();
            if (remote && raw instanceof String) {
                String selector = ((String) raw).trim().toLowerCase(Locale.ROOT);
                if (selector.startsWith("geosite:") || selector.startsWith("rule-set:") ||
                        selector.startsWith("regexp:")) {
                    warnings.add("Unsupported source Clash DNS selector omitted: " + selector.substring(0, selector.indexOf(':')));
                    continue;
                }
                if (selector.startsWith("domain:")) raw = ((String) raw).trim().substring(7);
                else if (selector.startsWith("domain-suffix:")) raw = "+." + ((String) raw).trim().substring(14);
            }
            if (remote && raw instanceof String && ((String) raw).contains(",")) {
                // Clash supports comma-separated domain: selectors in one key.
                List<String> servers = sourceEndpoints(entry.getValue(), warnings);
                for (String part : ((String) raw).split(",")) {
                    String key = pattern(part.trim());
                    target.put(key, servers);
                }
                continue;
            }
            String key = pattern(raw);
            List<String> servers = remote ? sourceEndpoints(entry.getValue(), warnings) : endpoints(entry.getValue());
            if (servers.isEmpty()) throw invalid("DNS policy requires at least one server");
            if (target.put(key, servers) != null) throw invalid("Duplicate normalized DNS policy");
        }
    }
    private static void readHosts(Object value, Map<String, List<String>> target) {
        if (value == null) return;
        for (Map.Entry<?, ?> entry : asMap(value, "hosts").entrySet()) {
            String key = pattern(entry.getKey());
            Object v = entry.getValue();
            List<?> input = v instanceof List ? (List<?>) v : Collections.singletonList(v);
            if (input.isEmpty() || input.size() > 64) throw invalid("hosts needs 1..64 IPs or one domain alias");
            List<String> output = new ArrayList<>();
            for (Object item : input) {
                if (!(item instanceof String)) throw invalid("hosts values must be IP addresses or a domain alias");
                String s = ((String) item).trim();
                if (isIp(s)) output.add(unbracket(s));
                else if (input.size() == 1) output.add(domain(s));
                else throw invalid("hosts cannot mix IP addresses with domain aliases");
            }
            if (target.put(key, new ArrayList<>(new LinkedHashSet<>(output))) != null) throw invalid("Duplicate normalized hosts entry");
        }
    }
    private static Map<?, ?> asMap(Object value, String field) {
        if (!(value instanceof Map)) throw invalid(field + " must be a mapping");
        Map<?, ?> map = (Map<?, ?>) value;
        if (map.size() > MAX_ENTRIES) throw invalid(field + " exceeds 4096 entries");
        return map;
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
    public static String dump(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(value);
    }
    public static String dumpFlow(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setSplitLines(false);
        return new Yaml(options).dump(value).trim();
    }

    public static String domain(String raw) {
        if (raw == null) throw invalid("Missing domain");
        String s = raw.trim();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        try { s = IDN.toASCII(s).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException e) { throw invalid("Invalid domain"); }
        if (s.isEmpty() || s.length() > 253) throw invalid("Invalid domain");
        for (String label : s.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?")) throw invalid("Invalid domain");
        }
        return s;
    }
    public static String pattern(Object raw) {
        if (!(raw instanceof String)) throw invalid("Domain pattern must be a string");
        String s = ((String) raw).trim();
        String prefix = s.startsWith("+.") || s.startsWith("*.") ? s.substring(0, 2) : s.startsWith(".") ? "." : "";
        String name = domain(s.substring(prefix.length()));
        return prefix + name;
    }
    public static boolean matches(String pattern, String host) {
        String name = domain(host);
        if (pattern.startsWith("+.")) {
            String suffix = pattern.substring(2);
            return name.equals(suffix) || name.endsWith("." + suffix);
        }
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            if (!name.endsWith(suffix)) return false;
            String first = name.substring(0, name.length() - suffix.length());
            return !first.isEmpty() && !first.contains(".");
        }
        if (pattern.startsWith(".")) return name.endsWith(pattern) && name.length() > pattern.length();
        return name.equals(pattern);
    }
    public static int specificity(String key) {
        if (!key.startsWith(".") && !key.startsWith("+.") && !key.startsWith("*.")) return 10000 + key.length();
        return key.replaceFirst("^[+*]?\\.", "").length() * 4 + (key.startsWith("*.") ? 2 : key.startsWith(".") ? 1 : 0);
    }
    public static String bestMatch(Map<String, ?> map, String host) {
        String best = null;
        for (String key : map.keySet()) {
            if (matches(key, host) && (best == null || specificity(key) > specificity(best))) best = key;
        }
        return best;
    }
    private static void validateAliases(Map<String, List<String>> hosts) {
        for (String start : hosts.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            String key = start;
            while (key != null) {
                if (!seen.add(key)) throw invalid("Cyclic hosts aliases");
                if (seen.size() > MAX_ALIAS_DEPTH) throw invalid("hosts alias chain exceeds 16 levels");
                List<String> value = hosts.get(key);
                if (value.size() != 1 || isIp(value.get(0))) break;
                key = bestMatch(hosts, value.get(0));
            }
        }
    }
    private static String unbracket(String s) {
        return s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
    }
    public static boolean isIp(String value) {
        if (value == null) return false;
        String s = unbracket(value);
        if (s.contains(":")) {
            if (!s.matches("[0-9a-fA-F:.]+")) return false;
            try { return InetAddress.getByName(s).getAddress().length >= 4; }
            catch (Exception e) { return false; }
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (!p.matches("[0-9]{1,3}") || Integer.parseInt(p) > 255 || (p.length() > 1 && p.startsWith("0"))) return false;
        }
        return true;
    }
    public static final class Endpoint {
        public String scheme, host, path;
        public int port;
        public Map<String, Object> serverOptions(String tag, String bootstrapTag) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", scheme.equals("tcp") || scheme.equals("tls") ? "neko-" + scheme : scheme);
            result.put("tag", tag);
            if (!scheme.equals("local")) {
                result.put("server", host); result.put("server_port", port);
                if (!isIp(host)) result.put("domain_resolver", bootstrapTag);
                if (scheme.equals("https") || scheme.equals("h3")) result.put("path", path);
            }
            // New DNS transports dial directly by default, not through the selected proxy.
            return result;
        }
    }
    public static Endpoint endpoint(String raw) {
        if (raw == null || raw.isEmpty() || raw.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) throw invalid("Invalid DNS endpoint");
        Endpoint e = new Endpoint();
        if (raw.equals("local") || raw.equals("system")) { e.scheme = "local"; e.host = ""; return e; }
        String value = raw;
        if (!value.contains("://")) value = "udp://" + (isIp(value) && value.contains(":") ? "[" + unbracket(value) + "]" : value);
        URI uri;
        try { uri = new URI(value); }
        catch (Exception ex) { throw invalid("Invalid DNS URL"); }
        e.scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("udp", "tcp", "tls", "https", "quic", "h3").contains(e.scheme)) throw invalid("Unsupported DNS protocol");
        if (uri.getUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) throw invalid("DNS URL credentials, fragments and query parameters are not supported");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw invalid("Missing or invalid DNS host");
        e.host = isIp(host) ? unbracket(host) : domain(host);
        if (e.host.matches("[0-9.]+") && !isIp(e.host)) throw invalid("Invalid DNS IP address");
        int port = uri.getPort();
        String authority = uri.getRawAuthority();
        if (authority == null || authority.endsWith(":")) throw invalid("Invalid DNS port");
        if (port == -1) port = e.scheme.equals("https") || e.scheme.equals("h3") ? 443 : e.scheme.equals("tls") || e.scheme.equals("quic") ? 853 : 53;
        if (port < 1 || port > 65535) throw invalid("DNS port must be 1..65535");
        e.port = port;
        e.path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/dns-query" : uri.getRawPath();
        if (!e.scheme.equals("https") && !e.scheme.equals("h3") && uri.getRawPath() != null && !uri.getRawPath().isEmpty()) throw invalid("Only DoH supports a DNS URL path");
        return e;
    }
    private static int listenerPort(Object value) {
        if (!(value instanceof String)) return 0;
        try { return endpoint((String) value).port; } catch (IllegalArgumentException e) { return 0; }
    }
}
