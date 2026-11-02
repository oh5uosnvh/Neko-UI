// Package dnspolicy bridges subscription hosts and domain-scoped DNS to sing-box.
package dnspolicy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"regexp"
	"strings"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/service"
)

const Type = "neko-policy"
const maxAliasDepth = 16

// Rule uses transport tags, not URLs; upstream TLS and socket protection remain core-owned.
type Rule struct {
	Pattern string   `json:"pattern"`
	Servers []string `json:"servers"`
}
type Options struct {
	Fallback    []string            `json:"fallback"`
	Rules       []Rule              `json:"rules,omitempty"`
	ServerRules []Rule              `json:"server_rules,omitempty"`
	Hosts       map[string][]string `json:"hosts,omitempty"`
	Timeout     badoption.Duration  `json:"timeout,omitempty"`
}
type Transport struct {
	boxdns.TransportAdapter
	manager   adapter.DNSTransportManager
	upstreams map[string]adapter.DNSTransport
	options   Options
	timeout   time.Duration
}

var _ adapter.DNSTransport = (*Transport)(nil)

func RegisterTransport(registry *boxdns.TransportRegistry) {
	boxdns.RegisterTransport[Options](registry, Type, New)
	registerStreams(registry)
}
func New(ctx context.Context, _ log.ContextLogger, tag string, options Options) (adapter.DNSTransport, error) {
	if len(options.Rules) > 4096 || len(options.ServerRules) > 4096 || len(options.Hosts) > 4096 {
		return nil, errors.New("subscription DNS exceeds 4096 rules")
	}
	dependencies := make([]string, 0)
	seen := make(map[string]bool)
	validateServers := func(servers []string) error {
		if len(servers) == 0 || len(servers) > 16 {
			return errors.New("DNS rule requires 1..16 upstreams")
		}
		for _, server := range servers {
			if server == "" || server == tag {
				return errors.New("invalid or recursive DNS upstream")
			}
			if !seen[server] {
				seen[server] = true
				dependencies = append(dependencies, server)
			}
		}
		return nil
	}
	if err := validateServers(options.Fallback); err != nil {
		return nil, err
	}
	// Deep copy: config maps/slices are immutable after construction and safe for concurrent queries.
	copyRules := func(rules []Rule) ([]Rule, error) {
		result := make([]Rule, 0, len(rules))
		duplicate := make(map[string]bool)
		for _, r := range rules {
			pattern, err := normalizePattern(r.Pattern)
			if err != nil {
				return nil, err
			}
			if duplicate[pattern] {
				return nil, errors.New("duplicate normalized DNS pattern")
			}
			duplicate[pattern] = true
			if err := validateServers(r.Servers); err != nil {
				return nil, err
			}
			result = append(result, Rule{pattern, append([]string(nil), r.Servers...)})
		}
		return result, nil
	}
	var err error
	options.Fallback = append([]string(nil), options.Fallback...)
	options.Rules, err = copyRules(options.Rules)
	if err != nil {
		return nil, err
	}
	options.ServerRules, err = copyRules(options.ServerRules)
	if err != nil {
		return nil, err
	}
	hosts := make(map[string][]string, len(options.Hosts))
	for key, values := range options.Hosts {
		key, err = normalizePattern(key)
		if err != nil {
			return nil, err
		}
		if _, exists := hosts[key]; exists {
			return nil, errors.New("duplicate normalized hosts entry")
		}
		if len(values) == 0 || len(values) > 64 {
			return nil, errors.New("hosts requires 1..64 addresses or one alias")
		}
		copyValues := make([]string, 0, len(values))
		for _, value := range values {
			if ip, err := netip.ParseAddr(value); err == nil {
				copyValues = append(copyValues, ip.Unmap().String())
			} else if len(values) == 1 {
				value, err := normalizeDomain(value)
				if err != nil {
					return nil, errors.New("invalid hosts alias")
				}
				copyValues = append(copyValues, value)
			} else {
				return nil, errors.New("hosts cannot mix aliases and IPs")
			}
		}
		hosts[key] = copyValues
	}
	options.Hosts = hosts
	for key := range hosts {
		seenKeys := make(map[string]bool)
		for key != "" {
			if seenKeys[key] {
				return nil, errors.New("cyclic hosts aliases")
			}
			seenKeys[key] = true
			if len(seenKeys) > maxAliasDepth {
				return nil, errors.New("hosts alias chain exceeds 16 levels")
			}
			values := hosts[key]
			if len(values) != 1 {
				break
			}
			if _, err := netip.ParseAddr(values[0]); err == nil {
				break
			}
			key = bestHost(hosts, values[0])
		}
	}
	timeout := time.Duration(options.Timeout)
	if timeout == 0 {
		timeout = 2 * time.Second
	}
	if timeout < time.Millisecond || timeout > 30*time.Second {
		return nil, errors.New("DNS attempt timeout must be 1ms..30s")
	}
	return &Transport{
		TransportAdapter: boxdns.NewTransportAdapter(Type, tag, dependencies),
		manager:          service.FromContext[adapter.DNSTransportManager](ctx),
		options:          options, timeout: timeout,
	}, nil
}
func (t *Transport) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	if t.manager == nil {
		return errors.New("missing DNS transport manager")
	}
	upstreams := make(map[string]adapter.DNSTransport, len(t.Dependencies()))
	for _, tag := range t.Dependencies() {
		upstream, ok := t.manager.Transport(tag)
		if !ok {
			return errors.New("subscription DNS upstream not found")
		}
		upstreams[tag] = upstream
	}
	t.upstreams = upstreams
	return nil
}
func (t *Transport) Close() error { return nil }

var labelPattern = regexp.MustCompile(`^[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?$`)

func normalizeDomain(value string) (string, error) {
	value = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
	if value == "" || len(value) > 253 {
		return "", errors.New("invalid DNS domain")
	}
	for _, label := range strings.Split(value, ".") {
		if len(label) > 63 || !labelPattern.MatchString(label) {
			return "", errors.New("invalid DNS domain")
		}
	}
	return value, nil
}
func normalizePattern(value string) (string, error) {
	value = strings.TrimSpace(value)
	prefix := ""
	if strings.HasPrefix(value, "+.") || strings.HasPrefix(value, "*.") {
		prefix, value = value[:2], value[2:]
	} else if strings.HasPrefix(value, ".") {
		prefix, value = ".", value[1:]
	}
	domain, err := normalizeDomain(value)
	if err != nil {
		return "", err
	}
	return prefix + domain, nil
}
func matches(pattern, host string) bool {
	switch {
	case strings.HasPrefix(pattern, "+."):
		suffix := pattern[2:]
		return host == suffix || strings.HasSuffix(host, "."+suffix)
	case strings.HasPrefix(pattern, "*."):
		suffix := pattern[1:]
		if !strings.HasSuffix(host, suffix) {
			return false
		}
		prefix := strings.TrimSuffix(host, suffix)
		return prefix != "" && !strings.Contains(prefix, ".")
	case strings.HasPrefix(pattern, "."):
		return len(host) > len(pattern) && strings.HasSuffix(host, pattern)
	default:
		return host == pattern
	}
}
func specificity(pattern string) int {
	switch {
	case strings.HasPrefix(pattern, "+."):
		return len(pattern[2:]) * 4
	case strings.HasPrefix(pattern, "*."):
		return len(pattern[2:])*4 + 2
	case strings.HasPrefix(pattern, "."):
		return len(pattern[1:])*4 + 1
	default:
		return 10000 + len(pattern)
	}
}
func bestHost(hosts map[string][]string, name string) string {
	best := ""
	for pattern := range hosts {
		if matches(pattern, name) && (best == "" || specificity(pattern) > specificity(best)) {
			best = pattern
		}
	}
	return best
}
func bestRule(rules []Rule, name string) []string {
	index, score := -1, -1
	for i, r := range rules {
		if matches(r.Pattern, name) && specificity(r.Pattern) > score {
			index, score = i, specificity(r.Pattern)
		}
	}
	if index < 0 {
		return nil
	}
	return rules[index].Servers
}

func (t *Transport) Exchange(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if message == nil || len(message.Question) != 1 {
		return nil, errors.New("subscription DNS requires one question")
	}
	question := message.Question[0]
	name, err := normalizeDomain(question.Name)
	if err != nil {
		return nil, err
	}
	request := message.Copy()
	var aliases []dns.RR
	visited := make(map[string]bool)
	for depth := 0; ; depth++ {
		if depth > maxAliasDepth || visited[name] {
			return nil, errors.New("cyclic or excessive hosts alias chain")
		}
		visited[name] = true
		key := bestHost(t.options.Hosts, name)
		if key == "" {
			break
		}
		values := t.options.Hosts[key]
		if _, err := netip.ParseAddr(values[0]); err == nil {
			reply := new(dns.Msg)
			reply.SetReply(message)
			reply.RecursionAvailable = true
			reply.Answer = append(reply.Answer, aliases...)
			if question.Qclass == dns.ClassINET {
				for _, value := range values {
					ip := netip.MustParseAddr(value)
					header := dns.RR_Header{Name: dns.Fqdn(name), Class: dns.ClassINET, Ttl: 60}
					if ip.Is4() && (question.Qtype == dns.TypeA || question.Qtype == dns.TypeANY) {
						header.Rrtype = dns.TypeA
						reply.Answer = append(reply.Answer, &dns.A{Hdr: header, A: net.IP(ip.AsSlice())})
					} else if ip.Is6() && (question.Qtype == dns.TypeAAAA || question.Qtype == dns.TypeANY) {
						header.Rrtype = dns.TypeAAAA
						reply.Answer = append(reply.Answer, &dns.AAAA{Hdr: header, AAAA: net.IP(ip.AsSlice())})
					}
				}
			}
			return reply, nil
		}
		target := values[0]
		aliases = append(aliases, &dns.CNAME{
			Hdr:    dns.RR_Header{Name: dns.Fqdn(name), Rrtype: dns.TypeCNAME, Class: dns.ClassINET, Ttl: 60},
			Target: dns.Fqdn(target),
		})
		if question.Qtype == dns.TypeCNAME {
			reply := new(dns.Msg)
			reply.SetReply(message)
			reply.RecursionAvailable = true
			reply.Answer = aliases
			return reply, nil
		}
		name = target
		request.Question[0].Name = dns.Fqdn(name)
	}
	servers := bestRule(t.options.ServerRules, name)
	if servers == nil {
		servers = bestRule(t.options.Rules, name)
	}
	if servers == nil {
		servers = t.options.Fallback
	}
	response, err := t.exchangeUpstreams(ctx, request, servers)
	if err != nil {
		return nil, err
	}
	response = response.Copy()
	if len(aliases) > 0 {
		response.Question = append([]dns.Question(nil), message.Question...)
		response.Answer = append(aliases, response.Answer...)
		// Rewritten aliases are locally synthesized, not DNSSEC authenticated.
		response.AuthenticatedData = false
	}
	response.Id = message.Id
	return response, nil
}

func (t *Transport) exchangeUpstreams(ctx context.Context, message *dns.Msg, servers []string) (*dns.Msg, error) {
	var negative *dns.Msg
	for _, tag := range servers {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		upstream := t.upstreams[tag]
		if upstream == nil {
			return nil, errors.New("subscription DNS upstream unavailable")
		}
		attempt, cancel := context.WithTimeout(ctx, t.timeout)
		response, err := upstream.Exchange(attempt, message.Copy())
		cancel()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if err != nil || response == nil {
			continue
		}
		if len(response.Question) != 1 || !strings.EqualFold(response.Question[0].Name, message.Question[0].Name) ||
			response.Question[0].Qtype != message.Question[0].Qtype || response.Question[0].Qclass != message.Question[0].Qclass {
			continue
		}
		if response.Rcode == dns.RcodeSuccess && len(response.Answer) > 0 {
			return response, nil
		}
		// Alternate servers are still restricted to the matched policy. Never leak to the default.
		if response.Rcode == dns.RcodeNameError || response.Rcode == dns.RcodeSuccess {
			negative = response
		}
	}
	if negative != nil {
		return negative, nil
	}
	return nil, fmt.Errorf("all %d subscription DNS upstreams failed", len(servers))
}
