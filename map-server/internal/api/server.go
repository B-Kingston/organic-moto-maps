// Package api is the map server's HTTP surface: JSON API for clients (the
// Android app), the embedded HTMX UI for operators, and resumable artifact
// downloads.
package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"runtime/debug"
	"strings"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/jobs"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
	"github.com/organicmoto/curveMaps/map-server/internal/ratelimit"
	"github.com/organicmoto/curveMaps/map-server/internal/security"
	"github.com/organicmoto/curveMaps/map-server/internal/trustedproxy"
	"github.com/organicmoto/curveMaps/map-server/internal/ui"
)

// Limits configures per-class rate limits. Every rule is per client address.
type Limits struct {
	BuildBurst    int
	BuildPer      time.Duration
	PollBurst     int
	PollPer       time.Duration
	DownloadBurst int
	DownloadPer   time.Duration
	DefaultBurst  int
	DefaultPer    time.Duration

	SearchBurst int
	SearchPer   time.Duration

	MaxConcurrentDownloads int
	MaxConcurrentPerClient int
	MaxBodyBytes           int64
}

// DefaultLimits returns conservative defaults for a small public host.
func DefaultLimits() Limits {
	return Limits{
		BuildBurst:             3,
		BuildPer:               20 * time.Minute,
		PollBurst:              60,
		PollPer:                30 * time.Second,
		DownloadBurst:          10,
		DownloadPer:            2 * time.Minute,
		SearchBurst:            20,
		SearchPer:              time.Minute,
		DefaultBurst:           60,
		DefaultPer:             30 * time.Second,
		MaxConcurrentDownloads: 8,
		MaxConcurrentPerClient: 2,
		MaxBodyBytes:           64 << 10,
	}
}

// Config wires the server to the catalog, queue, and generator.
type Config struct {
	Version    string
	Catalog    *catalog.Catalog
	Jobs       *jobs.Manager
	Generator  pipeline.Builder
	Trusted    *trustedproxy.Set
	CSRF       *security.CSRF
	Limits     Limits
	Headless   bool
	AdminToken string
	// Extracts resolves place names and coordinates to Geofabrik extracts;
	// nil turns request-by-place off.
	Extracts ExtractResolver
	// Requested persists client-requested regions (required with Extracts).
	Requested *catalog.RequestedStore
	// RefreshAfter is how old a requested region's source may get before a
	// new request re-resolves it (default 30 days).
	RefreshAfter time.Duration
	Log          *slog.Logger
	Now          func() time.Time
}

// Server is the HTTP application.
type Server struct {
	cfg       Config
	mux       *http.ServeMux
	limiter   *ratelimit.Limiter
	downloads *ratelimit.Concurrency
	templates *ui.Templates
	handler   http.Handler
	sweepStop context.CancelFunc

	javaReady   bool
	javaMessage string
}

// New builds the server and its route table.
func New(cfg Config) (*Server, error) {
	if cfg.Catalog == nil || cfg.Jobs == nil || cfg.Generator == nil {
		return nil, fmt.Errorf("api: catalog, jobs manager, and generator are required")
	}
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Trusted == nil {
		cfg.Trusted, _ = trustedproxy.Parse(nil)
	}
	if cfg.CSRF == nil {
		return nil, fmt.Errorf("api: CSRF validator is required")
	}
	if cfg.Extracts != nil && cfg.Requested == nil {
		return nil, fmt.Errorf("api: a requested-regions store is required with an extract resolver")
	}
	if cfg.Requested != nil {
		cfg.Catalog.AttachRequested(cfg.Requested)
	}
	if cfg.Limits.SearchBurst <= 0 || cfg.Limits.SearchPer <= 0 {
		def := DefaultLimits()
		cfg.Limits.SearchBurst, cfg.Limits.SearchPer = def.SearchBurst, def.SearchPer
	}
	templates, err := ui.New()
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg:       cfg,
		mux:       http.NewServeMux(),
		limiter:   ratelimit.NewLimiter(20_000, 30*time.Minute),
		downloads: ratelimit.NewConcurrency(cfg.Limits.MaxConcurrentDownloads, cfg.Limits.MaxConcurrentPerClient, 30*time.Minute),
		templates: templates,
	}
	s.javaReady, s.javaMessage = s.checkGeneration()
	s.routes()
	return s, nil
}

// Handler returns the fully wrapped HTTP handler.
func (s *Server) Handler() http.Handler {
	return s.handler
}

// Close stops the background sweep.
func (s *Server) Close() {
	if s.sweepStop != nil {
		s.sweepStop()
	}
}

// checkGeneration reports whether this server can actually build packages:
// the pinned pipeline inputs must exist and both JDKs must be usable.
func (s *Server) checkGeneration() (bool, string) {
	probe := s.cfg.Generator.Config()
	if err := probe.ApplyDefaults(); err != nil {
		return false, err.Error()
	}
	if pipeline.JavaMajor(probe.Java17) < 17 {
		return false, "GraphHopper JDK 17 not found at " + probe.Java17
	}
	if pipeline.JavaMajor(probe.Java21) < 21 {
		return false, "Planetiler JDK 21 not found at " + probe.Java21
	}
	return true, ""
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /api/v1/health", s.handleHealth)
	s.mux.HandleFunc("GET /api/v1/csrf", s.handleCSRF)
	s.mux.HandleFunc("GET /api/v1/catalog", s.handleCatalog)
	s.mux.HandleFunc("GET /api/v1/extracts", s.handleExtracts)
	s.mux.HandleFunc("GET /api/v1/builds", s.handleBuildList)
	s.mux.HandleFunc("POST /api/v1/builds", s.handleBuildCreate)
	s.mux.HandleFunc("GET /api/v1/builds/{id}", s.handleBuildGet)
	s.mux.HandleFunc("POST /api/v1/builds/{id}/cancel", s.handleBuildCancel)
	s.mux.HandleFunc("GET /api/v1/artifacts/{region}/{fingerprint}/{file}", s.handleArtifact)

	if s.cfg.AdminToken != "" {
		s.mux.HandleFunc("GET /api/v1/admin/jobs", s.handleAdminJobs)
		s.mux.HandleFunc("POST /api/v1/admin/jobs/{id}/cancel", s.handleAdminCancel)
	}

	if !s.cfg.Headless {
		s.mux.HandleFunc("GET /{$}", s.handleIndex)
		s.mux.Handle("GET /static/", http.StripPrefix("/static/", ui.StaticHandler()))
		s.mux.HandleFunc("GET /ui/regions", s.handleRegionsFragment)
		s.mux.HandleFunc("GET /ui/jobs", s.handleJobsFragment)
		s.mux.HandleFunc("POST /ui/builds", s.handleUIBuild)
		s.mux.HandleFunc("POST /ui/requests", s.handleUIRequest)
		s.mux.HandleFunc("POST /ui/builds/{id}/cancel", s.handleUICancel)
	}

	s.handler = s.recoverMW(s.headersMW(s.bodyLimitMW(s.mux)))
	ctx, cancel := context.WithCancel(context.Background())
	s.sweepStop = cancel
	go func() {
		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				s.limiter.Sweep(now)
			}
		}
	}()
}

func (s *Server) recoverMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				s.cfg.Log.Error("panic serving request", "path", r.URL.Path, "panic", rec, "stack", string(debug.Stack()))
				writeError(w, http.StatusInternalServerError, "internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) headersMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		security.Headers(w, s.requestIsTLS(r))
		next.ServeHTTP(w, r)
	})
}

func (s *Server) bodyLimitMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost || r.Method == http.MethodPut || r.Method == http.MethodPatch {
			r.Body = http.MaxBytesReader(w, r.Body, s.cfg.Limits.MaxBodyBytes)
		}
		next.ServeHTTP(w, r)
	})
}

// requestIsTLS reports whether the client connection is protected by TLS,
// honoring a trusted proxy's X-Forwarded-Proto.
func (s *Server) requestIsTLS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	if s.cfg.Trusted.IsTrustedPeer(r.RemoteAddr) &&
		strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
		return true
	}
	return false
}

func (s *Server) clientKey(r *http.Request) string {
	return trustedproxy.BucketKey(s.cfg.Trusted.ClientAddr(r.RemoteAddr, r.Header))
}

// allow charges the class's token bucket, writing 429 when empty.
func (s *Server) allow(w http.ResponseWriter, r *http.Request, class string) bool {
	ok, retry := s.allowRequest(r, class)
	if !ok {
		w.Header().Set("Retry-After", fmt.Sprint(int(retry.Seconds()+0.999)))
		writeError(w, http.StatusTooManyRequests, "rate limit exceeded; retry later")
		return false
	}
	return true
}

// allowRequest checks a class's token bucket without writing a response.
func (s *Server) allowRequest(r *http.Request, class string) (bool, time.Duration) {
	var burst int
	var per time.Duration
	switch class {
	case "build":
		burst, per = s.cfg.Limits.BuildBurst, s.cfg.Limits.BuildPer
	case "poll":
		burst, per = s.cfg.Limits.PollBurst, s.cfg.Limits.PollPer
	case "search":
		burst, per = s.cfg.Limits.SearchBurst, s.cfg.Limits.SearchPer
	case "download":
		burst, per = s.cfg.Limits.DownloadBurst, s.cfg.Limits.DownloadPer
	default:
		burst, per = s.cfg.Limits.DefaultBurst, s.cfg.Limits.DefaultPer
	}
	return s.limiter.Allow(class+":"+s.clientKey(r), burst, per, s.cfg.Now())
}

// requireCSRF validates the double-submit token on state-changing requests.
func (s *Server) requireCSRF(w http.ResponseWriter, r *http.Request) bool {
	if err := s.cfg.CSRF.Validate(r, s.cfg.Now()); err != nil {
		writeError(w, http.StatusForbidden, err.Error())
		return false
	}
	return true
}

// requireAdmin authenticates an admin API call with the configured bearer
// token. Admin routes are not registered when no token is configured.
func (s *Server) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	header := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		writeError(w, http.StatusUnauthorized, "missing admin token")
		return false
	}
	provided := strings.TrimPrefix(header, prefix)
	if subtle.ConstantTimeCompare([]byte(provided), []byte(s.cfg.AdminToken)) != 1 {
		writeError(w, http.StatusUnauthorized, "invalid admin token")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

// jobStatus maps manager errors to HTTP status codes.
func jobStatus(err error) (int, string) {
	switch {
	case errors.Is(err, jobs.ErrUnknownRegion):
		return http.StatusNotFound, err.Error()
	case errors.Is(err, jobs.ErrDisabledRegion):
		return http.StatusForbidden, err.Error()
	case errors.Is(err, jobs.ErrQueueFull):
		return http.StatusTooManyRequests, err.Error()
	case errors.Is(err, jobs.ErrNotFound):
		return http.StatusNotFound, err.Error()
	case errors.Is(err, jobs.ErrNotCancelable):
		return http.StatusConflict, err.Error()
	default:
		return http.StatusInternalServerError, "internal error"
	}
}

func (s *Server) maxExtractBytes() int64 {
	if s.cfg.Extracts == nil {
		return 0
	}
	return s.cfg.Extracts.MaxExtractBytes()
}

func humanSize(n int64) string { return ui.HumanBytes(n) }
