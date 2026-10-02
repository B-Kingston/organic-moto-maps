// Package ui serves the embedded, dependency-free HTMX interface. The only
// JavaScript is a vendored copy of htmx; there is no CDN and no build step.
package ui

import (
	"embed"
	"fmt"
	"html/template"
	"io"
	"io/fs"
	"net/http"
	"time"
)

//go:embed web
var webFS embed.FS

// Templates renders the embedded pages.
type Templates struct {
	t *template.Template
}

// New parses the embedded templates.
func New() (*Templates, error) {
	t, err := template.New("root").Funcs(template.FuncMap{
		"humanBytes": HumanBytes,
		"percent":    func(p float64) int { return int(p*100 + 0.5) },
		"since": func(t time.Time) string {
			if t.IsZero() {
				return ""
			}
			return t.UTC().Format("2006-01-02 15:04 UTC")
		},
	}).ParseFS(webFS, "web/*.html")
	if err != nil {
		return nil, fmt.Errorf("parse embedded templates: %w", err)
	}
	return &Templates{t: t}, nil
}

// Render writes a named template.
func (t *Templates) Render(w io.Writer, name string, data any) error {
	return t.t.ExecuteTemplate(w, name, data)
}

// StaticHandler serves the embedded stylesheet and vendored htmx.
func StaticHandler() http.Handler {
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		panic(err)
	}
	return http.FileServer(http.FS(sub))
}

// Page is the shared view model for the index page and its fragments.
type Page struct {
	Version           string
	GenerationEnabled bool
	JavaMessage       string
	ArtifactRoot      string
	QueueDepth        int
	MaxQueued         int
	Regions           []RegionView
	Jobs              []JobView
	CSRF              string
	Error             string
	// RequestsEnabled shows the "Request a region" form.
	RequestsEnabled bool
	MaxExtract      string
}

// RegionView is one catalog row.
type RegionView struct {
	ID          string
	Name        string
	Coverage    string
	Notes       string
	SourceKind  string
	SourceDate  string
	SourceSHA   string
	Disabled    bool
	Cached      bool
	CachedSize  string
	CachedAt    string
	DownloadURL string
	ActiveJob   *JobView
}

// JobView is one build-request row.
type JobView struct {
	ID              string
	RegionName      string
	State           string
	Step            string
	ProgressPercent int
	Message         string
	CreatedAt       string
	Active          bool
	Cancelable      bool
	DownloadURL     string
}

// HumanBytes formats a byte count for humans.
func HumanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}
