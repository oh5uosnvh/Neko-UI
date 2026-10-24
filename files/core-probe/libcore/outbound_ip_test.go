package libcore

import "testing"

func TestContainsPublicAddress(t *testing.T) {
    good := []string{"ip=18.177.154.150", `{"ip":"2406:da14:3c:b001:ab2d:a408:2f5:6096"}`}
    for _, body := range good { if !containsPublicAddress(body) { t.Fatalf("rejected public address: %s", body) } }
    bad := []string{"ip=10.0.0.1", "127.0.0.1", "rate limited", "captcha"}
    for _, body := range bad { if containsPublicAddress(body) { t.Fatalf("accepted non-public body: %s", body) } }
}
