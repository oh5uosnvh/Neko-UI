package libcore

import (
	"fmt"
	"strings"

	"github.com/sagernet/sing-box/nekoutils"
)

// ValidateGeoRuleSet probes the patched core's built-in geo selector without
// constructing a config. It is intentionally exported for built-in route
// compilation; callers must fail closed when the probe is unavailable.
func ValidateGeoRuleSet(selector string) error {
	selector = strings.ToLower(strings.TrimSpace(selector))
	if selector == "geoip:private" {
		return nil
	}
	switch {
	case strings.HasPrefix(selector, "geoip:"):
		if nekoutils.GetGeoIPHeadlessRules == nil {
			return fmt.Errorf("geoip callback unavailable")
		}
		rules, err := nekoutils.GetGeoIPHeadlessRules(strings.TrimPrefix(selector, "geoip:"))
		if err != nil {
			return err
		}
		if len(rules) == 0 {
			return fmt.Errorf("geoip selector has no rules")
		}
		return nil
	case strings.HasPrefix(selector, "geosite:"):
		if nekoutils.GetGeoSiteHeadlessRules == nil {
			return fmt.Errorf("geosite callback unavailable")
		}
		rules, err := nekoutils.GetGeoSiteHeadlessRules(strings.TrimPrefix(selector, "geosite:"))
		if err != nil {
			return err
		}
		if len(rules) == 0 {
			return fmt.Errorf("geosite selector has no rules")
		}
		for _, rule := range rules {
			options := rule.DefaultOptions
			if len(options.Domain)+len(options.DomainSuffix)+len(options.DomainKeyword)+len(options.DomainRegex) == 0 {
				return fmt.Errorf("geosite selector has empty matching criteria")
			}
		}
		return nil
	default:
		return fmt.Errorf("unsupported geo selector")
	}
}
