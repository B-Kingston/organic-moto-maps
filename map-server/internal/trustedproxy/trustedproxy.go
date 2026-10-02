// Package trustedproxy resolves the effective client address. Forwarded
// headers are only honored when the direct peer is inside the configured
// trusted proxy CIDRs, so a public client cannot spoof its address.
package trustedproxy

import (
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strings"
)

// Set is an immutable set of trusted proxy prefixes.
type Set struct {
	prefixes []netip.Prefix
}

// Parse builds a trusted proxy set from CIDR strings. Empty input yields an
// empty set: no forwarded header is ever trusted.
func Parse(cidrs []string) (*Set, error) {
	s := &Set{}
	for _, raw := range cidrs {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		p, err := netip.ParsePrefix(raw)
		if err != nil {
			// Accept a bare address as a /32 or /128.
			addr, aerr := netip.ParseAddr(raw)
			if aerr != nil {
				return nil, fmt.Errorf("invalid trusted proxy %q: %w", raw, err)
			}
			p = netip.PrefixFrom(addr, addr.BitLen())
		}
		s.prefixes = append(s.prefixes, p.Masked())
	}
	return s, nil
}

// Contains reports whether addr is a trusted proxy.
func (s *Set) Contains(addr netip.Addr) bool {
	if !addr.IsValid() {
		return false
	}
	for _, p := range s.prefixes {
		if p.Contains(addr) {
			return true
		}
	}
	return false
}

// Empty reports whether no proxies are trusted.
func (s *Set) Empty() bool { return len(s.prefixes) == 0 }

// IsTrustedPeer reports whether the direct connection peer is a trusted proxy.
func (s *Set) IsTrustedPeer(remoteAddr string) bool {
	addr, err := addrFromHostPort(remoteAddr)
	if err != nil {
		return false
	}
	return s.Contains(addr)
}

// ClientAddr resolves the effective client address for a request. remoteAddr
// is the direct peer (host:port). X-Forwarded-For is walked right to left,
// skipping trusted proxies; the first untrusted address wins.
func (s *Set) ClientAddr(remoteAddr string, header http.Header) netip.Addr {
	peer, err := addrFromHostPort(remoteAddr)
	if err != nil {
		return netip.Addr{}
	}
	if s.Empty() || !s.Contains(peer) {
		return peer
	}
	xff := header.Get("X-Forwarded-For")
	if xff == "" {
		return peer
	}
	parts := strings.Split(xff, ",")
	var fallback netip.Addr
	for i := len(parts) - 1; i >= 0; i-- {
		raw := strings.TrimSpace(parts[i])
		if raw == "" {
			continue
		}
		addr, err := netip.ParseAddr(raw)
		if err != nil {
			continue
		}
		addr = normalize(addr)
		if fallback == (netip.Addr{}) {
			fallback = addr
		}
		if s.Contains(addr) {
			continue
		}
		return addr
	}
	if fallback != (netip.Addr{}) {
		return fallback
	}
	return peer
}

// BucketKey normalizes an address into a rate-limit key. IPv4 and IPv4-mapped
// IPv6 collapse to the same /32; native IPv6 buckets by /64 so a client cannot
// trivially bypass a limit by varying the interface identifier.
func BucketKey(addr netip.Addr) string {
	addr = normalize(addr)
	if !addr.IsValid() {
		return "unknown"
	}
	if addr.Is4() {
		return "v4:" + addr.String()
	}
	p, err := addr.Prefix(64)
	if err != nil {
		return "v6:" + addr.String()
	}
	return "v6:" + p.Masked().String()
}

func normalize(addr netip.Addr) netip.Addr {
	if addr.Is4In6() {
		return addr.Unmap()
	}
	return addr
}

func addrFromHostPort(hostPort string) (netip.Addr, error) {
	host, _, err := net.SplitHostPort(hostPort)
	if err != nil {
		host = hostPort
	}
	return netip.ParseAddr(host)
}
