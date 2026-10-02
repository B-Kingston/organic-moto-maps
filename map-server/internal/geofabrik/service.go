package geofabrik

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	defaultIndexURL    = "https://download.geofabrik.de/index-v1.json"
	defaultHost        = "download.geofabrik.de"
	maxIndexBytes      = 64 << 20
	maxChecksumBytes   = 4 << 10
	datedProbeDays     = 7
	candidateSizeConc  = 4
	defaultMaxExtract  = 768 << 20
	defaultResultLimit = 8
	maxResultLimit     = 20
)

// Options configures a Service. Zero values select the defaults noted.
type Options struct {
	IndexURL        string        // default "https://download.geofabrik.de/index-v1.json"
	AllowedHost     string        // default "download.geofabrik.de"
	CachePath       string        // on-disk index cache, "" = memory only
	IndexTTL        time.Duration // default 24h
	SizeTTL         time.Duration // default 6h
	MaxExtractBytes int64         // default 768 MiB
	HTTPClient      *http.Client  // default 30s timeout
	Now             func() time.Time
	Logf            func(string, ...any)
}

// Service resolves places to Geofabrik extracts. It makes no network calls
// until a method needs the index or an extract's size.
type Service struct {
	opts   Options
	probe  *http.Client // never follows redirects
	guard  *http.Client // follows only https redirects to the allowed host
	idxMu  sync.Mutex
	idx    *index
	idxAt  time.Time
	pinMu  sync.Mutex
	pinned map[string]pinEntry
}

type pinEntry struct {
	url, date, md5 string
	bytes          int64
	haveMD5        bool
	at             time.Time
}

// New returns a Service with defaults filled in.
func New(opts Options) *Service {
	if opts.IndexURL == "" {
		opts.IndexURL = defaultIndexURL
	}
	if opts.AllowedHost == "" {
		opts.AllowedHost = defaultHost
	}
	if opts.IndexTTL <= 0 {
		opts.IndexTTL = 24 * time.Hour
	}
	if opts.SizeTTL <= 0 {
		opts.SizeTTL = 6 * time.Hour
	}
	if opts.MaxExtractBytes <= 0 {
		opts.MaxExtractBytes = defaultMaxExtract
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.Logf == nil {
		opts.Logf = func(string, ...any) {}
	}
	base := opts.HTTPClient
	if base == nil {
		base = &http.Client{Timeout: 30 * time.Second}
	}
	s := &Service{opts: opts, pinned: map[string]pinEntry{}}
	probe := *base
	probe.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	guard := *base
	guard.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return errors.New("too many redirects")
		}
		if req.URL.Scheme != "https" || req.URL.Host != s.opts.AllowedHost {
			return fmt.Errorf("redirect to %s is not allowed", req.URL.Host)
		}
		return nil
	}
	s.probe, s.guard = &probe, &guard
	return s
}

// MaxExtractBytes is the size limit applied to dated extracts.
func (s *Service) MaxExtractBytes() int64 { return s.opts.MaxExtractBytes }

type resolveError struct {
	kind error
	msg  string
}

func (e *resolveError) Error() string { return e.msg }
func (e *resolveError) Unwrap() error { return e.kind }

func upstream(format string, args ...any) error {
	return &resolveError{ErrUpstream, fmt.Sprintf(format, args...)}
}

func clampLimit(n int) int {
	if n <= 0 {
		return defaultResultLimit
	}
	return min(n, maxResultLimit)
}

// Search matches id, name and ISO codes ignoring case and diacritics: exact
// matches first, then prefix, then substring; ties go to the deeper path, then
// the name.
func (s *Service) Search(ctx context.Context, query string, limit int) ([]Candidate, error) {
	q := fold(query)
	if q == "" {
		return nil, nil
	}
	idx, err := s.loadIndex(ctx)
	if err != nil {
		return nil, err
	}
	type hit struct {
		e    *entry
		rank int
	}
	var hits []hit
	for _, e := range idx.list {
		if r := e.matchRank(q); r >= 0 {
			hits = append(hits, hit{e, r})
		}
	}
	sort.SliceStable(hits, func(i, j int) bool {
		a, b := hits[i], hits[j]
		if a.rank != b.rank {
			return a.rank < b.rank
		}
		if len(a.e.Path) != len(b.e.Path) {
			return len(a.e.Path) > len(b.e.Path)
		}
		if a.e.Name != b.e.Name {
			return a.e.Name < b.e.Name
		}
		return a.e.ID < b.e.ID
	})
	hits = hits[:min(len(hits), clampLimit(limit))]
	es := make([]*entry, len(hits))
	for i, h := range hits {
		es[i] = h.e
	}
	return s.candidates(ctx, es)
}

// Locate returns extracts containing the point, smallest area first.
func (s *Service) Locate(ctx context.Context, lat, lon float64, limit int) ([]Candidate, error) {
	if math.IsNaN(lat) || math.IsNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180 {
		return nil, fmt.Errorf("coordinates out of range: lat %v, lon %v", lat, lon)
	}
	idx, err := s.loadIndex(ctx)
	if err != nil {
		return nil, err
	}
	var hits []*entry
	for _, e := range idx.list {
		if e.contains(lat, lon) {
			hits = append(hits, e)
		}
	}
	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].area != hits[j].area {
			return hits[i].area < hits[j].area
		}
		return hits[i].ID < hits[j].ID
	})
	return s.candidates(ctx, hits[:min(len(hits), clampLimit(limit))])
}

func (s *Service) candidates(ctx context.Context, es []*entry) ([]Candidate, error) {
	out := make([]Candidate, len(es))
	sem := make(chan struct{}, candidateSizeConc)
	var wg sync.WaitGroup
	for i, e := range es {
		c := Candidate{Extract: e.Extract, RegionID: RegionID(e.ID)}
		out[i] = c
		if e.Parent == "" {
			out[i].Reason = reasonContinent
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			p, err := s.pin(ctx, e, false)
			switch {
			case err != nil:
				s.opts.Logf("geofabrik: size of %s: %v", e.ID, err)
				out[i].Reason = "size unavailable; try again"
			case p.bytes > s.opts.MaxExtractBytes:
				out[i].Bytes = p.bytes
				out[i].Reason = fmt.Sprintf("%s exceeds the %s limit", formatSize(p.bytes), formatSize(s.opts.MaxExtractBytes))
			default:
				out[i].Bytes = p.bytes
				out[i].Eligible = true
			}
		}()
	}
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

const reasonContinent = "continents are too large; pick a country or state"

// Resolve pins an extract to its dated file and enforces eligibility.
func (s *Service) Resolve(ctx context.Context, extractID string) (Resolved, error) {
	idx, err := s.loadIndex(ctx)
	if err != nil {
		return Resolved{}, err
	}
	e := idx.byID[extractID]
	if e == nil {
		return Resolved{}, &resolveError{ErrUnknownExtract, fmt.Sprintf("unknown extract %q", extractID)}
	}
	if e.Parent == "" {
		return Resolved{}, &resolveError{ErrNotBuildable, fmt.Sprintf("%s: %s", e.Name, reasonContinent)}
	}
	p, err := s.pin(ctx, e, true)
	if err != nil {
		return Resolved{}, err
	}
	if p.bytes > s.opts.MaxExtractBytes {
		msg := fmt.Sprintf("%s is %s; this server builds extracts up to %s.",
			e.Name, formatSize(p.bytes), formatSize(s.opts.MaxExtractBytes))
		if len(e.Children) > 0 {
			var parts []string
			for _, c := range e.Children {
				parts = append(parts, fmt.Sprintf("%s (%s)", c.Name, c.ID))
			}
			msg += " Pick a smaller region: " + strings.Join(parts, ", ")
		}
		return Resolved{}, &resolveError{ErrTooLarge, msg}
	}
	return Resolved{Extract: e.Extract, URL: p.url, Date: p.date, Bytes: p.bytes, MD5: p.md5}, nil
}

func formatSize(n int64) string {
	const gib = 1 << 30
	if n >= gib {
		return fmt.Sprintf("%.1f GiB", float64(n)/gib)
	}
	return fmt.Sprintf("%.0f MiB", float64(n)/(1<<20))
}

// pin resolves the dated URL and size, caching per extract for SizeTTL.
func (s *Service) pin(ctx context.Context, e *entry, wantMD5 bool) (pinEntry, error) {
	now := s.opts.Now()
	s.pinMu.Lock()
	p, ok := s.pinned[e.ID]
	s.pinMu.Unlock()
	if !ok || now.Sub(p.at) >= s.opts.SizeTTL || now.Before(p.at) {
		datedURL, size, err := s.resolveDated(ctx, e.LatestURL)
		if err != nil {
			return pinEntry{}, err
		}
		p = pinEntry{url: datedURL, bytes: size, at: now}
		p.date = datedURL[len(datedURL)-len("261001.osm.pbf") : len(datedURL)-len(".osm.pbf")]
	}
	if wantMD5 && !p.haveMD5 {
		p.md5, p.haveMD5 = s.fetchMD5(ctx, p.url), true
	}
	s.pinMu.Lock()
	s.pinned[e.ID] = p
	s.pinMu.Unlock()
	return p, nil
}

func (s *Service) head(ctx context.Context, rawURL string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, rawURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := s.probe.Do(req)
	if err != nil {
		return nil, err
	}
	resp.Body.Close()
	return resp, nil
}

func (s *Service) resolveDated(ctx context.Context, latestURL string) (string, int64, error) {
	var lastErr error
	if resp, err := s.head(ctx, latestURL); err != nil {
		lastErr = err
	} else if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		if dated, err := s.datedFromLocation(latestURL, resp.Header.Get("Location")); err != nil {
			s.opts.Logf("geofabrik: %s: %v; probing dated names", latestURL, err)
			lastErr = err
		} else if size, err := s.datedSize(ctx, dated); err != nil {
			lastErr = err
		} else {
			return dated, size, nil
		}
	} else {
		lastErr = fmt.Errorf("HEAD %s: status %d, expected a redirect", latestURL, resp.StatusCode)
	}
	if ctx.Err() != nil {
		return "", 0, upstream("geofabrik request cancelled: %v", ctx.Err())
	}
	base := strings.TrimSuffix(latestURL, "-latest.osm.pbf")
	now := s.opts.Now().UTC()
	for i := 0; i <= datedProbeDays; i++ {
		dated := fmt.Sprintf("%s-%s.osm.pbf", base, now.AddDate(0, 0, -i).Format("060102"))
		size, err := s.datedSize(ctx, dated)
		if err == nil {
			return dated, size, nil
		}
		if ctx.Err() != nil {
			break
		}
		lastErr = err
	}
	return "", 0, upstream("could not find a current Geofabrik extract for %s: %v", path.Base(latestURL), lastErr)
}

func (s *Service) datedFromLocation(latestURL, loc string) (string, error) {
	if loc == "" {
		return "", errors.New("redirect without Location")
	}
	base, err := url.Parse(latestURL)
	if err != nil {
		return "", err
	}
	u, err := base.Parse(loc)
	if err != nil {
		return "", fmt.Errorf("bad Location %q", loc)
	}
	if u.Scheme != "https" || u.Host != s.opts.AllowedHost || u.RawQuery != "" || u.Fragment != "" {
		return "", fmt.Errorf("Location %q is not an https URL on %s", loc, s.opts.AllowedHost)
	}
	re := regexp.MustCompile("^" + regexp.QuoteMeta(strings.TrimSuffix(base.Path, "-latest.osm.pbf")) + `-\d{6}\.osm\.pbf$`)
	if !re.MatchString(u.Path) {
		return "", fmt.Errorf("Location %q is not a dated name for %s", loc, base.Path)
	}
	return u.String(), nil
}

func (s *Service) datedSize(ctx context.Context, dated string) (int64, error) {
	resp, err := s.head(ctx, dated)
	if err != nil {
		return 0, err
	}
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("HEAD %s: status %d", dated, resp.StatusCode)
	}
	if resp.ContentLength <= 0 {
		return 0, fmt.Errorf("HEAD %s: no Content-Length", dated)
	}
	return resp.ContentLength, nil
}

var md5Re = regexp.MustCompile(`^[0-9a-fA-F]{32}$`)

func (s *Service) fetchMD5(ctx context.Context, dated string) string {
	body, err := s.get(ctx, dated+".md5", maxChecksumBytes)
	if err != nil {
		s.opts.Logf("geofabrik: md5 for %s: %v", dated, err)
		return ""
	}
	f := strings.Fields(string(body))
	if len(f) != 2 || !md5Re.MatchString(f[0]) || strings.TrimPrefix(f[1], "*") != path.Base(dated) {
		s.opts.Logf("geofabrik: ignoring malformed md5 for %s", dated)
		return ""
	}
	return strings.ToLower(f[0])
}

func (s *Service) get(ctx context.Context, rawURL string, limit int64) ([]byte, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" {
		return nil, fmt.Errorf("refusing non-https URL %q", rawURL)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := s.guard.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s: status %d", rawURL, resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, fmt.Errorf("GET %s: body exceeds %d bytes", rawURL, limit)
	}
	return body, nil
}

// loadIndex returns the cached index while fresh and otherwise refetches it,
// falling back to a stale copy when Geofabrik cannot be reached. The mutex is
// held across the fetch so concurrent callers share one load.
func (s *Service) loadIndex(ctx context.Context) (*index, error) {
	s.idxMu.Lock()
	defer s.idxMu.Unlock()
	now := s.opts.Now()
	fresh := func() bool { return s.idx != nil && now.Sub(s.idxAt) < s.opts.IndexTTL && !now.Before(s.idxAt) }
	if fresh() {
		return s.idx, nil
	}
	if s.idx == nil && s.opts.CachePath != "" {
		if st, err := os.Stat(s.opts.CachePath); err == nil {
			if data, err := os.ReadFile(s.opts.CachePath); err == nil {
				if idx, err := parseIndex(data, s.opts.AllowedHost); err == nil && len(idx.list) > 0 {
					s.idx, s.idxAt = idx, st.ModTime()
				} else {
					s.opts.Logf("geofabrik: ignoring unusable index cache %s", s.opts.CachePath)
				}
			}
		}
		if fresh() {
			return s.idx, nil
		}
	}
	data, err := s.fetchIndex(ctx)
	if err == nil {
		var idx *index
		if idx, err = parseIndex(data, s.opts.AllowedHost); err == nil && len(idx.list) == 0 {
			err = errors.New("index has no usable extracts")
		}
		if err == nil {
			s.idx, s.idxAt = idx, now
			s.writeCache(data, now)
			return idx, nil
		}
	}
	if s.idx != nil {
		s.opts.Logf("geofabrik: index refresh failed, using stale copy: %v", err)
		return s.idx, nil
	}
	return nil, upstream("could not load the Geofabrik index: %v", err)
}

func (s *Service) fetchIndex(ctx context.Context) ([]byte, error) {
	return s.get(ctx, s.opts.IndexURL, maxIndexBytes)
}

func (s *Service) writeCache(data []byte, now time.Time) {
	p := s.opts.CachePath
	if p == "" {
		return
	}
	err := os.MkdirAll(filepath.Dir(p), 0o755)
	if err == nil {
		tmp := p + ".tmp"
		if err = os.WriteFile(tmp, data, 0o644); err == nil {
			if err = os.Chtimes(tmp, now, now); err == nil {
				err = os.Rename(tmp, p)
			}
		}
	}
	if err != nil {
		s.opts.Logf("geofabrik: writing index cache: %v", err)
	}
}
