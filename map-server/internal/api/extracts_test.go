package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/geofabrik"
	"github.com/organicmoto/curveMaps/map-server/internal/jobs"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
	"github.com/organicmoto/curveMaps/map-server/internal/security"
)

const testMaxExtract = 4 << 30

type fakeResolver struct {
	mu          sync.Mutex
	searchCalls int
	resolves    int
	search      []geofabrik.Candidate
	locate      []geofabrik.Candidate
	resolved    map[string]geofabrik.Resolved
	err         error
	resolveErr  error
}

func (f *fakeResolver) MaxExtractBytes() int64 { return testMaxExtract }
func (f *fakeResolver) Search(_ context.Context, q string, limit int) ([]geofabrik.Candidate, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.searchCalls++
	return f.search, f.err
}
func (f *fakeResolver) Locate(_ context.Context, lat, lon float64, limit int) ([]geofabrik.Candidate, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.locate, f.err
}
func (f *fakeResolver) Resolve(_ context.Context, id string) (geofabrik.Resolved, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.resolves++
	if f.resolveErr != nil {
		return geofabrik.Resolved{}, f.resolveErr
	}
	r, ok := f.resolved[id]
	if !ok {
		return geofabrik.Resolved{}, fmt.Errorf("%w: %q", geofabrik.ErrUnknownExtract, id)
	}
	return r, nil
}
func (f *fakeResolver) resolveCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.resolves
}

func candidate(id, name string, eligible bool) geofabrik.Candidate {
	return geofabrik.Candidate{
		Extract:  geofabrik.Extract{ID: id, Name: name},
		RegionID: geofabrik.RegionID(id),
		Bytes:    100 << 20,
		Eligible: eligible,
	}
}

func resolvedFor(id, name, date string) geofabrik.Resolved {
	return geofabrik.Resolved{
		Extract: geofabrik.Extract{ID: id, Name: name, Path: []string{"Australia and Oceania", "Australia"}},
		URL:     "https://download.geofabrik.de/australia-oceania/" + id + "-" + date + ".osm.pbf",
		Date:    date,
		MD5:     strings.Repeat("a", 32),
	}
}

type extractServer struct {
	*testServer
	resolver  *fakeResolver
	requested *catalog.RequestedStore
}

func newExtractServer(t *testing.T, maxRequested int) *extractServer {
	t.Helper()
	resolver := &fakeResolver{
		search: []geofabrik.Candidate{candidate("victoria", "Victoria", true)},
		locate: []geofabrik.Candidate{candidate("victoria", "Victoria", true)},
		resolved: map[string]geofabrik.Resolved{
			"victoria":   resolvedFor("victoria", "Victoria", "231110"),
			"us/oregon":  resolvedFor("us/oregon", "Oregon", "231110"),
			"us/idaho":   resolvedFor("us/idaho", "Idaho", "231110"),
			"queensland": resolvedFor("queensland", "Queensland", "231110"),
		},
	}
	store, err := catalog.OpenRequested(filepath.Join(t.TempDir(), "requested.json"), maxRequested)
	if err != nil {
		t.Fatal(err)
	}
	ts := newTestServer(t, func(cfg *Config, _ *pipeline.Config) {
		cfg.Extracts = resolver
		cfg.Requested = store
		cfg.Limits.BuildBurst = 1000
	})
	return &extractServer{testServer: ts, resolver: resolver, requested: store}
}

func (s *extractServer) csrfToken(t *testing.T) string {
	t.Helper()
	rec := s.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/csrf", nil))
	var doc struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &doc); err != nil || doc.Token == "" {
		t.Fatalf("csrf: %v %s", err, rec.Body.String())
	}
	return doc.Token
}

func (s *extractServer) post(t *testing.T, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(security.HeaderName, s.csrfToken(t))
	req.RemoteAddr = "203.0.113.9:1"
	return s.do(t, req)
}

func (s *extractServer) get(t *testing.T, target string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, target, nil)
	req.RemoteAddr = "203.0.113.9:1"
	return s.do(t, req)
}

func decodeJob(t *testing.T, rec *httptest.ResponseRecorder) jobs.Public {
	t.Helper()
	var job jobs.Public
	if err := json.Unmarshal(rec.Body.Bytes(), &job); err != nil {
		t.Fatalf("decode job: %v %s", err, rec.Body.String())
	}
	return job
}

func TestExtractsSearchByNameAndPoint(t *testing.T) {
	s := newExtractServer(t, 5)
	for _, target := range []string{"/api/v1/extracts?q=victoria&limit=3", "/api/v1/extracts?lat=-37.81&lon=144.96"} {
		rec := s.get(t, target)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s: %d %s", target, rec.Code, rec.Body.String())
		}
		var body struct {
			Extracts        []geofabrik.Candidate `json:"extracts"`
			MaxExtractBytes int64                 `json:"maxExtractBytes"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		if len(body.Extracts) != 1 || body.Extracts[0].ID != "victoria" || !body.Extracts[0].Eligible || body.MaxExtractBytes != testMaxExtract {
			t.Fatalf("%s body: %+v", target, body)
		}
	}
}

func TestExtractsRejectsBadParameters(t *testing.T) {
	s := newExtractServer(t, 5)
	for _, target := range []string{
		"/api/v1/extracts",
		"/api/v1/extracts?q=",
		"/api/v1/extracts?lat=1",
		"/api/v1/extracts?lon=1",
		"/api/v1/extracts?lat=abc&lon=1",
		"/api/v1/extracts?lat=91&lon=1",
		"/api/v1/extracts?lat=1&lon=181",
		"/api/v1/extracts?q=x&lat=1&lon=1",
		"/api/v1/extracts?q=x&limit=0",
		"/api/v1/extracts?q=x&limit=1000",
		"/api/v1/extracts?q=x&limit=abc",
	} {
		if rec := s.get(t, target); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: want 400, got %d %s", target, rec.Code, rec.Body.String())
		}
	}
}

func TestExtractsFeatureOffAndUpstreamDown(t *testing.T) {
	off := newTestServer(t, nil)
	if rec := off.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/extracts?q=x", nil)); rec.Code != http.StatusNotFound {
		t.Fatalf("feature off: %d", rec.Code)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(`{"query":"victoria"}`))
	req.Header.Set("Content-Type", "application/json")
	rec := off.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/csrf", nil))
	var tok struct{ Token string }
	_ = json.Unmarshal(rec.Body.Bytes(), &tok)
	req.Header.Set(security.HeaderName, tok.Token)
	if got := off.do(t, req); got.Code != http.StatusNotFound || !strings.Contains(got.Body.String(), "only builds catalog regions") {
		t.Fatalf("feature off build: %d %s", got.Code, got.Body.String())
	}
	var health struct {
		Requests struct {
			Enabled bool `json:"enabled"`
		} `json:"requests"`
	}
	_ = json.Unmarshal(off.do(t, httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)).Body.Bytes(), &health)
	if health.Requests.Enabled {
		t.Fatal("health reports requests enabled while off")
	}

	s := newExtractServer(t, 5)
	s.resolver.err = fmt.Errorf("%w: connection refused", geofabrik.ErrUpstream)
	rec = s.get(t, "/api/v1/extracts?q=victoria")
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "connection refused") {
		t.Fatalf("upstream: %d %s", rec.Code, rec.Body.String())
	}
}

func TestHealthReportsRequests(t *testing.T) {
	s := newExtractServer(t, 5)
	var health struct {
		Requests struct {
			Enabled         bool  `json:"enabled"`
			MaxExtractBytes int64 `json:"maxExtractBytes"`
		} `json:"requests"`
	}
	_ = json.Unmarshal(s.get(t, "/api/v1/health").Body.Bytes(), &health)
	if !health.Requests.Enabled || health.Requests.MaxExtractBytes != testMaxExtract {
		t.Fatalf("health: %+v", health)
	}
}

func TestSearchRateLimitClass(t *testing.T) {
	s := newTestServer(t, func(cfg *Config, _ *pipeline.Config) {
		cfg.Extracts = &fakeResolver{}
		cfg.Requested, _ = catalog.OpenRequested(filepath.Join(t.TempDir(), "r.json"), 1)
		cfg.Limits.SearchBurst = 2
		cfg.Limits.SearchPer = time.Hour
	})
	codes := []int{}
	for i := 0; i < 3; i++ {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/extracts?q=x", nil)
		req.RemoteAddr = "203.0.113.50:1"
		codes = append(codes, s.do(t, req).Code)
	}
	if codes[0] != 200 || codes[1] != 200 || codes[2] != http.StatusTooManyRequests {
		t.Fatalf("codes: %v", codes)
	}
}

func TestBuildByExtractIDCreatesRequestedRegion(t *testing.T) {
	s := newExtractServer(t, 5)
	rec := s.post(t, `{"extractId":"us/oregon"}`)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	job := decodeJob(t, rec)
	if job.RegionID != "us-oregon" {
		t.Fatalf("job region %q", job.RegionID)
	}
	region, ok := s.requested.Get("us-oregon")
	if !ok {
		t.Fatal("region not recorded")
	}
	if !region.Requested || region.ExtractID != "us/oregon" || region.Name != "Oregon" ||
		region.Coverage != "Australia and Oceania › Australia › Oregon" || region.MaxZoom != 14 ||
		region.Source.Date != "231110" || region.Source.MD5 != strings.Repeat("a", 32) ||
		region.Source.MaxBytes != testMaxExtract || !strings.Contains(region.Notes, "us/oregon") ||
		!strings.HasPrefix(region.Source.URL, "https://download.geofabrik.de/") {
		t.Fatalf("region: %+v", region)
	}
}

func TestBuildByQueryExactMatch(t *testing.T) {
	s := newExtractServer(t, 5)
	rec := s.post(t, `{"query":"victoria"}`)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	if decodeJob(t, rec).RegionID != "victoria" {
		t.Fatalf("job: %s", rec.Body.String())
	}
}

func TestBuildByQueryAmbiguousReturnsCandidates(t *testing.T) {
	s := newExtractServer(t, 5)
	s.resolver.search = []geofabrik.Candidate{
		candidate("us/georgia", "Georgia", true),
		candidate("europe/georgia", "Georgia", true),
		candidate("us/oregon", "Oregon", true),
	}
	rec := s.post(t, `{"query":"Georgia"}`)
	if rec.Code != http.StatusConflict {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Error      string                `json:"error"`
		Candidates []geofabrik.Candidate `json:"candidates"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	if len(body.Candidates) != 3 || !strings.Contains(body.Error, "more specific") {
		t.Fatalf("body: %s", rec.Body.String())
	}
	if s.resolver.resolveCount() != 0 || len(s.requested.List()) != 0 {
		t.Fatal("ambiguous query must not resolve or record anything")
	}
	// A substring match with no exact name is also ambiguous.
	s.resolver.search = []geofabrik.Candidate{candidate("us/oregon", "Oregon", true)}
	if rec := s.post(t, `{"query":"Oreg"}`); rec.Code != http.StatusConflict {
		t.Fatalf("partial match status %d", rec.Code)
	}
	// An exact match that is ineligible does not count.
	s.resolver.search = []geofabrik.Candidate{candidate("europe", "Europe", false)}
	if rec := s.post(t, `{"query":"europe"}`); rec.Code != http.StatusConflict {
		t.Fatalf("ineligible exact status %d", rec.Code)
	}
}

func TestBuildByLatLonPicksFirstEligible(t *testing.T) {
	s := newExtractServer(t, 5)
	s.resolver.locate = []geofabrik.Candidate{
		candidate("hobart-metro", "Hobart", false),
		candidate("victoria", "Victoria", true),
		candidate("australia", "Australia", true),
	}
	rec := s.post(t, `{"lat":0,"lon":0}`) // zero coordinates are valid
	if rec.Code != http.StatusAccepted || decodeJob(t, rec).RegionID != "victoria" {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBuildByLatLonNothingEligible(t *testing.T) {
	s := newExtractServer(t, 5)
	s.resolver.locate = []geofabrik.Candidate{candidate("europe", "Europe", false)}
	rec := s.post(t, `{"lat":48.1,"lon":11.5}`)
	if rec.Code != http.StatusUnprocessableEntity || !strings.Contains(rec.Body.String(), `"candidates"`) {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBuildRequestShapeValidation(t *testing.T) {
	s := newExtractServer(t, 5)
	for _, body := range []string{
		`{}`,
		`{"lat":1}`,
		`{"lon":1}`,
		`{"regionId":"queensland","query":"x"}`,
		`{"extractId":"victoria","lat":1,"lon":1}`,
		`{"lat":95,"lon":0}`,
		`{"query":"x","bogus":1}`,
	} {
		if rec := s.post(t, body); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: want 400, got %d %s", body, rec.Code, rec.Body.String())
		}
	}
}

func TestBuildMapsResolveErrors(t *testing.T) {
	s := newExtractServer(t, 5)
	cases := []struct {
		err  error
		want int
	}{
		{fmt.Errorf("%w: nowhere", geofabrik.ErrUnknownExtract), http.StatusNotFound},
		{fmt.Errorf("%w: 9 GiB", geofabrik.ErrTooLarge), http.StatusRequestEntityTooLarge},
		{fmt.Errorf("%w: Europe is a continent", geofabrik.ErrNotBuildable), http.StatusUnprocessableEntity},
		{fmt.Errorf("%w: timeout", geofabrik.ErrUpstream), http.StatusServiceUnavailable},
	}
	for _, c := range cases {
		s.resolver.resolveErr = c.err
		rec := s.post(t, `{"extractId":"us/oregon"}`)
		if rec.Code != c.want || !strings.Contains(rec.Body.String(), c.err.Error()) {
			t.Errorf("%v: want %d, got %d %s", c.err, c.want, rec.Code, rec.Body.String())
		}
	}
	if len(s.requested.List()) != 0 {
		t.Fatal("failed resolution must not record a region")
	}
}

func TestBuildOperatorRegionCollisionUsesOperatorRegion(t *testing.T) {
	s := newExtractServer(t, 5)
	rec := s.post(t, `{"extractId":"queensland"}`)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	if s.resolver.resolveCount() != 0 || len(s.requested.List()) != 0 {
		t.Fatal("operator region must not be re-resolved or shadowed")
	}
	if decodeJob(t, rec).RegionID != "queensland" {
		t.Fatalf("job: %s", rec.Body.String())
	}
}

func TestActiveJobIsNotReResolved(t *testing.T) {
	s := newExtractServer(t, 5)
	s.builder.gate = make(chan struct{})
	first := s.post(t, `{"extractId":"us/oregon"}`)
	if first.Code != http.StatusAccepted {
		t.Fatalf("first: %d %s", first.Code, first.Body.String())
	}
	second := s.post(t, `{"extractId":"us/oregon"}`)
	if second.Code != http.StatusAccepted || decodeJob(t, first).ID != decodeJob(t, second).ID {
		t.Fatalf("second: %d %s", second.Code, second.Body.String())
	}
	if n := s.resolver.resolveCount(); n != 1 {
		t.Fatalf("resolver called %d times, want 1", n)
	}
	close(s.builder.gate)
}

func TestPinnedFreshRequestedRegionSkipsResolve(t *testing.T) {
	s := newExtractServer(t, 5)
	region := catalog.Region{
		ID: "victoria", Name: "Victoria", MaxZoom: 14, Requested: true, ExtractID: "victoria",
		Source: catalog.Source{URL: "https://download.geofabrik.de/t.osm.pbf", Date: "231110", SHA256: strings.Repeat("c", 64)},
	}
	if err := s.requested.Upsert(region); err != nil {
		t.Fatal(err)
	}
	// A cached artifact exists, so the pinned hash serves it directly.
	if err := writePackage(t, s.builder.ArtifactPackagePath("victoria", testFingerprint), "victoria"); err != nil {
		t.Fatal(err)
	}
	rec := s.post(t, `{"extractId":"victoria"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("cache hit status %d: %s", rec.Code, rec.Body.String())
	}
	if s.resolver.resolveCount() != 0 {
		t.Fatal("fresh pinned region was re-resolved")
	}
}

func TestStaleRequestedRegionIsReResolved(t *testing.T) {
	s := newExtractServer(t, 5)
	region := catalog.Region{
		ID: "victoria", Name: "Victoria", MaxZoom: 14, Requested: true, ExtractID: "victoria",
		Source: catalog.Source{URL: "https://download.geofabrik.de/t.osm.pbf", Date: "230101", SHA256: strings.Repeat("c", 64)},
	}
	if err := s.requested.Upsert(region); err != nil {
		t.Fatal(err)
	}
	rec := s.post(t, `{"extractId":"victoria"}`)
	if rec.Code != http.StatusAccepted && rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	if s.resolver.resolveCount() != 1 {
		t.Fatalf("stale region resolved %d times", s.resolver.resolveCount())
	}
	got, _ := s.requested.Get("victoria")
	if got.Source.Date != "231110" || got.Source.SHA256 != "" {
		t.Fatalf("stale entry not refreshed: %+v", got)
	}
}

func TestUnpinnedRequestedRegionIsReResolved(t *testing.T) {
	s := newExtractServer(t, 5)
	if rec := s.post(t, `{"extractId":"victoria"}`); rec.Code != http.StatusAccepted {
		t.Fatalf("first: %d", rec.Code)
	}
	waitIdle(t, s, "victoria")
	if rec := s.post(t, `{"extractId":"victoria"}`); rec.Code >= 300 && rec.Code != http.StatusAccepted {
		t.Fatalf("second: %d", rec.Code)
	}
	if s.resolver.resolveCount() != 2 {
		t.Fatalf("resolve count %d, want 2 (no pinned hash yet)", s.resolver.resolveCount())
	}
}

func waitIdle(t *testing.T, s *extractServer, regionID string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if _, active := s.manager.ActiveFor(regionID); !active {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("job never finished")
}

func TestRequestedStoreFullReturns507(t *testing.T) {
	s := newExtractServer(t, 1)
	if rec := s.post(t, `{"extractId":"us/oregon"}`); rec.Code != http.StatusAccepted {
		t.Fatalf("first: %d %s", rec.Code, rec.Body.String())
	}
	rec := s.post(t, `{"extractId":"us/idaho"}`)
	if rec.Code != http.StatusInsufficientStorage || !strings.Contains(rec.Body.String(), "ask the operator") {
		t.Fatalf("full: %d %s", rec.Code, rec.Body.String())
	}
	if _, ok := s.requested.Get("us-idaho"); ok {
		t.Fatal("region recorded past the maximum")
	}
}

func TestOnBuildCompletedPinsRequestedRegionsOnly(t *testing.T) {
	s := newExtractServer(t, 5)
	if rec := s.post(t, `{"extractId":"victoria"}`); rec.Code != http.StatusAccepted {
		t.Fatalf("build: %d", rec.Code)
	}
	region, _ := s.requested.Get("victoria")
	sha := strings.Repeat("d", 64)
	s.OnBuildCompleted(region, pipeline.Result{Source: pipeline.SourceInfo{SHA256: sha}})
	if got, _ := s.requested.Get("victoria"); got.Source.SHA256 != sha {
		t.Fatalf("not pinned: %+v", got)
	}
	// Operator regions (Requested=false) are never written to the store.
	s.OnBuildCompleted(catalog.Region{ID: "queensland"}, pipeline.Result{Source: pipeline.SourceInfo{SHA256: sha}})
	if _, ok := s.requested.Get("queensland"); ok {
		t.Fatal("operator region leaked into the requested store")
	}
}

func TestCatalogListsRequestedRegions(t *testing.T) {
	s := newExtractServer(t, 5)
	if rec := s.post(t, `{"extractId":"us/oregon"}`); rec.Code != http.StatusAccepted {
		t.Fatalf("build: %d", rec.Code)
	}
	var body struct {
		Regions []struct {
			ID        string `json:"id"`
			Requested bool   `json:"requested"`
			ExtractID string `json:"extractId"`
		} `json:"regions"`
	}
	if err := json.Unmarshal(s.get(t, "/api/v1/catalog").Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	var seen bool
	for i, r := range body.Regions {
		if r.ID == "us-oregon" {
			seen = r.Requested && r.ExtractID == "us/oregon" && i >= 3
		} else if r.Requested {
			t.Fatalf("operator region flagged requested: %+v", r)
		}
	}
	if !seen || len(body.Regions) != 4 {
		t.Fatalf("catalog: %+v", body.Regions)
	}
}

func TestExtractBuildStillRequiresCSRF(t *testing.T) {
	s := newExtractServer(t, 5)
	for _, body := range []string{`{"extractId":"victoria"}`, `{"query":"victoria"}`, `{"lat":1,"lon":2}`} {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if rec := s.do(t, req); rec.Code != http.StatusForbidden {
			t.Errorf("%s without token: %d", body, rec.Code)
		}
	}
	if s.resolver.resolveCount() != 0 {
		t.Fatal("resolver reached without CSRF")
	}
}

func TestUIRequestFormHappyPathAndErrors(t *testing.T) {
	s := newExtractServer(t, 5)
	page := s.do(t, httptest.NewRequest(http.MethodGet, "/", nil)).Body.String()
	if !strings.Contains(page, `action="/ui/requests"`) {
		t.Fatal("request form missing from the index")
	}
	token := regexp.MustCompile(`name="csrf_token" value="([^"]+)"`).FindStringSubmatch(page)[1]
	submit := func(place string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/ui/requests",
			strings.NewReader("place="+strings.ReplaceAll(place, " ", "+")+"&csrf_token="+token))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.AddCookie(&http.Cookie{Name: security.CookieName, Value: token})
		return s.do(t, req)
	}
	if rec := submit("Victoria"); rec.Code != http.StatusSeeOther {
		t.Fatalf("name request: %d %s", rec.Code, rec.Body.String())
	}
	if _, ok := s.requested.Get("victoria"); !ok {
		t.Fatal("name request did not record the region")
	}
	if rec := submit("-37.81, 144.96"); rec.Code != http.StatusSeeOther {
		t.Fatalf("coordinate request: %d %s", rec.Code, rec.Body.String())
	}
	s.resolver.search = []geofabrik.Candidate{candidate("us/georgia", "Georgia", true), candidate("europe/georgia", "Georgia", true)}
	rec := submit("Georgia")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "us/georgia") || !strings.Contains(rec.Body.String(), "100.0 MiB") {
		t.Fatalf("ambiguous: %d %s", rec.Code, rec.Body.String())
	}
	// Missing CSRF is refused.
	req := httptest.NewRequest(http.MethodPost, "/ui/requests", strings.NewReader("place=Victoria"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if rec := s.do(t, req); rec.Code != http.StatusForbidden {
		t.Fatalf("no csrf: %d", rec.Code)
	}
}

func TestUIHidesRequestFormWhenOff(t *testing.T) {
	s := newTestServer(t, nil)
	if body := s.do(t, httptest.NewRequest(http.MethodGet, "/", nil)).Body.String(); strings.Contains(body, "/ui/requests") {
		t.Fatal("form visible while the feature is off")
	}
}
