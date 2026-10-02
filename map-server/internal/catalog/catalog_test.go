package catalog

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultCatalogIsValid(t *testing.T) {
	cat := Default()
	if err := cat.Validate(); err != nil {
		t.Fatalf("built-in catalog invalid: %v", err)
	}
	region, err := cat.Lookup("queensland")
	if err != nil {
		t.Fatal(err)
	}
	if region.EffectiveMaxZoom() != 14 || region.Source.URL == "" {
		t.Fatalf("default region incomplete: %+v", region)
	}
}

func TestLookupRejectsUnknownAndDisabled(t *testing.T) {
	cat := &Catalog{Regions: []Region{
		{ID: "a", Name: "A", Source: Source{URL: "https://example.invalid/a.osm.pbf", Date: "260928"}},
		{ID: "b", Name: "B", Disabled: true, Source: Source{URL: "https://example.invalid/b.osm.pbf", Date: "260928"}},
	}}
	if _, err := cat.Lookup("missing"); err == nil {
		t.Fatal("unknown region accepted")
	}
	if _, err := cat.Lookup("b"); err == nil || !errorsIs(err, ErrDisabled) {
		t.Fatalf("disabled region error: %v", err)
	}
}

func errorsIs(err, target error) bool {
	for err != nil {
		if err == target {
			return true
		}
		type unwrapper interface{ Unwrap() error }
		u, ok := err.(unwrapper)
		if !ok {
			return false
		}
		err = u.Unwrap()
	}
	return false
}

func TestValidateRejectsBrokenRegions(t *testing.T) {
	base := func() Region {
		return Region{ID: "queensland", Name: "Queensland",
			Source: Source{URL: "https://example.invalid/q.osm.pbf", Date: "260928"}}
	}
	cases := map[string]func(*Region){
		"id":      func(r *Region) { r.ID = "Queensland!" },
		"name":    func(r *Region) { r.Name = "" },
		"date":    func(r *Region) { r.Source.Date = "tomorrow" },
		"both":    func(r *Region) { r.Source.Path = "/data/q.osm.pbf" },
		"neither": func(r *Region) { r.Source.URL = "" },
		"scheme":  func(r *Region) { r.Source.URL = "http://example.invalid/q.osm.pbf" },
		"host":    func(r *Region) { r.Source.URL = "https:///q.osm.pbf" },
		"sha":     func(r *Region) { r.Source.SHA256 = "abc" },
		"maxzoom": func(r *Region) { r.MaxZoom = 20 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			r := base()
			mutate(&r)
			if err := r.Validate(); err == nil {
				t.Fatal("broken region accepted")
			}
		})
	}
}

func TestLoadRejectsUnknownFieldsAndDuplicates(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "catalog.json")
	doc := map[string]any{
		"regions": []map[string]any{
			{"id": "aa", "name": "A", "source": map[string]any{"url": "https://example.invalid/a.osm.pbf", "date": "260928"}},
			{"id": "aa", "name": "A again", "source": map[string]any{"url": "https://example.invalid/a.osm.pbf", "date": "260928"}},
		},
		"surprise": true,
	}
	data, _ := json.Marshal(doc)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("catalog with unknown fields and duplicate ids accepted")
	}
	delete(doc, "surprise")
	doc["regions"] = doc["regions"].([]map[string]any)[:1]
	data, _ = json.Marshal(doc)
	_ = os.WriteFile(path, data, 0o644)
	cat, err := Load(path)
	if err != nil {
		t.Fatalf("valid catalog rejected: %v", err)
	}
	if len(cat.List()) != 1 {
		t.Fatalf("regions: %+v", cat.List())
	}
}
