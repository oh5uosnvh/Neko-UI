package libcore

// Karing SRS resource validation is deliberately independent from routing
// configuration. Java owns extraction/download; this package only validates a
// complete sing-box rule-set and resolves a manager-owned local path.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/sagernet/sing-box/common/srs"
)

var karingCodePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9@._-]{0,127}$`)

type KaringSRSValidation struct {
	Valid     bool
	RuleCount int32
	Sha256    string
	Error     string
}

func validKaringPart(value string) bool {
	return value != "" && value != "." && value != ".." && !strings.ContainsAny(value, `/\\\x00`) && karingCodePattern.MatchString(value)
}

// ValidateKaringSRS parses the complete compressed SRS body, not merely its
// three-byte magic. family is acl, geosite, or geoip; code excludes .srs.
func validateKaringSRS(path, family, code string) (ruleCount int32, digest string, err error) {
	if !validKaringPart(family) || (family != "acl" && family != "geosite" && family != "geoip") {
		return 0, "", fmt.Errorf("invalid Karing SRS family")
	}
	if !validKaringPart(code) {
		return 0, "", fmt.Errorf("invalid Karing SRS code")
	}
	base := filepath.Base(path)
	// The Android manager validates an atomically downloaded staging file
	// named <code>.srs.part before renaming it to <code>.srs.
	if base != code+".srs" && base != code+".srs.part" {
		return 0, "", fmt.Errorf("Karing SRS basename does not match code")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, "", err
	}
	hash := sha256.Sum256(data)
	parsed, err := srs.Read(bytes.NewReader(data), false)
	if err != nil {
		return 0, "", fmt.Errorf("invalid Karing %s:%s SRS: %w", family, code, err)
	}
	if len(parsed.Options.Rules) == 0 {
		return 0, "", fmt.Errorf("Karing SRS contains no rules")
	}
	return int32(len(parsed.Options.Rules)), hex.EncodeToString(hash[:]), nil
}

// ValidateKaringSRSFile is the gomobile-friendly structured entry point.
// The returned object retains rule count and digest for UI diagnostics.
func ValidateKaringSRSFile(path, family, code string) *KaringSRSValidation {
	count, digest, err := validateKaringSRS(path, family, code)
	result := &KaringSRSValidation{Valid: err == nil, RuleCount: count, Sha256: digest}
	if err != nil {
		result.Error = err.Error()
	}
	return result
}

// KaringSRSResource resolves only the manager-owned external asset tree.
// It intentionally does not search arbitrary filesystem locations.
func karingSRSResource(reference string) (path string, ruleCount int32, err error) {
	parts := strings.Split(reference, ":")
	if len(parts) != 2 || !validKaringPart(parts[0]) || !validKaringPart(parts[1]) {
		return "", 0, fmt.Errorf("invalid Karing rule reference")
	}
	family, code := parts[0], parts[1]
	if family != "acl" && family != "geosite" && family != "geoip" {
		return "", 0, fmt.Errorf("unsupported Karing rule family: %s", family)
	}
	path = filepath.Join(externalAssetsPath, "karing", family, code+".srs")
	root, err := filepath.Abs(filepath.Join(externalAssetsPath, "karing", family))
	if err != nil {
		return "", 0, err
	}
	candidate, err := filepath.Abs(path)
	if err != nil || filepath.Dir(candidate) != root {
		return "", 0, fmt.Errorf("unsafe Karing rule path")
	}
	count, _, err := validateKaringSRS(candidate, family, code)
	if err != nil {
		return "", 0, err
	}
	return candidate, count, nil
}
