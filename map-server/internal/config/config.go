// Package config loads the map server's optional JSON configuration file.
// Command-line flags and environment variables override file values.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// File is the on-disk configuration document. Every field is optional.
type File struct {
	Addr           string         `json:"addr,omitempty"`
	StateDir       string         `json:"stateDir,omitempty"`
	CatalogPath    string         `json:"catalogPath,omitempty"`
	Headless       bool           `json:"headless,omitempty"`
	TrustedProxies []string       `json:"trustedProxies,omitempty"`
	AdminToken     string         `json:"adminToken,omitempty"`
	Limits         *Limits        `json:"limits,omitempty"`
	Pipeline       *PipelineEntry `json:"pipeline,omitempty"`
	Requests       *RequestsEntry `json:"requests,omitempty"`
}

// RequestsEntry configures builds requested by place name or coordinate,
// resolved against Geofabrik's extract index.
type RequestsEntry struct {
	// Enabled defaults to true.
	Enabled *bool `json:"enabled,omitempty"`
	// MaxExtractBytes caps a requested extract (default 768 MiB: state-sized).
	MaxExtractBytes int64 `json:"maxExtractBytes,omitempty"`
	// MaxRegions caps how many requested regions the server keeps (default 24).
	MaxRegions int `json:"maxRegions,omitempty"`
	// RefreshDays re-resolves a requested region whose data is older (default 30).
	RefreshDays int `json:"refreshDays,omitempty"`
	// IndexURL overrides Geofabrik's index (tests and mirrors).
	IndexURL string `json:"indexUrl,omitempty"`
}

// Limits mirrors api.Limits in JSON form.
type Limits struct {
	BuildBurst             int   `json:"buildBurst,omitempty"`
	BuildPerSeconds        int   `json:"buildPerSeconds,omitempty"`
	PollBurst              int   `json:"pollBurst,omitempty"`
	PollPerSeconds         int   `json:"pollPerSeconds,omitempty"`
	DownloadBurst          int   `json:"downloadBurst,omitempty"`
	DownloadPerSeconds     int   `json:"downloadPerSeconds,omitempty"`
	DefaultBurst           int   `json:"defaultBurst,omitempty"`
	DefaultPerSeconds      int   `json:"defaultPerSeconds,omitempty"`
	MaxConcurrentDownloads int   `json:"maxConcurrentDownloads,omitempty"`
	MaxConcurrentPerClient int   `json:"maxConcurrentPerClient,omitempty"`
	MaxBodyBytes           int64 `json:"maxBodyBytes,omitempty"`
	SearchBurst            int   `json:"searchBurst,omitempty"`
	SearchPerSeconds       int   `json:"searchPerSeconds,omitempty"`
}

// PipelineEntry mirrors pipeline.Config overrides in JSON form.
type PipelineEntry struct {
	RepoRoot             string `json:"repoRoot,omitempty"`
	WorkRoot             string `json:"workRoot,omitempty"`
	ArtifactRoot         string `json:"artifactRoot,omitempty"`
	CacheDir             string `json:"cacheDir,omitempty"`
	Java17               string `json:"java17,omitempty"`
	Java21               string `json:"java21,omitempty"`
	GraphHeap            string `json:"graphHeap,omitempty"`
	TilesHeap            string `json:"tilesHeap,omitempty"`
	GeocoderHeap         string `json:"geocoderHeap,omitempty"`
	KeepWork             *bool  `json:"keepWork,omitempty"`
	MinFreeDiskBytes     int64  `json:"minFreeDiskBytes,omitempty"`
	MaxSourceBytes       int64  `json:"maxSourceBytes,omitempty"`
	VerifyArchive        *bool  `json:"verifyArchive,omitempty"`
	ExpectedGraphProfile string `json:"expectedGraphProfile,omitempty"`
}

// Load reads a configuration file. A missing path yields an empty config.
func Load(path string) (File, error) {
	if strings.TrimSpace(path) == "" {
		return File{}, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return File{}, fmt.Errorf("read config %s: %w", path, err)
	}
	var file File
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&file); err != nil {
		return File{}, fmt.Errorf("decode config %s: %w", path, err)
	}
	return file, nil
}
