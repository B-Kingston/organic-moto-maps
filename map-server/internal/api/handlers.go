package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/jobs"
	"github.com/organicmoto/curveMaps/map-server/internal/ui"
)

// --- JSON API ---------------------------------------------------------------

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"version": s.cfg.Version,
		"time":    s.cfg.Now().UTC().Format(time.RFC3339),
		"generation": map[string]any{
			"enabled": s.javaReady,
			"message": s.javaMessage,
		},
		"queue": map[string]any{
			"depth":   s.cfg.Jobs.QueuedCount(),
			"maxSize": s.cfg.Jobs.MaxQueued(),
		},
		"requests": map[string]any{
			"enabled":         s.extractsEnabled(),
			"maxExtractBytes": s.maxExtractBytes(),
		},
		"note": "rate limits mitigate abuse; this server is not authenticated",
	})
}

func (s *Server) handleCSRF(w http.ResponseWriter, r *http.Request) {
	if !s.allow(w, r, "default") {
		return
	}
	token := s.cfg.CSRF.Ensure(w, r, s.cfg.Now())
	writeJSON(w, http.StatusOK, map[string]string{"token": token})
}

type regionArtifact struct {
	Available   bool   `json:"available"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Size        int64  `json:"size,omitempty"`
	SHA256      string `json:"sha256,omitempty"`
	GeneratedAt string `json:"generatedAt,omitempty"`
	DownloadURL string `json:"downloadUrl,omitempty"`
}

type regionStatus struct {
	ID          string         `json:"id"`
	Name        string         `json:"name"`
	Coverage    string         `json:"coverage,omitempty"`
	Disabled    bool           `json:"disabled"`
	MaxZoom     int            `json:"maxZoom"`
	SourceDate  string         `json:"sourceDate"`
	SourceURL   string         `json:"sourceUrl,omitempty"`
	SourceSHA   string         `json:"sourceSha256,omitempty"`
	Pinned      bool           `json:"sourcePinned"`
	Artifact    regionArtifact `json:"artifact"`
	ActiveJobID string         `json:"activeJobId,omitempty"`
	Requested   bool           `json:"requested"`
	ExtractID   string         `json:"extractId,omitempty"`
}

func (s *Server) handleCatalog(w http.ResponseWriter, r *http.Request) {
	if !s.allow(w, r, "poll") {
		return
	}
	regions := make([]regionStatus, 0, len(s.cfg.Catalog.Regions))
	for _, region := range s.cfg.Catalog.List() {
		view := regionStatus{
			ID:         region.ID,
			Name:       region.Name,
			Coverage:   region.Coverage,
			Disabled:   region.Disabled,
			MaxZoom:    region.EffectiveMaxZoom(),
			SourceDate: region.Source.Date,
			SourceURL:  region.Source.URL,
			SourceSHA:  region.Source.SHA256,
			Pinned:     region.Source.SHA256 != "",
			Requested:  region.Requested,
			ExtractID:  region.ExtractID,
		}
		if artifact, ok := s.cfg.Generator.LatestArtifact(region.ID); ok {
			view.Artifact = regionArtifact{
				Available:   true,
				Fingerprint: artifact.Fingerprint,
				Size:        artifact.Size,
				SHA256:      artifact.SHA256,
				GeneratedAt: artifact.GeneratedAt,
				DownloadURL: downloadURL(region.ID, artifact.Fingerprint),
			}
		}
		if active := s.cfg.Jobs.List(region.ID, 1); len(active) > 0 && active[0].Active() {
			view.ActiveJobID = active[0].ID
		}
		regions = append(regions, view)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"regions": regions,
		"package": map[string]any{
			"schemaVersion": 1,
			"extension":     ".motomap",
		},
	})
}

func (s *Server) handleBuildList(w http.ResponseWriter, r *http.Request) {
	if !s.allow(w, r, "poll") {
		return
	}
	regionID := r.URL.Query().Get("regionId")
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	list := s.cfg.Jobs.List(regionID, limit)
	public := make([]jobs.Public, 0, len(list))
	for _, j := range list {
		public = append(public, j.Public())
	}
	writeJSON(w, http.StatusOK, map[string]any{"jobs": public})
}

type buildRequest struct {
	RegionID  string   `json:"regionId"`
	ExtractID string   `json:"extractId"`
	Query     string   `json:"query"`
	Lat       *float64 `json:"lat"`
	Lon       *float64 `json:"lon"`
}

// forms counts how many request forms are present (lat+lon is one form).
func (b buildRequest) forms() (int, error) {
	if (b.Lat == nil) != (b.Lon == nil) {
		return 0, errors.New("lat and lon must be given together")
	}
	n := 0
	for _, set := range []bool{b.RegionID != "", b.ExtractID != "", b.Query != "", b.Lat != nil} {
		if set {
			n++
		}
	}
	return n, nil
}

func (s *Server) handleBuildCreate(w http.ResponseWriter, r *http.Request) {
	if !s.allow(w, r, "build") {
		return
	}
	if !s.requireCSRF(w, r) {
		return
	}
	var req buildRequest
	if err := decodeJSONBody(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	forms, err := req.forms()
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if forms != 1 {
		writeError(w, http.StatusBadRequest, "provide exactly one of regionId, extractId, query, or lat and lon")
		return
	}
	var job jobs.Job
	if req.RegionID != "" {
		job, err = s.cfg.Jobs.Submit(r.Context(), req.RegionID)
		if err != nil {
			status, message := jobStatus(err)
			writeError(w, status, message)
			return
		}
	} else {
		var fail *requestFailure
		job, fail = s.buildExtractRequest(r.Context(), req)
		if fail != nil {
			writeJSON(w, fail.status, fail.body())
			return
		}
	}
	status := http.StatusAccepted
	if job.State == jobs.StateCompleted {
		status = http.StatusOK
	}
	writeJSON(w, status, job.Public())
}

func (s *Server) handleBuildGet(w http.ResponseWriter, r *http.Request) {
	if !s.allow(w, r, "poll") {
		return
	}
	job, ok := s.cfg.Jobs.Get(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	writeJSON(w, http.StatusOK, job.Public())
}

func (s *Server) handleBuildCancel(w http.ResponseWriter, r *http.Request) {
	if !s.allow(w, r, "build") {
		return
	}
	if !s.requireCSRF(w, r) {
		return
	}
	if err := s.cfg.Jobs.Cancel(r.PathValue("id")); err != nil {
		status, message := jobStatus(err)
		writeError(w, status, message)
		return
	}
	job, _ := s.cfg.Jobs.Get(r.PathValue("id"))
	writeJSON(w, http.StatusOK, job.Public())
}

func (s *Server) handleAdminJobs(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	list := s.cfg.Jobs.List("", 200)
	public := make([]jobs.Public, 0, len(list))
	for _, j := range list {
		public = append(public, j.Public())
	}
	writeJSON(w, http.StatusOK, map[string]any{"jobs": public})
}

func (s *Server) handleAdminCancel(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	if err := s.cfg.Jobs.Cancel(r.PathValue("id")); err != nil {
		status, message := jobStatus(err)
		writeError(w, status, message)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "canceled"})
}

func decodeJSONBody(r *http.Request, target any) error {
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	dec.DisallowUnknownFields()
	if err := dec.Decode(target); err != nil {
		return err
	}
	return nil
}

func downloadURL(regionID, fingerprint string) string {
	return "/api/v1/artifacts/" + regionID + "/" + fingerprint + "/" + regionID + ".motomap"
}

// --- HTMX UI ----------------------------------------------------------------

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	page, err := s.page(w, r, "")
	if err != nil {
		s.cfg.Log.Error("render index", "error", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := s.templates.Render(w, "index", page); err != nil {
		s.cfg.Log.Error("render index template", "error", err)
	}
}

func (s *Server) handleRegionsFragment(w http.ResponseWriter, r *http.Request) {
	if ok, retry := s.allowRequest(r, "poll"); !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(retry.Seconds()+0.999)))
		http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
		return
	}
	page, err := s.page(w, r, "")
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := s.templates.Render(w, "regionsFragment", page); err != nil {
		s.cfg.Log.Error("render regions", "error", err)
	}
}

func (s *Server) handleJobsFragment(w http.ResponseWriter, r *http.Request) {
	if ok, retry := s.allowRequest(r, "poll"); !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(retry.Seconds()+0.999)))
		http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
		return
	}
	page, err := s.page(w, r, "")
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := s.templates.Render(w, "jobsFragment", page); err != nil {
		s.cfg.Log.Error("render jobs", "error", err)
	}
}

func (s *Server) handleUIBuild(w http.ResponseWriter, r *http.Request) {
	if ok, retry := s.allowRequest(r, "build"); !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(retry.Seconds()+0.999)))
		s.renderIndexError(w, r, "rate limit exceeded; retry later", http.StatusTooManyRequests)
		return
	}
	if !s.requireCSRF(w, r) {
		return
	}
	if err := r.ParseForm(); err != nil {
		s.renderIndexError(w, r, "invalid form submission", http.StatusBadRequest)
		return
	}
	regionID := r.PostFormValue("regionId")
	if regionID == "" {
		s.renderIndexError(w, r, "choose a region", http.StatusBadRequest)
		return
	}
	if _, err := s.cfg.Jobs.Submit(r.Context(), regionID); err != nil {
		status, message := jobStatus(err)
		s.renderIndexError(w, r, message, status)
		return
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

// handleUIRequest builds a region the operator did not list: a "lat,lon" input
// goes the coordinate route, anything else is a place name.
func (s *Server) handleUIRequest(w http.ResponseWriter, r *http.Request) {
	if ok, retry := s.allowRequest(r, "build"); !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(retry.Seconds()+0.999)))
		s.renderIndexError(w, r, "rate limit exceeded; retry later", http.StatusTooManyRequests)
		return
	}
	if !s.requireCSRF(w, r) {
		return
	}
	if !s.extractsEnabled() {
		s.renderIndexError(w, r, "this server only builds catalog regions", http.StatusNotFound)
		return
	}
	if err := r.ParseForm(); err != nil {
		s.renderIndexError(w, r, "invalid form submission", http.StatusBadRequest)
		return
	}
	input := strings.TrimSpace(r.PostFormValue("place"))
	if input == "" || len(input) > 100 {
		s.renderIndexError(w, r, "enter a place name or lat,lon", http.StatusBadRequest)
		return
	}
	var req buildRequest
	if m := coordinateInput.FindStringSubmatch(input); m != nil {
		lat, _ := strconv.ParseFloat(m[1], 64)
		lon, _ := strconv.ParseFloat(m[2], 64)
		req.Lat, req.Lon = &lat, &lon
	} else {
		req.Query = input
	}
	if _, fail := s.buildExtractRequest(r.Context(), req); fail != nil {
		s.renderIndexError(w, r, describeFailure(fail), fail.status)
		return
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) handleUICancel(w http.ResponseWriter, r *http.Request) {
	if ok, retry := s.allowRequest(r, "build"); !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(retry.Seconds()+0.999)))
		s.renderIndexError(w, r, "rate limit exceeded; retry later", http.StatusTooManyRequests)
		return
	}
	if !s.requireCSRF(w, r) {
		return
	}
	if err := s.cfg.Jobs.Cancel(r.PathValue("id")); err != nil {
		status, message := jobStatus(err)
		s.renderIndexError(w, r, message, status)
		return
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) renderIndexError(w http.ResponseWriter, r *http.Request, message string, status int) {
	page, err := s.page(w, r, message)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	if err := s.templates.Render(w, "index", page); err != nil {
		s.cfg.Log.Error("render index error", "error", err)
	}
}

func (s *Server) page(w http.ResponseWriter, r *http.Request, errorMessage string) (ui.Page, error) {
	csrf := s.cfg.CSRF.Ensure(w, r, s.cfg.Now())
	page := ui.Page{
		Version:           s.cfg.Version,
		GenerationEnabled: s.javaReady,
		JavaMessage:       s.javaMessage,
		ArtifactRoot:      s.cfg.Generator.Config().ArtifactRoot,
		QueueDepth:        s.cfg.Jobs.QueuedCount(),
		MaxQueued:         s.cfg.Jobs.MaxQueued(),
		CSRF:              csrf,
		Error:             errorMessage,
		RequestsEnabled:   s.extractsEnabled(),
		MaxExtract:        ui.HumanBytes(s.maxExtractBytes()),
	}
	for _, region := range s.cfg.Catalog.List() {
		view := ui.RegionView{
			ID:         region.ID,
			Name:       region.Name,
			Coverage:   region.Coverage,
			Notes:      region.Notes,
			SourceKind: sourceKind(region),
			SourceDate: region.Source.Date,
			Disabled:   region.Disabled,
		}
		if region.Source.SHA256 != "" {
			view.SourceSHA = "pinned " + region.Source.SHA256[:12]
		} else {
			view.SourceSHA = "hash recorded at build time"
		}
		if artifact, ok := s.cfg.Generator.LatestArtifact(region.ID); ok {
			view.Cached = true
			view.CachedSize = ui.HumanBytes(artifact.Size)
			view.CachedAt = artifact.GeneratedAt
			view.DownloadURL = downloadURL(region.ID, artifact.Fingerprint)
		}
		if active := s.cfg.Jobs.List(region.ID, 1); len(active) > 0 && active[0].Active() {
			jobView := jobViewOf(active[0])
			view.ActiveJob = &jobView
		}
		page.Regions = append(page.Regions, view)
	}
	for _, job := range s.cfg.Jobs.List("", 50) {
		page.Jobs = append(page.Jobs, jobViewOf(job))
	}
	return page, nil
}

func sourceKind(region catalog.Region) string {
	if region.Source.URL != "" {
		return "Geofabrik URL"
	}
	return "local file"
}

func jobViewOf(job jobs.Job) ui.JobView {
	view := ui.JobView{
		ID:              job.ID,
		RegionName:      job.RegionName,
		State:           string(job.State),
		Step:            job.Step,
		ProgressPercent: int(job.Progress*100 + 0.5),
		Message:         job.Message,
		CreatedAt:       job.CreatedAt.UTC().Format("2006-01-02 15:04 UTC"),
		Active:          job.Active(),
		Cancelable:      job.State == jobs.StateQueued || job.State == jobs.StateRunning,
	}
	if job.State == jobs.StateCompleted && job.ArtifactPath != "" {
		view.DownloadURL = downloadURL(job.RegionID, job.Fingerprint)
	}
	return view
}
