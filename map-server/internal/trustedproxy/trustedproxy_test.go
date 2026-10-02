package trustedproxy

import (
	"net/http"
	"net/netip"
	"testing"
)

func TestNoTrustedProxiesIgnoresForwardedHeaders(t *testing.T) {
	set, err := Parse(nil)
	if err != nil {
		t.Fatal(err)
	}
	h := http.Header{}
	h.Set("X-Forwarded-For", "203.0.113.9")
	got := set.ClientAddr("198.51.100.4:5555", h)
	if got.String() != "198.51.100.4" {
		t.Fatalf("spoofed address accepted: %s", got)
	}
}

func TestForwardedChainWalksRightToLeft(t *testing.T) {
	set, err := Parse([]string{"10.0.0.0/8", "192.168.0.0/16"})
	if err != nil {
		t.Fatal(err)
	}
	h := http.Header{}
	h.Set("X-Forwarded-For", "203.0.113.9, 10.1.2.3, 192.168.5.5")
	got := set.ClientAddr("10.0.0.1:443", h)
	if got.String() != "203.0.113.9" {
		t.Fatalf("got %s, want 203.0.113.9", got)
	}
	// A client-injected leftmost entry cannot override the real client when
	// the proxy appends: the rightmost untrusted address wins.
	h.Set("X-Forwarded-For", "1.2.3.4, 203.0.113.9")
	got = set.ClientAddr("10.0.0.1:443", h)
	if got.String() != "203.0.113.9" {
		t.Fatalf("got %s, want 203.0.113.9", got)
	}
	// Untrusted peer: the header is ignored entirely.
	got = set.ClientAddr("203.0.113.200:443", h)
	if got.String() != "203.0.113.200" {
		t.Fatalf("got %s, want the peer address", got)
	}
}

func TestMalformedForwardedEntriesAreSkipped(t *testing.T) {
	set, _ := Parse([]string{"10.0.0.0/8"})
	h := http.Header{}
	h.Set("X-Forwarded-For", "not-an-ip, , 203.0.113.9, 10.0.0.9")
	got := set.ClientAddr("10.0.0.1:443", h)
	if got.String() != "203.0.113.9" {
		t.Fatalf("got %s, want 203.0.113.9", got)
	}
}

func TestBucketKeyNormalizesIPv4AndIPv6(t *testing.T) {
	v4 := BucketKey(mustAddr(t, "203.0.113.9"))
	mapped := BucketKey(mustAddr(t, "::ffff:203.0.113.9"))
	if v4 != mapped {
		t.Fatalf("IPv4-mapped address bucketed separately: %s vs %s", v4, mapped)
	}
	a := BucketKey(mustAddr(t, "2001:db8:1:2:3:4:5:6"))
	b := BucketKey(mustAddr(t, "2001:db8:1:2:ffff::1"))
	if a != b {
		t.Fatalf("same /64 bucketed separately: %s vs %s", a, b)
	}
	c := BucketKey(mustAddr(t, "2001:db8:1:3::1"))
	if a == c {
		t.Fatal("different /64s share a bucket")
	}
	if BucketKey(mustAddr(t, "198.51.100.1")) == BucketKey(mustAddr(t, "198.51.100.2")) {
		t.Fatal("distinct IPv4 addresses share a bucket")
	}
}

func mustAddr(t *testing.T, s string) netip.Addr {
	t.Helper()
	a, err := netip.ParseAddr(s)
	if err != nil {
		t.Fatal(err)
	}
	return a
}
