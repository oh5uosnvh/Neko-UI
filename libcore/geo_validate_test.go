package libcore

import (
 "errors"
 "testing"

 "github.com/sagernet/sing-box/nekoutils"
 "github.com/sagernet/sing-box/option"
)

func TestValidateGeoRuleSet(t *testing.T) {
 oldIP, oldSite := nekoutils.GetGeoIPHeadlessRules, nekoutils.GetGeoSiteHeadlessRules
 defer func() { nekoutils.GetGeoIPHeadlessRules = oldIP; nekoutils.GetGeoSiteHeadlessRules = oldSite }()
 nekoutils.GetGeoIPHeadlessRules = nil
 nekoutils.GetGeoSiteHeadlessRules = nil
 for _, selector := range []string{"geoip:cn", "geosite:cn", "unknown:value"} {
  if ValidateGeoRuleSet(selector) == nil { t.Fatalf("unverifiable selector accepted: %s", selector) }
 }
 if err := ValidateGeoRuleSet("geoip:private"); err != nil { t.Fatal(err) }
 nekoutils.GetGeoSiteHeadlessRules = func(string) ([]option.HeadlessRule, error) {
  return nil, errors.New("synthetic unreadable database")
 }
 if ValidateGeoRuleSet("geosite:cn") == nil { t.Fatal("read failure accepted") }
 nekoutils.GetGeoSiteHeadlessRules = func(string) ([]option.HeadlessRule, error) {
  return []option.HeadlessRule{{}}, nil
 }
 if ValidateGeoRuleSet("geosite:cn") == nil { t.Fatal("empty category accepted") }
 nekoutils.GetGeoSiteHeadlessRules = func(code string) ([]option.HeadlessRule, error) {
  if code != "cn" { t.Fatalf("unexpected code: %s", code) }
  return []option.HeadlessRule{{DefaultOptions: option.DefaultHeadlessRule{Domain: []string{"example.invalid"}}}}, nil
 }
 if err := ValidateGeoRuleSet(" GEOSITE:CN "); err != nil { t.Fatal(err) }
 nekoutils.GetGeoIPHeadlessRules = func(string) ([]option.HeadlessRule, error) { return nil, nil }
 if ValidateGeoRuleSet("geoip:cn") == nil { t.Fatal("missing IP category accepted") }
}
