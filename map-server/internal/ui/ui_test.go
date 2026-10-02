package ui

import (
	"strings"
	"testing"
)

func TestEmbeddedTemplatesParse(t *testing.T) {
	if _, err := New(); err != nil {
		t.Fatalf("embedded templates do not parse: %v", err)
	}
}

func TestRenderSmoke(t *testing.T) {
	templates, err := New()
	if err != nil {
		t.Fatal(err)
	}
	page := Page{
		Version:           "test",
		GenerationEnabled: true,
		QueueDepth:        1,
		MaxQueued:         8,
		ArtifactRoot:      "/tmp/artifacts",
		CSRF:              "token",
		Regions: []RegionView{{
			ID: "queensland", Name: "Queensland", SourceKind: "Geofabrik URL", SourceDate: "260928",
			SourceSHA: "pinned 0123456789ab",
		}},
		Jobs: []JobView{{
			ID: "job1", RegionName: "Queensland", State: "running", Step: "tiles",
			ProgressPercent: 40, Message: "generating", CreatedAt: "2026-10-01", Active: true, Cancelable: true,
		}},
	}
	var out strings.Builder
	if err := templates.Render(&out, "index", page); err != nil {
		t.Fatalf("render index: %v", err)
	}
	body := out.String()
	for _, want := range []string{"Queensland", "Request build", "csrf_token", "job1", "generating"} {
		if !strings.Contains(body, want) {
			t.Fatalf("rendered index missing %q", want)
		}
	}
	if err := templates.Render(&out, "regionsFragment", page); err != nil {
		t.Fatalf("render regions fragment: %v", err)
	}
	if err := templates.Render(&out, "jobsFragment", page); err != nil {
		t.Fatalf("render jobs fragment: %v", err)
	}
}

func TestRequestFormOnlyWhenEnabled(t *testing.T) {
	templates, err := New()
	if err != nil {
		t.Fatal(err)
	}
	render := func(enabled bool) string {
		var out strings.Builder
		if err := templates.Render(&out, "index", Page{CSRF: "tok", RequestsEnabled: enabled, MaxExtract: "4.0 GiB"}); err != nil {
			t.Fatal(err)
		}
		return out.String()
	}
	on := render(true)
	for _, want := range []string{`action="/ui/requests"`, `name="place"`, "Place name or lat,lon", "4.0 GiB"} {
		if !strings.Contains(on, want) {
			t.Fatalf("enabled index missing %q", want)
		}
	}
	if off := render(false); strings.Contains(off, "/ui/requests") {
		t.Fatal("request form shown while the feature is off")
	}
}
