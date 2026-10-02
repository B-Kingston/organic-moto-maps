package geofabrik

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type feat struct {
	id, parent, name string
	iso              []string
	iso2             []string
	box              [4]float64 // minLon, minLat, maxLon, maxLat
	hole             *[4]float64
}

func ring(b [4]float64) [][]float64 {
	return [][]float64{{b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}, {b[0], b[3]}, {b[0], b[1]}}
}

var fixtureFeatures = []feat{
	{id: "australia-oceania", name: "Australia and Oceania", box: [4]float64{110, -50, 180, -5}},
	{id: "australia", parent: "australia-oceania", name: "Australia", iso: []string{"AU"}, box: [4]float64{112, -44, 154, -10}},
	{id: "tasmania", parent: "australia", name: "Tasmania", iso2: []string{"AU-TAS"}, box: [4]float64{144, -44, 149, -40}},
	{id: "queensland", parent: "australia", name: "Queensland", iso2: []string{"AU-QLD"}, box: [4]float64{138, -29, 154, -10}},
	{id: "brisbane", parent: "queensland", name: "Brisbane", box: [4]float64{152, -28, 153.5, -27}},
	{id: "europe", name: "Europe", box: [4]float64{-30, 30, 60, 80}},
	{id: "monaco", parent: "europe", name: "Monaco", iso: []string{"MC"}, box: [4]float64{7.4, 43.7, 7.5, 43.8}},
	{id: "ile-de-france", parent: "europe", name: "Île-de-France", iso2: []string{"FR-IDF"}, box: [4]float64{1.5, 48, 3.5, 49.5}},
	{id: "georgia", parent: "europe", name: "Georgia", iso: []string{"GE"}, box: [4]float64{40, 41, 46, 44}},
	{id: "north-america", name: "North America", box: [4]float64{-170, 15, -50, 80}},
	{id: "us/georgia", parent: "north-america", name: "Georgia", iso2: []string{"US-GA"}, box: [4]float64{-85, 30, -81, 35}},
	{id: "holey", parent: "north-america", name: "Holey", box: [4]float64{-120, 40, -110, 50}, hole: &[4]float64{-116, 44, -114, 46}},
}

type fake struct {
	t       *testing.T
	srv     *httptest.Server
	mu      sync.Mutex
	hits    map[string]int
	idxFail bool
	// path -> Location for latest HEADs; missing = default dated redirect
	loc   map[string]string
	sizes map[string]int64  // dated path -> Content-Length (absent = 404)
	md5   map[string]string // dated path -> body of .md5 (absent = 404)
}

func newFake(t *testing.T) *fake {
	f := &fake{t: t, hits: map[string]int{}, loc: map[string]string{}, sizes: map[string]int64{}, md5: map[string]string{}}
	f.srv = httptest.NewUnstartedServer(http.HandlerFunc(f.handle))
	f.srv.Config.ErrorLog = log.New(io.Discard, "", 0)
	f.srv.StartTLS()
	t.Cleanup(f.srv.Close)
	sizes := map[string]int64{"tasmania": 1000, "queensland": 10000, "brisbane": 100, "monaco": 50,
		"ile-de-france": 90, "georgia": 60, "us/georgia": 70, "holey": 80}
	for id, n := range sizes {
		f.sizes["/"+id+"-261001.osm.pbf"] = n
		f.md5["/"+id+"-261001.osm.pbf.md5"] = fmt.Sprintf("%032x  %s-261001.osm.pbf\n", n, id[strings.LastIndex(id, "/")+1:])
	}
	return f
}

func (f *fake) handle(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := r.Method + " " + r.URL.Path
	f.hits[key]++
	p := r.URL.Path
	switch {
	case p == "/index-v1.json":
		if f.idxFail {
			http.Error(w, "down", http.StatusInternalServerError)
			return
		}
		w.Write(f.indexJSON())
	case strings.HasSuffix(p, "-latest.osm.pbf"):
		loc, ok := f.loc[p]
		if !ok {
			loc = strings.TrimSuffix(p, "-latest.osm.pbf") + "-261001.osm.pbf"
		}
		if loc == "-" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Location", loc)
		w.WriteHeader(http.StatusTemporaryRedirect)
	case strings.HasSuffix(p, ".md5"):
		body, ok := f.md5[p]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(body))
	default:
		n, ok := f.sizes[p]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Length", fmt.Sprint(n))
	}
}

func (f *fake) count(method, path string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.hits[method+" "+path]
}

func (f *fake) indexJSON() []byte {
	var feats []map[string]any
	for _, ft := range fixtureFeatures {
		props := map[string]any{"id": ft.id, "name": ft.name, "urls": map[string]string{"pbf": f.srv.URL + "/" + ft.id + "-latest.osm.pbf"}}
		if ft.parent != "" {
			props["parent"] = ft.parent
		}
		if ft.iso != nil {
			props["iso3166-1:alpha2"] = ft.iso
		}
		if ft.iso2 != nil {
			props["iso3166-2"] = ft.iso2
		}
		props["unknown-future-field"] = map[string]any{"x": 1}
		rings := [][][]float64{ring(ft.box)}
		if ft.hole != nil {
			rings = append(rings, ring(*ft.hole))
		}
		feats = append(feats, map[string]any{
			"type": "Feature", "properties": props,
			"geometry": map[string]any{"type": "MultiPolygon", "coordinates": [][][][]float64{rings}},
		})
	}
	// Entries the parser must skip.
	feats = append(feats,
		map[string]any{"properties": map[string]any{"id": "nourl", "name": "No URL"}},
		map[string]any{"properties": map[string]any{"id": "evil", "name": "Evil", "parent": "europe",
			"urls": map[string]string{"pbf": "https://evil.example.com/evil-latest.osm.pbf"}}},
		map[string]any{"properties": map[string]any{"id": "plain", "name": "Plain", "parent": "europe",
			"urls": map[string]string{"pbf": "http://" + f.host() + "/plain-latest.osm.pbf"}}},
	)
	b, err := json.Marshal(map[string]any{"type": "FeatureCollection", "features": feats})
	if err != nil {
		f.t.Fatal(err)
	}
	return b
}

func (f *fake) host() string {
	u, _ := url.Parse(f.srv.URL)
	return u.Host
}

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) Add(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

func (f *fake) service(clk *clock, mod func(*Options)) *Service {
	o := Options{
		IndexURL:        f.srv.URL + "/index-v1.json",
		AllowedHost:     f.host(),
		MaxExtractBytes: 5000,
		HTTPClient:      f.srv.Client(),
		Now:             clk.Now,
		Logf:            f.t.Logf,
	}
	if mod != nil {
		mod(&o)
	}
	return New(o)
}

func newClock() *clock { return &clock{t: time.Date(2026, 10, 1, 12, 0, 0, 0, time.UTC)} }

func ids(cs []Candidate) []string {
	var out []string
	for _, c := range cs {
		out = append(out, c.ID)
	}
	return out
}

func equal(a, b []string) bool { return strings.Join(a, ",") == strings.Join(b, ",") }

func TestSearchRankingAndDiacritics(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), nil)
	ctx := context.Background()

	got, err := s.Search(ctx, "ile de france", 0)
	if err != nil || len(got) != 1 || got[0].ID != "ile-de-france" {
		t.Fatalf("ile de france: %v %v", ids(got), err)
	}
	got, _ = s.Search(ctx, "ÎLE", 0)
	if len(got) != 1 || got[0].ID != "ile-de-france" {
		t.Fatalf("ÎLE: %v", ids(got))
	}

	// "georgia": both exact at equal depth; ties fall back to name, then id.
	got, _ = s.Search(ctx, "Georgia", 0)
	if !equal(ids(got), []string{"georgia", "us/georgia"}) {
		t.Fatalf("georgia order: %v", ids(got))
	}

	// Both prefix matches; the deeper path (country) precedes the continent.
	got, _ = s.Search(ctx, "austral", 0)
	if len(got) < 2 || got[0].ID != "australia" || got[1].ID != "australia-oceania" {
		t.Fatalf("austral: %v", ids(got))
	}
	got, _ = s.Search(ctx, "land", 0)
	if len(got) != 1 || got[0].ID != "queensland" {
		t.Fatalf("substring: %v", ids(got))
	}
	if got, _ := s.Search(ctx, "   ", 0); len(got) != 0 {
		t.Fatalf("blank query: %v", ids(got))
	}
	if got, _ := s.Search(ctx, "a", 2); len(got) != 2 {
		t.Fatalf("limit: %v", ids(got))
	}
}

func TestSearchISOAndPaths(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), nil)
	got, err := s.Search(context.Background(), "AU-TAS", 0)
	if err != nil || len(got) != 1 || got[0].ID != "tasmania" {
		t.Fatalf("AU-TAS: %v %v", ids(got), err)
	}
	c := got[0]
	if !equal(c.Path, []string{"Australia and Oceania", "Australia"}) {
		t.Fatalf("path: %v", c.Path)
	}
	if c.RegionID != "tasmania" || c.Bytes != 1000 || !c.Eligible {
		t.Fatalf("candidate: %+v", c)
	}
	got, _ = s.Search(context.Background(), "MC", 0)
	if len(got) == 0 || got[0].ID != "monaco" {
		t.Fatalf("MC: %v", ids(got))
	}
	got, _ = s.Search(context.Background(), "queensland", 0)
	q := got[0]
	if q.Eligible || !strings.Contains(q.Reason, "exceeds") || q.Bytes != 10000 {
		t.Fatalf("queensland: %+v", q)
	}
	if len(q.Children) != 1 || q.Children[0].ID != "brisbane" {
		t.Fatalf("children: %+v", q.Children)
	}
	got, _ = s.Search(context.Background(), "europe", 0)
	if got[0].ID != "europe" || got[0].Eligible || got[0].Reason != reasonContinent {
		t.Fatalf("continent: %+v", got[0])
	}
}

func TestIndexSkipsUntrustedFeatures(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), nil)
	for _, q := range []string{"evil", "plain", "No URL"} {
		got, err := s.Search(context.Background(), q, 0)
		if err != nil || len(got) != 0 {
			t.Errorf("%q should be skipped, got %v %v", q, ids(got), err)
		}
	}
}

func TestLocate(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), nil)
	ctx := context.Background()

	got, err := s.Locate(ctx, -42, 146.5, 0)
	if err != nil || !equal(ids(got), []string{"tasmania", "australia", "australia-oceania"}) {
		t.Fatalf("tasmania point: %v %v", ids(got), err)
	}
	got, _ = s.Locate(ctx, -27.5, 153, 2)
	if !equal(ids(got), []string{"brisbane", "queensland"}) {
		t.Fatalf("brisbane limit 2: %v", ids(got))
	}
	// Georgia the country vs the state, by position.
	if got, _ := s.Locate(ctx, 42, 43, 0); len(got) == 0 || got[0].ID != "georgia" {
		t.Fatalf("country georgia: %v", ids(got))
	}
	if got, _ := s.Locate(ctx, 33, -83, 0); len(got) == 0 || got[0].ID != "us/georgia" {
		t.Fatalf("state georgia: %v", ids(got))
	}
	if got, _ := s.Locate(ctx, 42, -112, 0); !equal(ids(got), []string{"holey", "north-america"}) {
		t.Fatalf("inside holey: %v", ids(got))
	}
	if got, _ := s.Locate(ctx, 45, -115, 0); !equal(ids(got), []string{"north-america"}) {
		t.Fatalf("inside the hole must not match: %v", ids(got))
	}
	if got, _ := s.Locate(ctx, 0, 0, 0); len(got) != 0 {
		t.Fatalf("open ocean: %v", ids(got))
	}
	for _, c := range [][2]float64{{91, 0}, {-91, 0}, {0, 181}, {0, -181}} {
		if _, err := s.Locate(ctx, c[0], c[1], 0); err == nil || errors.Is(err, ErrUpstream) {
			t.Errorf("Locate(%v) error = %v", c, err)
		}
	}
}

func TestResolveHappyPath(t *testing.T) {
	f := newFake(t)
	clk := newClock()
	s := f.service(clk, nil)
	r, err := s.Resolve(context.Background(), "tasmania")
	if err != nil {
		t.Fatal(err)
	}
	if r.URL != f.srv.URL+"/tasmania-261001.osm.pbf" || r.Date != "261001" || r.Bytes != 1000 {
		t.Fatalf("resolved: %+v", r)
	}
	if r.MD5 != fmt.Sprintf("%032x", 1000) {
		t.Fatalf("md5 %q", r.MD5)
	}
	if r.Extract.ID != "tasmania" || r.Extract.Parent != "australia" {
		t.Fatalf("extract: %+v", r.Extract)
	}
	if s.MaxExtractBytes() != 5000 {
		t.Fatal("max bytes")
	}
	// The slash id keeps its path in the URL.
	r, err = s.Resolve(context.Background(), "us/georgia")
	if err != nil || r.URL != f.srv.URL+"/us/georgia-261001.osm.pbf" || r.MD5 == "" {
		t.Fatalf("us/georgia: %+v %v", r, err)
	}
}

func TestResolveRejectsForeignRedirect(t *testing.T) {
	f := newFake(t)
	f.loc["/tasmania-latest.osm.pbf"] = "https://evil.example.com/tasmania-261001.osm.pbf"
	f.sizes["/tasmania-261001.osm.pbf"] = 1000
	s := f.service(newClock(), nil)
	r, err := s.Resolve(context.Background(), "tasmania")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(r.URL, f.srv.URL+"/") {
		t.Fatalf("followed foreign redirect: %s", r.URL)
	}
	// Plain http on the same host is also refused.
	f.loc["/monaco-latest.osm.pbf"] = "http://" + f.host() + "/monaco-261001.osm.pbf"
	if r, err := s.Resolve(context.Background(), "monaco"); err != nil || !strings.HasPrefix(r.URL, "https://") {
		t.Fatalf("http location: %+v %v", r, err)
	}
}

func TestResolveFallsBackToDatedProbing(t *testing.T) {
	f := newFake(t)
	for _, loc := range []string{"/tasmania-latest.osm.pbf", "/other.osm.pbf", "/tasmania-1.osm.pbf", "/tasmania-261001.osm.pbf.bak", "/x/tasmania-261001.osm.pbf"} {
		f.loc["/tasmania-latest.osm.pbf"] = loc
		delete(f.sizes, "/tasmania-261001.osm.pbf")
		f.sizes["/tasmania-260929.osm.pbf"] = 1234
		s := f.service(newClock(), nil)
		r, err := s.Resolve(context.Background(), "tasmania")
		if err != nil {
			t.Fatalf("location %q: %v", loc, err)
		}
		if r.URL != f.srv.URL+"/tasmania-260929.osm.pbf" || r.Date != "260929" || r.Bytes != 1234 {
			t.Fatalf("location %q: %+v", loc, r)
		}
		if r.MD5 != "" {
			t.Fatalf("no md5 published, got %q", r.MD5)
		}
	}
}

func TestResolveProbingGivesUpAfterAWeek(t *testing.T) {
	f := newFake(t)
	f.loc["/tasmania-latest.osm.pbf"] = "-"
	delete(f.sizes, "/tasmania-261001.osm.pbf")
	f.sizes["/tasmania-260920.osm.pbf"] = 5 // 11 days old
	s := f.service(newClock(), nil)
	_, err := s.Resolve(context.Background(), "tasmania")
	if !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v", err)
	}
	if n := f.count("HEAD", "/tasmania-260924.osm.pbf"); n == 0 {
		t.Fatal("did not probe 7 days back")
	}
	if n := f.count("HEAD", "/tasmania-260923.osm.pbf"); n != 0 {
		t.Fatal("probed beyond 7 days")
	}
	// Candidates report an unknown size rather than failing.
	got, err := s.Search(context.Background(), "tasmania", 0)
	if err != nil || got[0].Eligible || got[0].Reason != "size unavailable; try again" {
		t.Fatalf("candidate: %+v %v", got, err)
	}
}

func TestResolveTooLargeListsChildren(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), func(o *Options) { o.MaxExtractBytes = 768 << 20 })
	f.sizes["/queensland-261001.osm.pbf"] = 1_300_000_000
	_, err := s.Resolve(context.Background(), "queensland")
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("err = %v", err)
	}
	for _, want := range []string{"Queensland is 1.2 GiB", "768 MiB", "Pick a smaller region", "Brisbane (brisbane)"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("message %q missing %q", err, want)
		}
	}
	// No children: no suggestion.
	s = f.service(newClock(), func(o *Options) { o.MaxExtractBytes = 10 })
	_, err = s.Resolve(context.Background(), "monaco")
	if !errors.Is(err, ErrTooLarge) || strings.Contains(err.Error(), "Pick") {
		t.Fatalf("leaf: %v", err)
	}
}

func TestResolveContinentUnknownAndBoundary(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), nil)
	if _, err := s.Resolve(context.Background(), "europe"); !errors.Is(err, ErrNotBuildable) {
		t.Fatalf("continent: %v", err)
	}
	if _, err := s.Resolve(context.Background(), "atlantis"); !errors.Is(err, ErrUnknownExtract) {
		t.Fatalf("unknown: %v", err)
	}
	f.sizes["/monaco-261001.osm.pbf"] = 5000
	if _, err := s.Resolve(context.Background(), "monaco"); err != nil {
		t.Fatalf("exactly at the limit is allowed: %v", err)
	}
}

func TestResolveMalformedMD5(t *testing.T) {
	f := newFake(t)
	good := strings.Repeat("a", 32)
	for name, body := range map[string]string{
		"short":      "abc  tasmania-261001.osm.pbf\n",
		"nonhex":     strings.Repeat("z", 32) + "  tasmania-261001.osm.pbf\n",
		"wrong file": good + "  other-261001.osm.pbf\n",
		"empty":      "",
		"extra":      good + "  tasmania-261001.osm.pbf extra\n",
	} {
		f.md5["/tasmania-261001.osm.pbf.md5"] = body
		s := f.service(newClock(), nil)
		r, err := s.Resolve(context.Background(), "tasmania")
		if err != nil || r.MD5 != "" {
			t.Errorf("%s: md5 %q err %v", name, r.MD5, err)
		}
	}
	f.md5["/tasmania-261001.osm.pbf.md5"] = strings.ToUpper(good) + "  tasmania-261001.osm.pbf\n"
	r, _ := f.service(newClock(), nil).Resolve(context.Background(), "tasmania")
	if r.MD5 != good {
		t.Fatalf("uppercase md5 should be lowercased, got %q", r.MD5)
	}
}

func TestIndexCacheFileReusedWhileFresh(t *testing.T) {
	f := newFake(t)
	clk := newClock()
	cache := filepath.Join(t.TempDir(), "sub", "index.json")
	mod := func(o *Options) { o.CachePath = cache }
	if _, err := f.service(clk, mod).Search(context.Background(), "tasmania", 0); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(cache); err != nil {
		t.Fatal(err)
	}
	clk.Add(time.Hour)
	if got, err := f.service(clk, mod).Search(context.Background(), "tasmania", 0); err != nil || len(got) != 1 {
		t.Fatalf("%v %v", got, err)
	}
	if n := f.count("GET", "/index-v1.json"); n != 1 {
		t.Fatalf("index fetched %d times, want 1", n)
	}
	clk.Add(48 * time.Hour)
	if _, err := f.service(clk, mod).Search(context.Background(), "tasmania", 0); err != nil {
		t.Fatal(err)
	}
	if n := f.count("GET", "/index-v1.json"); n != 2 {
		t.Fatalf("stale cache should refetch, hits = %d", n)
	}
}

func TestStaleIndexUsedWhenRefetchFails(t *testing.T) {
	f := newFake(t)
	clk := newClock()
	cache := filepath.Join(t.TempDir(), "index.json")
	mod := func(o *Options) { o.CachePath = cache }
	s := f.service(clk, mod)
	if _, err := s.Search(context.Background(), "tasmania", 0); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	f.idxFail = true
	f.mu.Unlock()
	clk.Add(48 * time.Hour)
	if got, err := s.Search(context.Background(), "tasmania", 0); err != nil || len(got) != 1 {
		t.Fatalf("in-memory stale: %v %v", got, err)
	}
	// A fresh process with only the stale file also survives.
	if got, err := f.service(clk, mod).Search(context.Background(), "tasmania", 0); err != nil || len(got) != 1 {
		t.Fatalf("file stale: %v %v", got, err)
	}
	// No cache at all: upstream error.
	_, err := f.service(clk, nil).Search(context.Background(), "tasmania", 0)
	if !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v", err)
	}
}

func TestIndexLoadIsShared(t *testing.T) {
	f := newFake(t)
	s := f.service(newClock(), nil)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.Locate(context.Background(), -42, 146.5, 1); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if n := f.count("GET", "/index-v1.json"); n != 1 {
		t.Fatalf("index fetched %d times", n)
	}
}

func TestSizeCacheAvoidsRepeatHEADs(t *testing.T) {
	f := newFake(t)
	clk := newClock()
	s := f.service(clk, nil)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if _, err := s.Resolve(ctx, "tasmania"); err != nil {
			t.Fatal(err)
		}
		if _, err := s.Search(ctx, "tasmania", 0); err != nil {
			t.Fatal(err)
		}
	}
	if n := f.count("HEAD", "/tasmania-latest.osm.pbf"); n != 1 {
		t.Fatalf("latest HEADs = %d", n)
	}
	if n := f.count("HEAD", "/tasmania-261001.osm.pbf"); n != 1 {
		t.Fatalf("dated HEADs = %d", n)
	}
	if n := f.count("GET", "/tasmania-261001.osm.pbf.md5"); n != 1 {
		t.Fatalf("md5 GETs = %d", n)
	}
	clk.Add(7 * time.Hour)
	if _, err := s.Resolve(ctx, "tasmania"); err != nil {
		t.Fatal(err)
	}
	if n := f.count("HEAD", "/tasmania-latest.osm.pbf"); n != 2 {
		t.Fatalf("after SizeTTL latest HEADs = %d", n)
	}
}

func TestIndexMustBeHTTPS(t *testing.T) {
	s := New(Options{IndexURL: "http://download.geofabrik.de/index-v1.json"})
	if _, err := s.Search(context.Background(), "x", 0); !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v", err)
	}
}

func TestNewMakesNoNetworkCallsAndAppliesDefaults(t *testing.T) {
	s := New(Options{})
	if s.MaxExtractBytes() != 768<<20 {
		t.Fatalf("default max = %d", s.MaxExtractBytes())
	}
	o := s.opts
	if o.IndexURL != "https://download.geofabrik.de/index-v1.json" || o.AllowedHost != "download.geofabrik.de" ||
		o.IndexTTL != 24*time.Hour || o.SizeTTL != 6*time.Hour {
		t.Fatalf("defaults: %+v", o)
	}
}

func TestRegionID(t *testing.T) {
	for in, want := range map[string]string{"us/georgia": "us-georgia", "Tasmania": "tasmania", "georgia": "georgia"} {
		if got := RegionID(in); got != want {
			t.Errorf("RegionID(%q) = %q, want %q", in, got, want)
		}
	}
}
