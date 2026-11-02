package dnspolicy

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/miekg/dns"
)

const hedgeDelay = 150 * time.Millisecond
const maxRaceTime = 6 * time.Second
const maxPreferredLists = 16384

type upstreamResult struct {
	index   int
	message *dns.Msg
	err     error
}

func validReply(reply, query *dns.Msg) bool {
	return reply != nil && reply.Response && len(reply.Question) == 1 &&
		strings.EqualFold(reply.Question[0].Name, query.Question[0].Name) &&
		reply.Question[0].Qtype == query.Question[0].Qtype && reply.Question[0].Qclass == query.Question[0].Qclass
}
func positiveReply(reply, query *dns.Msg) bool {
	if reply.Rcode != dns.RcodeSuccess {
		return false
	}
	q := query.Question[0]
	owners := map[string]bool{strings.ToLower(q.Name): true}
	// A response with only a CNAME is not a usable A/AAAA answer and must not beat a usable backup.
	for depth := 0; depth < maxAliasDepth; depth++ {
		changed := false
		for _, rr := range reply.Answer {
			if cname, ok := rr.(*dns.CNAME); ok && owners[strings.ToLower(cname.Hdr.Name)] && !owners[strings.ToLower(cname.Target)] {
				owners[strings.ToLower(cname.Target)] = true
				changed = true
			}
		}
		if !changed {
			break
		}
	}
	for _, rr := range reply.Answer {
		h := rr.Header()
		if owners[strings.ToLower(h.Name)] && h.Class == q.Qclass && (h.Rrtype == q.Qtype || q.Qtype == dns.TypeANY) {
			return true
		}
	}
	return false
}

// Hedge only within the selected rule's upstream list. No query path ever changes to public fallback.
func (t *Transport) exchangeUpstreams(parent context.Context, message *dns.Msg, servers []string) (*dns.Msg, error) {
	if err := parent.Err(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(parent, maxRaceTime)
	defer cancel()
	family := "other"
	if message.Question[0].Qtype == dns.TypeA {
		family = "A"
	} else if message.Question[0].Qtype == dns.TypeAAAA {
		family = "AAAA"
	}
	key := family + "\x00" + strings.Join(servers, "\x00")
	t.choiceMu.Lock()
	first := t.preferred[key]
	t.choiceMu.Unlock()
	if first >= len(servers) {
		first = 0
	}
	order := make([]int, 0, len(servers))
	order = append(order, first)
	for i := range servers {
		if i != first {
			order = append(order, i)
		}
	}
	results := make(chan upstreamResult, len(servers))
	next, active := 0, 0
	launch := func() {
		index := order[next]
		next++
		active++
		go func() {
			select {
			case t.slots <- struct{}{}:
			case <-ctx.Done():
				results <- upstreamResult{index: index, err: ctx.Err()}
				return
			}
			defer func() { <-t.slots }()
			if err := ctx.Err(); err != nil {
				results <- upstreamResult{index: index, err: err}
				return
			}
			upstream := t.upstreams[servers[index]]
			if upstream == nil {
				results <- upstreamResult{index: index, err: fmt.Errorf("subscription DNS upstream unavailable")}
				return
			}
			attempt, done := context.WithTimeout(ctx, t.timeout)
			response, err := upstream.Exchange(attempt, message.Copy())
			done()
			results <- upstreamResult{index: index, message: response, err: err}
		}()
	}
	launch()
	timer := time.NewTimer(hedgeDelay)
	defer timer.Stop()
	var negative *dns.Msg
	for active > 0 || next < len(order) {
		select {
		case <-ctx.Done():
			if parent.Err() != nil {
				return nil, parent.Err()
			}
			if negative != nil {
				return negative, nil
			}
			return nil, fmt.Errorf("subscription DNS query budget exceeded")
		case <-timer.C:
			if next < len(order) && active < 3 {
				launch()
			}
			if next < len(order) {
				timer.Reset(hedgeDelay)
			}
		case result := <-results:
			active--
			if result.err == nil && validReply(result.message, message) {
				if positiveReply(result.message, message) {
					t.choiceMu.Lock()
					_, alreadyKnown := t.preferred[key]
					if alreadyKnown || len(t.preferred) < maxPreferredLists {
						t.preferred[key] = result.index
					}
					t.choiceMu.Unlock()
					return result.message, nil
				}
				if result.message.Rcode == dns.RcodeNameError || result.message.Rcode == dns.RcodeSuccess {
					negative = result.message
				}
			}
			// A definite failure/negative response starts the next allowed server immediately.
			if next < len(order) && active < 3 {
				launch()
			}
		}
	}
	if negative != nil {
		return negative, nil
	}
	return nil, fmt.Errorf("all %d subscription DNS upstreams failed", len(servers))
}
