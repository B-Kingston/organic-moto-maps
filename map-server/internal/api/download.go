package api

import (
	"errors"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
)

var fingerprintPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// handleArtifact serves a published package (or its sha256 sidecar) with
// HTTP Range and ETag support, so a phone can resume an interrupted download.
// Artifacts are immutable, hence the long-lived cache headers.
func (s *Server) handleArtifact(w http.ResponseWriter, r *http.Request) {
	regionID := r.PathValue("region")
	fingerprint := r.PathValue("fingerprint")
	file := r.PathValue("file")

	if !fingerprintPattern.MatchString(fingerprint) {
		writeError(w, http.StatusNotFound, "artifact not found")
		return
	}
	if _, err := s.cfg.Catalog.Lookup(regionID); err != nil {
		if !errors.Is(err, catalog.ErrDisabled) {
			writeError(w, http.StatusNotFound, "artifact not found")
			return
		}
	}
	packageName := regionID + ".motomap"
	if file != packageName && file != packageName+".sha256" {
		writeError(w, http.StatusNotFound, "artifact not found")
		return
	}
	path := s.cfg.Generator.ArtifactPackagePath(regionID, fingerprint)
	if strings.HasSuffix(file, ".sha256") {
		path += ".sha256"
	}
	info, err := os.Stat(path)
	if err != nil || info.IsDir() || info.Size() == 0 {
		writeError(w, http.StatusNotFound, "artifact not found")
		return
	}

	if !s.allow(w, r, "download") {
		return
	}
	key := s.clientKey(r)
	if !s.downloads.Acquire(key) {
		w.Header().Set("Retry-After", "5")
		writeError(w, http.StatusTooManyRequests, "too many concurrent downloads; retry later")
		return
	}
	defer s.downloads.Release(key)

	etag := artifactETag(path, fingerprint, info.Size())
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="`+file+`"`)
	w.Header().Set("X-Content-Type-Options", "nosniff")

	f, err := os.Open(path)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "artifact unavailable")
		return
	}
	defer f.Close()
	http.ServeContent(w, r, file, time.Time{}, f)
}

func artifactETag(path, fingerprint string, size int64) string {
	if sidecar, err := os.ReadFile(path + ".sha256"); err == nil {
		sum := strings.TrimSpace(string(sidecar))
		if sum != "" {
			return `"` + sum + `"`
		}
	}
	return `"` + fingerprint + "-" + strconv.FormatInt(size, 10) + `"`
}
