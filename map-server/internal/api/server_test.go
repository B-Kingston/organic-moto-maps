package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/jobs"
	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
	"github.com/organicmoto/curveMaps/map-server/internal/security"
	"github.com/organicmoto/curveMaps/map-server/internal/trustedproxy"
)

const testFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type fakeBuilder struct {
	cfg   pipeline.Config
	t     *testing.T
	calls int
	// gate, when set, holds every Build until it is closed.
	gate chan struct{}
}

func (f *fakeBuilder) Config() pipeline.Config { return f.cfg }
func (f *fakeBuilder) FingerprintFor(catalog.Region, string) (string, error) {
	return testFingerprint, nil
}
func (f *fakeBuilder) ArtifactExists(regionID, fingerprint string) bool {
	_, err := os.Stat(f.ArtifactPackagePath(regionID, fingerprint))
	return err == nil
}
func (f *fakeBuilder) ArtifactPackagePath(regionID, fingerprint string) string {
	return filepath.Join(f.cfg.ArtifactRoot, regionID, fingerprint, regionID+".motomap")
}
func (f *fakeBuilder) LatestArtifact(regionID string) (pipeline.ArtifactInfo, bool) {
	entries, err := os.ReadDir(filepath.Join(f.cfg.ArtifactRoot, regionID))
	if err != nil {
		return pipeline.ArtifactInfo{}, false
	}
	for _, e := range entries {
		pkg := f.ArtifactPackagePath(regionID, e.Name())
		if info, err := os.Stat(pkg); err == nil {
			return pipeline.ArtifactInfo{
				Fingerprint: e.Name(), PackagePath: pkg, Size: info.Size(),
				SidecarPath: pkg + ".sha256",
			}, true
		}
	}
	return pipeline.ArtifactInfo{}, false
}
func (f *fakeBuilder) Build(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
	f.calls++
	if f.gate != nil {
		select {
		case <-f.gate:
		case <-ctx.Done():
			return pipeline.Result{}, ctx.Err()
		}
	}
	pkg := f.ArtifactPackagePath(region.ID, testFingerprint)
	if err := writePackage(f.t, pkg, region.ID); err != nil {
		return pipeline.Result{}, err
	}
	return pipeline.Result{
		Fingerprint:  testFingerprint,
		ArtifactPath: pkg,
		SidecarPath:  pkg + ".sha256",
		Manifest:     manifest.Manifest{Region: manifest.Region{ID: region.ID}},
	}, nil
}

// writePackage writes a minimal valid `.motomap` (used directly by tests and
// by the fake builder).
func writePackage(t *testing.T, pkg, regionID string) error {
	t.Helper()
	dir := t.TempDir()
	tiles := filepath.Join(dir, "basemap.pmtiles")
	tileBytes := append([]byte("PMTiles"), 3)
	tileBytes = append(tileBytes, make([]byte, 127-len(tileBytes))...)
	if err := os.WriteFile(tiles, tileBytes, 0o644); err != nil {
		return err
	}
	graph := filepath.Join(dir, "graph")
	if err := os.MkdirAll(graph, 0o755); err != nil {
		return err
	}
	for name, content := range map[string]string{
		"properties": "profiles=motorcycle|198752012\n",
		"nodes":      "nodes",
	} {
		if err := os.WriteFile(filepath.Join(graph, name), []byte(content), 0o644); err != nil {
			return err
		}
	}
	geocoder := filepath.Join(dir, "geocoder.dat")
	if err := os.WriteFile(geocoder, append([]byte(manifest.GeocoderMagic), make([]byte, 32)...), 0o644); err != nil {
		return err
	}
	tilesComp, err := motomap.BuildComponent(manifest.ComponentTiles, tiles, manifest.FormatPMTilesV3, "")
	if err != nil {
		return err
	}
	graphComp, err := motomap.BuildComponent(manifest.ComponentGraph, graph, manifest.FormatGraphDir, "motorcycle|198752012")
	if err != nil {
		return err
	}
	geoComp, err := motomap.BuildComponent(manifest.ComponentGeocoder, geocoder, manifest.FormatGeocoder, "")
	if err != nil {
		return err
	}
	m := manifest.Manifest{
		SchemaVersion: manifest.SchemaVersion,
		PackageID:     regionID + "-260928-0123456789ab",
		Region:        manifest.Region{ID: regionID, Name: regionID},
		Source:        manifest.Source{Date: "260928", SHA256: motomap.DigestString("source")},
		GeneratedAt:   "2026-10-01T00:00:00Z",
		Generator:     manifest.Generator{Name: "test", Version: "1", PipelineFingerprint: testFingerprint},
		Components:    []manifest.Component{tilesComp, graphComp, geoComp},
		Compatibility: manifest.Compatibility{
			PackageSchema: manifest.SchemaVersion, GraphProfile: "motorcycle|198752012",
			GeocoderMagic: manifest.GeocoderMagic, TilesFormat: manifest.FormatPMTilesV3,
		},
		Attribution: manifest.Attribution,
	}
	if err := os.MkdirAll(filepath.Dir(pkg), 0o755); err != nil {
		return err
	}
	if err := motomap.Write(pkg, m, map[string]string{
		manifest.ComponentTiles:    tiles,
		manifest.ComponentGraph:    graph,
		manifest.ComponentGeocoder: geocoder,
	}, nil); err != nil {
		return err
	}
	_, err = motomap.WriteSidecar(pkg)
	return err
}

type testServer struct {
	*Server
	manager *jobs.Manager
	builder *fakeBuilder
	handler http.Handler
	csrf    *security.CSRF
	now     time.Time
}

func newTestServer(t *testing.T, mutate func(*Config, *pipeline.Config)) *testServer {
	t.Helper()
	state := t.TempDir()
	builder := &fakeBuilder{cfg: pipeline.Config{ArtifactRoot: filepath.Join(state, "artifacts")}, t: t}
	cat := &catalog.Catalog{Regions: []catalog.Region{
		{ID: "queensland", Name: "Queensland", Coverage: "QLD",
			Source: catalog.Source{URL: "https://example.invalid/q.osm.pbf", Date: "260928", SHA256: strings.Repeat("a", 64)}},
		{ID: "tasmania", Name: "Tasmania",
			Source: catalog.Source{Path: "/data/tasmania.osm.pbf", Date: "260928"}},
		{ID: "broken", Name: "Broken", Disabled: true,
			Source: catalog.Source{URL: "https://example.invalid/b.osm.pbf", Date: "260928", SHA256: strings.Repeat("b", 64)}},
	}}
	store, err := jobs.NewStore(filepath.Join(state, "jobs.json"), 50)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	manager := jobs.NewManager(store, cat, builder, jobs.Options{MaxQueued: 4}, logger)
	if err := manager.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = manager.Shutdown(ctx)
	})
	now := time.Unix(1_700_000_000, 0)
	csrf := security.NewCSRF([]byte("0123456789abcdef0123456789abcdef"), time.Hour, false)
	cfg := Config{
		Version:   "test",
		Catalog:   cat,
		Jobs:      manager,
		Generator: builder,
		Trusted:   mustProxies(t),
		CSRF:      csrf,
		Limits:    DefaultLimits(),
		Log:       logger,
		Now:       func() time.Time { return now },
	}
	if mutate != nil {
		mutate(&cfg, &builder.cfg)
	}
	server, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Close)
	return &testServer{Server: server, manager: manager, builder: builder, handler: server.Handler(), csrf: csrf, now: now}
}

func mustProxies(t *testing.T) *trustedproxy.Set {
	t.Helper()
	set, err := trustedproxy.Parse(nil)
	if err != nil {
		t.Fatal(err)
	}
	return set
}

func (s *testServer) do(t *testing.T, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	s.handler.ServeHTTP(rec, req)
	return rec
}

func TestHealthAndSecurityHeaders(t *testing.T) {
	ts := newTestServer(t, nil)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	req.RemoteAddr = "203.0.113.5:1234"
	rec := ts.do(t, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("health status %d", rec.Code)
	}
	if rec.Header().Get("X-Content-Type-Options") != "nosniff" ||
		!strings.Contains(rec.Header().Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatal("security headers missing")
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["status"] != "ok" {
		t.Fatalf("health body: %v", body)
	}
}

func TestCatalogListsRegionsAndCachedArtifacts(t *testing.T) {
	ts := newTestServer(t, nil)
	pkg := ts.builder.ArtifactPackagePath("queensland", testFingerprint)
	if err := writePackage(t, pkg, "queensland"); err != nil {
		t.Fatal(err)
	}
	rec := ts.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/catalog", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("catalog status %d", rec.Code)
	}
	var body struct {
		Regions []struct {
			ID       string `json:"id"`
			Disabled bool   `json:"disabled"`
			Artifact struct {
				Available   bool   `json:"available"`
				DownloadURL string `json:"downloadUrl"`
			} `json:"artifact"`
		} `json:"regions"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Regions) != 3 {
		t.Fatalf("regions: %+v", body.Regions)
	}
	var qld bool
	for _, r := range body.Regions {
		if r.ID == "queensland" {
			qld = r.Artifact.Available && strings.Contains(r.Artifact.DownloadURL, testFingerprint)
		}
		if r.ID == "broken" && !r.Disabled {
			t.Fatal("disabled region not reported")
		}
	}
	if !qld {
		t.Fatal("cached artifact not reported")
	}
}

func TestBuildRequestRequiresCSRFAndDeduplicates(t *testing.T) {
	ts := newTestServer(t, nil)
	// No token: rejected.
	req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"regionId":"queensland"}`))
	req.Header.Set("Content-Type", "application/json")
	if rec := ts.do(t, req); rec.Code != http.StatusForbidden {
		t.Fatalf("CSRF-less build status %d", rec.Code)
	}
	// Fetch a token, then submit.
	rec := ts.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/csrf", nil))
	var tokenDoc struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &tokenDoc); err != nil || tokenDoc.Token == "" {
		t.Fatalf("csrf token: %v %q", err, rec.Body.String())
	}
	submit := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"regionId":"queensland"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(security.HeaderName, tokenDoc.Token)
		req.RemoteAddr = "203.0.113.5:1234"
		return ts.do(t, req)
	}
	first := submit()
	if first.Code != http.StatusAccepted {
		t.Fatalf("build status %d: %s", first.Code, first.Body.String())
	}
	second := submit()
	if second.Code != http.StatusAccepted {
		t.Fatalf("dedup status %d", second.Code)
	}
	var jobA, jobB jobs.Public
	_ = json.Unmarshal(first.Body.Bytes(), &jobA)
	_ = json.Unmarshal(second.Body.Bytes(), &jobB)
	if jobA.ID != jobB.ID {
		t.Fatalf("dedup failed: %s vs %s", jobA.ID, jobB.ID)
	}
	// Unknown and disabled regions are rejected distinctly.
	unknown := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"regionId":"nowhere"}`))
	unknown.Header.Set(security.HeaderName, tokenDoc.Token)
	if rec := ts.do(t, unknown); rec.Code != http.StatusNotFound {
		t.Fatalf("unknown region status %d", rec.Code)
	}
	disabled := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"regionId":"broken"}`))
	disabled.Header.Set(security.HeaderName, tokenDoc.Token)
	if rec := ts.do(t, disabled); rec.Code != http.StatusForbidden {
		t.Fatalf("disabled region status %d", rec.Code)
	}
}

func TestBuildRateLimitApplies(t *testing.T) {
	ts := newTestServer(t, func(cfg *Config, _ *pipeline.Config) {
		cfg.Limits.BuildBurst = 1
		cfg.Limits.BuildPer = time.Hour
	})
	token := ts.csrf.Token(ts.now)
	submit := func() int {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"regionId":"queensland"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(security.HeaderName, token)
		req.RemoteAddr = "203.0.113.5:1234"
		return ts.do(t, req).Code
	}
	if code := submit(); code != http.StatusAccepted {
		t.Fatalf("first build status %d", code)
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"regionId":"tasmania"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(security.HeaderName, token)
	req.RemoteAddr = "203.0.113.5:1234"
	ts.handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("second build status %d", rec.Code)
	}
	if rec.Header().Get("Retry-After") == "" {
		t.Fatal("missing Retry-After")
	}
}

func TestDownloadRangeConditionalAndPathSafety(t *testing.T) {
	ts := newTestServer(t, nil)
	pkg := ts.builder.ArtifactPackagePath("queensland", testFingerprint)
	if err := writePackage(t, pkg, "queensland"); err != nil {
		t.Fatal(err)
	}
	url := "/api/v1/artifacts/queensland/" + testFingerprint + "/queensland.motomap"

	// Plain GET.
	full := ts.do(t, httptest.NewRequest(http.MethodGet, url, nil))
	if full.Code != http.StatusOK {
		t.Fatalf("download status %d", full.Code)
	}
	total := full.Body.Len()
	if total == 0 {
		t.Fatal("empty download")
	}
	etag := full.Header().Get("ETag")
	if etag == "" || full.Header().Get("Cache-Control") == "" {
		t.Fatal("missing caching headers")
	}
	if full.Header().Get("Accept-Ranges") != "bytes" {
		t.Fatal("missing Accept-Ranges")
	}

	// Range resume.
	rangeReq := httptest.NewRequest(http.MethodGet, url, nil)
	rangeReq.Header.Set("Range", "bytes=10-19")
	part := ts.do(t, rangeReq)
	if part.Code != http.StatusPartialContent || part.Body.Len() != 10 {
		t.Fatalf("range status %d len %d", part.Code, part.Body.Len())
	}
	if got := part.Header().Get("Content-Range"); !strings.HasPrefix(got, "bytes 10-19/") {
		t.Fatalf("content-range %q", got)
	}

	// Conditional request.
	cond := httptest.NewRequest(http.MethodGet, url, nil)
	cond.Header.Set("If-None-Match", etag)
	if rec := ts.do(t, cond); rec.Code != http.StatusNotModified {
		t.Fatalf("conditional status %d", rec.Code)
	}
	// If-Range mismatch must fall back to a full response.
	mismatch := httptest.NewRequest(http.MethodGet, url, nil)
	mismatch.Header.Set("Range", "bytes=0-4")
	mismatch.Header.Set("If-Range", `"different"`)
	if rec := ts.do(t, mismatch); rec.Code != http.StatusOK || rec.Body.Len() != total {
		t.Fatalf("if-range fallback status %d len %d", rec.Code, rec.Body.Len())
	}

	// Sidecar download.
	side := ts.do(t, httptest.NewRequest(http.MethodGet,
		"/api/v1/artifacts/queensland/"+testFingerprint+"/queensland.motomap.sha256", nil))
	if side.Code != http.StatusOK || !regexp.MustCompile(`^[0-9a-f]{64}\n?$`).MatchString(side.Body.String()) {
		t.Fatalf("sidecar: %d %q", side.Code, side.Body.String())
	}

	// Unsafe or unknown names. A traversal path is either rejected outright or
	// redirected by the mux to its cleaned form, which then 404s.
	for _, bad := range []string{
		"/api/v1/artifacts/queensland/" + testFingerprint + "/../../etc/passwd",
		"/api/v1/artifacts/queensland/nothex/queensland.motomap",
		"/api/v1/artifacts/nowhere/" + testFingerprint + "/nowhere.motomap",
		"/api/v1/artifacts/queensland/" + testFingerprint + "/other.motomap",
	} {
		rec := ts.do(t, httptest.NewRequest(http.MethodGet, bad, nil))
		switch rec.Code {
		case http.StatusNotFound, http.StatusMovedPermanently, http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
		default:
			t.Fatalf("%s status %d", bad, rec.Code)
		}
	}
	cleaned := ts.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/artifacts/queensland/"+testFingerprint+"/etc/passwd", nil))
	if cleaned.Code != http.StatusNotFound {
		t.Fatalf("cleaned traversal path status %d", cleaned.Code)
	}
}

func TestUIIndexAndFormBuild(t *testing.T) {
	ts := newTestServer(t, nil)
	rec := ts.do(t, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("index status %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "Queensland") || !strings.Contains(body, "htmx.min.js") {
		t.Fatal("index page missing expected content")
	}
	match := regexp.MustCompile(`name="csrf_token" value="([^"]+)"`).FindStringSubmatch(body)
	if match == nil {
		t.Fatal("no csrf token in the form")
	}
	token := match[1]
	form := strings.NewReader("regionId=queensland&csrf_token=" + token)
	req := httptest.NewRequest(http.MethodPost, "/ui/builds", form)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: security.CookieName, Value: token})
	posted := ts.do(t, req)
	if posted.Code != http.StatusSeeOther {
		t.Fatalf("form build status %d: %s", posted.Code, posted.Body.String())
	}
	// The job appears in the polling fragment (it may already be completed:
	// the fake builder finishes instantly).
	listed := ts.manager.List("queensland", 1)
	if len(listed) != 1 {
		t.Fatalf("expected one job, got %d", len(listed))
	}
	frag := ts.do(t, httptest.NewRequest(http.MethodGet, "/ui/jobs", nil))
	if frag.Code != http.StatusOK || !strings.Contains(frag.Body.String(), listed[0].ID) {
		t.Fatalf("jobs fragment: %d %s", frag.Code, frag.Body.String())
	}
}

func TestHeadlessModeDisablesUI(t *testing.T) {
	ts := newTestServer(t, func(cfg *Config, _ *pipeline.Config) { cfg.Headless = true })
	if rec := ts.do(t, httptest.NewRequest(http.MethodGet, "/", nil)); rec.Code != http.StatusNotFound {
		t.Fatalf("headless index status %d", rec.Code)
	}
	if rec := ts.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)); rec.Code != http.StatusOK {
		t.Fatalf("headless health status %d", rec.Code)
	}
}

func TestAdminRoutesAreTokenGated(t *testing.T) {
	// Without a configured token the admin routes do not exist.
	plain := newTestServer(t, nil)
	if rec := plain.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/admin/jobs", nil)); rec.Code != http.StatusNotFound {
		t.Fatalf("admin route exposed without a token: %d", rec.Code)
	}
	// With a token, wrong and missing credentials fail; the right one works.
	ts := newTestServer(t, func(cfg *Config, _ *pipeline.Config) { cfg.AdminToken = "s3cret" })
	rec := ts.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/admin/jobs", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("missing token status %d", rec.Code)
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/jobs", nil)
	req.Header.Set("Authorization", "Bearer wrong")
	if rec := ts.do(t, req); rec.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token status %d", rec.Code)
	}
	req = httptest.NewRequest(http.MethodGet, "/api/v1/admin/jobs", nil)
	req.Header.Set("Authorization", "Bearer s3cret")
	if rec := ts.do(t, req); rec.Code != http.StatusOK {
		t.Fatalf("valid token status %d", rec.Code)
	}
}

func TestTrustedProxyChangesRateLimitIdentity(t *testing.T) {
	ts := newTestServer(t, func(cfg *Config, _ *pipeline.Config) {
		set, err := trustedproxy.Parse([]string{"10.0.0.0/8"})
		if err != nil {
			t.Fatal(err)
		}
		cfg.Trusted = set
		cfg.Limits.DefaultBurst = 1
		cfg.Limits.DefaultPer = time.Hour
	})
	call := func(forwarded string, remote string) int {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/csrf", nil)
		req.RemoteAddr = remote
		if forwarded != "" {
			req.Header.Set("X-Forwarded-For", forwarded)
		}
		return ts.do(t, req).Code
	}
	if code := call("203.0.113.9", "10.0.0.1:443"); code != http.StatusOK {
		t.Fatalf("first call %d", code)
	}
	if code := call("203.0.113.10", "10.0.0.1:443"); code != http.StatusOK {
		t.Fatalf("second client through the proxy was limited together: %d", code)
	}
	if code := call("203.0.113.9", "10.0.0.1:443"); code != http.StatusTooManyRequests {
		t.Fatalf("repeat client not limited: %d", code)
	}
}
