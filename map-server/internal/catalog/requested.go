package catalog

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// ErrRequestedFull means the requested-regions list holds its maximum.
var ErrRequestedFull = errors.New("the requested regions list is full")

// RequestedStore persists the regions clients asked for as one JSON document,
// written atomically (temp file + fsync + rename).
type RequestedStore struct {
	path string
	max  int

	mu      sync.Mutex
	regions []Region
}

type requestedDoc struct {
	Regions []Region `json:"regions"`
}

// OpenRequested opens (or creates lazily) the store at path. max <= 0 means
// unlimited.
func OpenRequested(path string, max int) (*RequestedStore, error) {
	s := &RequestedStore{path: path, max: max}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	var doc requestedDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, fmt.Errorf("decode requested regions %s: %w", path, err)
	}
	for i, r := range doc.Regions {
		if err := r.Validate(); err != nil {
			return nil, fmt.Errorf("requested region %d (%s): %w", i, r.ID, err)
		}
	}
	s.regions = doc.Regions
	return s, nil
}

// Get returns one requested region.
func (s *RequestedStore) Get(id string) (Region, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.regions {
		if r.ID == id {
			return r, true
		}
	}
	return Region{}, false
}

// List returns a copy of the requested regions in insertion order.
func (s *RequestedStore) List() []Region {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Region(nil), s.regions...)
}

// Upsert validates and stores a region. Replacing an existing id always
// works; adding a new one beyond the maximum returns ErrRequestedFull.
func (s *RequestedStore) Upsert(region Region) error {
	if err := region.Validate(); err != nil {
		return err
	}
	region.Requested = true
	s.mu.Lock()
	defer s.mu.Unlock()
	next := append([]Region(nil), s.regions...)
	replaced := false
	for i := range next {
		if next[i].ID == region.ID {
			next[i] = region
			replaced = true
			break
		}
	}
	if !replaced {
		if s.max > 0 && len(next) >= s.max {
			return ErrRequestedFull
		}
		next = append(next, region)
	}
	return s.saveLocked(next)
}

// PinSHA records the observed source hash for a region so later requests can
// reuse its cached build.
func (s *RequestedStore) PinSHA(id, sha256 string) error {
	if !hexPattern.MatchString(sha256) {
		return fmt.Errorf("invalid sha256 %q", sha256)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	next := append([]Region(nil), s.regions...)
	for i := range next {
		if next[i].ID == id {
			next[i].Source.SHA256 = sha256
			return s.saveLocked(next)
		}
	}
	return fmt.Errorf("unknown requested region %q", id)
}

// saveLocked persists next and, only on success, adopts it.
func (s *RequestedStore) saveLocked(next []Region) error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	doc, err := json.MarshalIndent(requestedDoc{Regions: next}, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := f.Write(doc); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.path); err != nil {
		return err
	}
	s.regions = next
	return nil
}
