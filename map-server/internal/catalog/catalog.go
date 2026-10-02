// Package catalog holds the operator-approved region catalog. Public build
// requests may only name a region from this file; the server never accepts a
// caller-supplied URL, path, or command.
package catalog

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
)

// ErrDisabled marks a region the operator has switched off.
var ErrDisabled = errors.New("region is disabled by the operator")

// Region is one buildable, operator-approved region.
type Region struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Coverage string `json:"coverage,omitempty"`
	Disabled bool   `json:"disabled,omitempty"`
	Notes    string `json:"notes,omitempty"`
	MaxZoom  int    `json:"maxZoom,omitempty"`

	// Requested marks a region added by a client request (not the operator's
	// catalog file); ExtractID is its Geofabrik id.
	Requested bool   `json:"requested,omitempty"`
	ExtractID string `json:"extractId,omitempty"`

	Source Source `json:"source"`
}

// Source is the OSM extract this region is built from. Exactly one of URL or
// Path is set. SHA256 should be pinned by the operator; when it is empty the
// server records the hash it observed so the artifact fingerprint is still
// reproducible, and logs a warning at build time.
type Source struct {
	URL    string `json:"url,omitempty"`
	Path   string `json:"path,omitempty"`
	Date   string `json:"date"`
	SHA256 string `json:"sha256,omitempty"`
	// MD5 is the digest the upstream publisher lists for URL; a download that
	// does not match is rejected.
	MD5 string `json:"md5,omitempty"`
	// MaxBytes caps this region's download below the server-wide cap (0 = none).
	MaxBytes int64 `json:"maxBytes,omitempty"`
}

// Catalog is the parsed catalog file. Regions holds the operator's regions
// only and is immutable after load; requested regions live in an attached
// RequestedStore.
type Catalog struct {
	Regions []Region `json:"regions"`

	mu        sync.RWMutex
	requested *RequestedStore
}

// AttachRequested adds a store of client-requested regions to Lookup/List.
func (c *Catalog) AttachRequested(store *RequestedStore) {
	c.mu.Lock()
	c.requested = store
	c.mu.Unlock()
}

var (
	idPattern   = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{1,79}$`)
	datePattern = regexp.MustCompile(`^([0-9]{6}|[0-9]{4}-[0-9]{2}-[0-9]{2})$`)
	hexPattern  = regexp.MustCompile(`^[0-9a-f]{64}$`)
	md5Pattern  = regexp.MustCompile(`^[0-9a-f]{32}$`)
)

// Default returns the built-in catalog used when no --catalog is configured.
// It mirrors the extract the app's CI pipeline pins.
func Default() *Catalog {
	return &Catalog{Regions: []Region{
		{
			ID:       "queensland",
			Name:     "Queensland",
			Coverage: "Queensland, Australia",
			MaxZoom:  14,
			Notes: "Pinned to the dated Geofabrik extract used by the app's CI pipeline. " +
				"Run `map-server catalog pin --region queensland --sha256 <hash>` after the first verified download.",
			Source: Source{
				URL:  "https://download.geofabrik.de/australia-oceania/australia/queensland-260928.osm.pbf",
				Date: "260928",
			},
		},
	}}
}

// Load reads a catalog file.
func Load(path string) (*Catalog, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Catalog
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return nil, fmt.Errorf("decode catalog: %w", err)
	}
	if err := c.Validate(); err != nil {
		return nil, err
	}
	return &c, nil
}

// Validate checks catalog invariants.
func (c *Catalog) Validate() error {
	if len(c.Regions) == 0 {
		return fmt.Errorf("catalog has no regions")
	}
	seen := map[string]bool{}
	for i, r := range c.Regions {
		if err := r.Validate(); err != nil {
			return fmt.Errorf("region %d (%s): %w", i, r.ID, err)
		}
		if seen[r.ID] {
			return fmt.Errorf("duplicate region id %q", r.ID)
		}
		seen[r.ID] = true
	}
	return nil
}

// Validate checks one region entry.
func (r Region) Validate() error {
	if !idPattern.MatchString(r.ID) {
		return fmt.Errorf("invalid id %q", r.ID)
	}
	if strings.TrimSpace(r.Name) == "" {
		return fmt.Errorf("missing name")
	}
	if !datePattern.MatchString(r.Source.Date) {
		return fmt.Errorf("invalid source date %q (want YYMMDD or YYYY-MM-DD)", r.Source.Date)
	}
	hasURL := r.Source.URL != ""
	hasPath := r.Source.Path != ""
	if hasURL == hasPath {
		return fmt.Errorf("set exactly one of source.url or source.path")
	}
	if hasURL {
		u, err := url.Parse(r.Source.URL)
		if err != nil {
			return fmt.Errorf("invalid source url: %w", err)
		}
		if u.Scheme != "https" {
			return fmt.Errorf("source url must use https, got %q", u.Scheme)
		}
		if u.Host == "" {
			return fmt.Errorf("source url has no host")
		}
	}
	if r.Source.SHA256 != "" && !hexPattern.MatchString(r.Source.SHA256) {
		return fmt.Errorf("invalid source sha256 %q", r.Source.SHA256)
	}
	if r.Source.MD5 != "" && !md5Pattern.MatchString(r.Source.MD5) {
		return fmt.Errorf("invalid source md5 %q", r.Source.MD5)
	}
	if r.Source.MaxBytes < 0 {
		return fmt.Errorf("negative source maxBytes")
	}
	if r.MaxZoom < 0 || r.MaxZoom > 16 {
		return fmt.Errorf("maxZoom %d out of range 0..16", r.MaxZoom)
	}
	return nil
}

// EffectiveMaxZoom returns the zoom with the pipeline default applied.
func (r Region) EffectiveMaxZoom() int {
	if r.MaxZoom == 0 {
		return 14
	}
	return r.MaxZoom
}

// Lookup finds an enabled region by id, operator regions first.
func (c *Catalog) Lookup(id string) (Region, error) {
	for _, r := range c.Regions {
		if r.ID == id {
			if r.Disabled {
				return Region{}, fmt.Errorf("%w: %q", ErrDisabled, id)
			}
			return r, nil
		}
	}
	if store := c.store(); store != nil {
		if r, ok := store.Get(id); ok {
			if r.Disabled {
				return Region{}, fmt.Errorf("%w: %q", ErrDisabled, id)
			}
			return r, nil
		}
	}
	return Region{}, fmt.Errorf("unknown region %q", id)
}

// List returns the operator regions in file order followed by requested ones.
// A requested region whose id collides with an operator region is hidden.
func (c *Catalog) List() []Region {
	out := append([]Region(nil), c.Regions...)
	store := c.store()
	if store == nil {
		return out
	}
	operator := make(map[string]bool, len(c.Regions))
	for _, r := range c.Regions {
		operator[r.ID] = true
	}
	for _, r := range store.List() {
		if !operator[r.ID] {
			out = append(out, r)
		}
	}
	return out
}

func (c *Catalog) store() *RequestedStore {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.requested
}
