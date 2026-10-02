package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/geofabrik"
	"github.com/organicmoto/curveMaps/map-server/internal/jobs"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
)

// ExtractResolver maps a place name or coordinate to Geofabrik extracts and
// pins a chosen one to a dated download. *geofabrik.Service satisfies it.
type ExtractResolver interface {
	MaxExtractBytes() int64
	Search(ctx context.Context, query string, limit int) ([]geofabrik.Candidate, error)
	Locate(ctx context.Context, lat, lon float64, limit int) ([]geofabrik.Candidate, error)
	Resolve(ctx context.Context, extractID string) (geofabrik.Resolved, error)
}

const (
	defaultRefreshAfter = 30 * 24 * time.Hour
	defaultCandidates   = 10
	maxCandidates       = 25
)

// requestFailure is a client-facing failure of an extract request.
type requestFailure struct {
	status     int
	message    string
	candidates []geofabrik.Candidate
}

func (f *requestFailure) body() map[string]any {
	out := map[string]any{"error": f.message}
	if len(f.candidates) > 0 {
		out["candidates"] = f.candidates
	}
	return out
}

func (s *Server) extractsEnabled() bool { return s.cfg.Extracts != nil }

func (s *Server) refreshAfter() time.Duration {
	if s.cfg.RefreshAfter > 0 {
		return s.cfg.RefreshAfter
	}
	return defaultRefreshAfter
}

// OnBuildCompleted pins the source hash observed by a finished build of a
// requested region so later requests can serve its cached package without
// re-resolving. Wire it into jobs.Options.OnCompleted.
func (s *Server) OnBuildCompleted(region catalog.Region, result pipeline.Result) {
	if !region.Requested || s.cfg.Requested == nil || result.Source.SHA256 == "" {
		return
	}
	if err := s.cfg.Requested.PinSHA(region.ID, result.Source.SHA256); err != nil {
		s.cfg.Log.Warn("pin requested region hash", "region", region.ID, "error", err)
	}
}

func (s *Server) handleExtracts(w http.ResponseWriter, r *http.Request) {
	if !s.extractsEnabled() {
		writeError(w, http.StatusNotFound, "this server only builds catalog regions")
		return
	}
	if !s.allow(w, r, "search") {
		return
	}
	q := r.URL.Query()
	limit := defaultCandidates
	if raw := q.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > maxCandidates {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("limit must be 1..%d", maxCandidates))
			return
		}
		limit = n
	}
	text := strings.TrimSpace(q.Get("q"))
	hasLat, hasLon := q.Has("lat"), q.Has("lon")
	var (
		list []geofabrik.Candidate
		err  error
	)
	switch {
	case text != "" && (hasLat || hasLon):
		writeError(w, http.StatusBadRequest, "use either q or lat and lon, not both")
		return
	case text != "":
		if len(text) > 100 {
			writeError(w, http.StatusBadRequest, "q is too long")
			return
		}
		list, err = s.cfg.Extracts.Search(r.Context(), text, limit)
	case hasLat && hasLon:
		lat, lon, perr := parseLatLon(q.Get("lat"), q.Get("lon"))
		if perr != nil {
			writeError(w, http.StatusBadRequest, perr.Error())
			return
		}
		list, err = s.cfg.Extracts.Locate(r.Context(), lat, lon, limit)
	default:
		writeError(w, http.StatusBadRequest, "provide q, or both lat and lon")
		return
	}
	if err != nil {
		f := extractFailure(err)
		writeError(w, f.status, f.message)
		return
	}
	if list == nil {
		list = []geofabrik.Candidate{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"extracts":        list,
		"maxExtractBytes": s.cfg.Extracts.MaxExtractBytes(),
	})
}

func parseLatLon(rawLat, rawLon string) (float64, float64, error) {
	lat, err1 := strconv.ParseFloat(rawLat, 64)
	lon, err2 := strconv.ParseFloat(rawLon, 64)
	if err1 != nil || err2 != nil || lat != lat || lon != lon {
		return 0, 0, errors.New("lat and lon must be numbers")
	}
	if lat < -90 || lat > 90 || lon < -180 || lon > 180 {
		return 0, 0, errors.New("lat must be within -90..90 and lon within -180..180")
	}
	return lat, lon, nil
}

// extractFailure maps resolver errors to HTTP statuses.
func extractFailure(err error) *requestFailure {
	f := &requestFailure{message: err.Error()}
	switch {
	case errors.Is(err, geofabrik.ErrUnknownExtract):
		f.status = http.StatusNotFound
	case errors.Is(err, geofabrik.ErrTooLarge):
		f.status = http.StatusRequestEntityTooLarge
	case errors.Is(err, geofabrik.ErrNotBuildable):
		f.status = http.StatusUnprocessableEntity
	case errors.Is(err, geofabrik.ErrUpstream):
		f.status = http.StatusServiceUnavailable
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		f.status = http.StatusServiceUnavailable
		f.message = "the request was interrupted"
	default:
		f.status = http.StatusInternalServerError
		f.message = "internal error"
	}
	return f
}

// extractIDForQuery picks the one eligible candidate that names the query
// exactly; anything less certain is ambiguous and the client must choose.
func (s *Server) extractIDForQuery(ctx context.Context, query string) (string, *requestFailure) {
	list, err := s.cfg.Extracts.Search(ctx, query, defaultCandidates)
	if err != nil {
		return "", extractFailure(err)
	}
	if len(list) == 0 {
		return "", &requestFailure{status: http.StatusNotFound, message: fmt.Sprintf("no extract matches %q", query)}
	}
	var exact []geofabrik.Candidate
	for _, c := range list {
		if c.Eligible && (strings.EqualFold(c.Name, query) || strings.EqualFold(c.ID, query)) {
			exact = append(exact, c)
		}
	}
	if len(exact) == 1 {
		return exact[0].ID, nil
	}
	return "", &requestFailure{
		status:     http.StatusConflict,
		message:    fmt.Sprintf("%q matches several places or none this server can build; be more specific or choose one of the candidates", query),
		candidates: list,
	}
}

func (s *Server) extractIDForPoint(ctx context.Context, lat, lon float64) (string, *requestFailure) {
	list, err := s.cfg.Extracts.Locate(ctx, lat, lon, defaultCandidates)
	if err != nil {
		return "", extractFailure(err)
	}
	for _, c := range list {
		if c.Eligible {
			return c.ID, nil
		}
	}
	return "", &requestFailure{
		status:     http.StatusUnprocessableEntity,
		message:    "no extract covering that point is small enough for this server to build",
		candidates: list,
	}
}

// submitExtract queues a build for a Geofabrik extract id, resolving and
// recording it as a requested region unless that can be skipped.
func (s *Server) submitExtract(ctx context.Context, extractID string) (jobs.Job, *requestFailure) {
	regionID := geofabrik.RegionID(extractID)
	if !s.isOperatorRegion(regionID) {
		if active, ok := s.cfg.Jobs.ActiveFor(regionID); ok {
			return active, nil
		}
		if !s.freshRequested(regionID) {
			resolved, err := s.cfg.Extracts.Resolve(ctx, extractID)
			if err != nil {
				return jobs.Job{}, extractFailure(err)
			}
			region := catalog.Region{
				ID:        regionID,
				Name:      resolved.Extract.Name,
				Coverage:  strings.Join(append(append([]string(nil), resolved.Extract.Path...), resolved.Extract.Name), " › "),
				MaxZoom:   14,
				Requested: true,
				ExtractID: resolved.Extract.ID,
				Notes:     "Built on request from Geofabrik extract " + resolved.Extract.ID,
				Source: catalog.Source{
					URL: resolved.URL, Date: resolved.Date, MD5: resolved.MD5,
					MaxBytes: s.cfg.Extracts.MaxExtractBytes(),
				},
			}
			if err := s.cfg.Requested.Upsert(region); err != nil {
				if errors.Is(err, catalog.ErrRequestedFull) {
					return jobs.Job{}, &requestFailure{status: http.StatusInsufficientStorage,
						message: "this server already holds the maximum number of requested regions; ask the operator"}
				}
				s.cfg.Log.Error("store requested region", "region", regionID, "error", err)
				return jobs.Job{}, &requestFailure{status: http.StatusInternalServerError, message: "internal error"}
			}
		}
	}
	job, err := s.cfg.Jobs.Submit(ctx, regionID)
	if err != nil {
		status, message := jobStatus(err)
		return jobs.Job{}, &requestFailure{status: status, message: message}
	}
	return job, nil
}

func (s *Server) isOperatorRegion(id string) bool {
	for _, r := range s.cfg.Catalog.Regions {
		if r.ID == id {
			return true
		}
	}
	return false
}

// freshRequested reports whether a stored requested region has a pinned hash
// and a source newer than the refresh window, so it can be built (or served
// from cache) as-is.
func (s *Server) freshRequested(regionID string) bool {
	region, ok := s.cfg.Requested.Get(regionID)
	if !ok || region.Source.SHA256 == "" {
		return false
	}
	date, ok := parseSourceDate(region.Source.Date)
	return ok && date.After(s.cfg.Now().Add(-s.refreshAfter()))
}

func parseSourceDate(raw string) (time.Time, bool) {
	for _, layout := range []string{"060102", "2006-01-02"} {
		if t, err := time.Parse(layout, raw); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

// buildTarget turns an extract-style request into a started job.
func (s *Server) buildExtractRequest(ctx context.Context, req buildRequest) (jobs.Job, *requestFailure) {
	if !s.extractsEnabled() {
		return jobs.Job{}, &requestFailure{status: http.StatusNotFound, message: "this server only builds catalog regions"}
	}
	var (
		extractID string
		fail      *requestFailure
	)
	switch {
	case req.ExtractID != "":
		extractID = req.ExtractID
	case req.Query != "":
		extractID, fail = s.extractIDForQuery(ctx, strings.TrimSpace(req.Query))
	default:
		lat, lon, err := parseLatLon(fmt.Sprint(*req.Lat), fmt.Sprint(*req.Lon))
		if err != nil {
			return jobs.Job{}, &requestFailure{status: http.StatusBadRequest, message: err.Error()}
		}
		extractID, fail = s.extractIDForPoint(ctx, lat, lon)
	}
	if fail != nil {
		return jobs.Job{}, fail
	}
	return s.submitExtract(ctx, extractID)
}

var coordinateInput = regexp.MustCompile(`^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$`)

// describeFailure renders a failure, with its candidates, for the HTML UI.
func describeFailure(f *requestFailure) string {
	if len(f.candidates) == 0 {
		return f.message
	}
	parts := make([]string, 0, len(f.candidates))
	for _, c := range f.candidates {
		label := fmt.Sprintf("%s (%s", c.Name, c.ID)
		if c.Bytes > 0 {
			label += ", " + humanSize(c.Bytes)
		}
		if !c.Eligible {
			label += ", not buildable"
		}
		parts = append(parts, label+")")
	}
	return f.message + ": " + strings.Join(parts, "; ")
}
