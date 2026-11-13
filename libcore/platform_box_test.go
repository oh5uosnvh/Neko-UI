package libcore

import "testing"

func TestStringIteratorExhaustionIsSafe(t *testing.T) {
	it := &stringIterator{items: []string{"one"}}
	if !it.HasNext() || it.Len() != 1 {
		t.Fatalf("initial iterator state: has=%v len=%d", it.HasNext(), it.Len())
	}
	if got := it.Next(); got != "one" {
		t.Fatalf("first item = %q", got)
	}
	if it.HasNext() {
		t.Fatal("iterator still has an item after consuming it")
	}
	if got := it.Next(); got != "" {
		t.Fatalf("exhausted iterator returned %q", got)
	}
}

func TestRealHostInterfacesAreExposed(t *testing.T) {
	interfaces, err := (&boxPlatformInterfaceWrapper{}).GetInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	if !interfaces.HasNext() {
		t.Fatal("host returned no network interfaces")
	}
	seen := 0
	for interfaces.HasNext() {
		item := interfaces.Next()
		if item == nil || item.Name == "" || item.Index <= 0 || item.MTU <= 0 {
			t.Fatalf("invalid real interface: %#v", item)
		}
		seen++
	}
	if seen == 0 {
		t.Fatal("interface iterator yielded no entries")
	}
}

func TestSplitLinesNormalizesPlatformNetworkData(t *testing.T) {
	got := splitLines(" 1.1.1.1\n\n 2001:4860:4860::8888 \n")
	if len(got) != 2 || got[0] != "1.1.1.1" || got[1] != "2001:4860:4860::8888" {
		t.Fatalf("splitLines = %#v", got)
	}
}
