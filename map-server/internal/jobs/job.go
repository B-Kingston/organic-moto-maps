// Package jobs owns the persistent build-request queue: bounded admission,
// deduplication, restart recovery, truthful progress, cancellation, and the
// immutable artifact cache.
package jobs

import (
	"fmt"
	"time"
)

// State is a job's lifecycle state.
type State string

// Job states.
const (
	StateQueued    State = "queued"
	StateRunning   State = "running"
	StateCompleted State = "completed"
	StateFailed    State = "failed"
	StateCanceled  State = "canceled"
)

// Job is one build request.
type Job struct {
	ID          string    `json:"id"`
	RegionID    string    `json:"regionId"`
	RegionName  string    `json:"regionName"`
	Fingerprint string    `json:"fingerprint,omitempty"`
	State       State     `json:"state"`
	Step        string    `json:"step,omitempty"`
	Progress    float64   `json:"progress"`
	Message     string    `json:"message,omitempty"`
	Error       string    `json:"error,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
	StartedAt   time.Time `json:"startedAt,omitempty"`
	FinishedAt  time.Time `json:"finishedAt,omitempty"`
	Cached      bool      `json:"cached,omitempty"`

	// Publish details. They are persisted in jobs.json so a completed job
	// still advertises its download after a restart, but clients only ever
	// see Public(), which never exposes the filesystem path.
	ArtifactPath   string `json:"artifactPath,omitempty"`
	ArtifactSize   int64  `json:"artifactSize,omitempty"`
	ArtifactSHA256 string `json:"artifactSha256,omitempty"`
}

// Public is the client-facing projection: no filesystem paths, just a
// relative download URL once the artifact exists.
type Public struct {
	ID             string     `json:"id"`
	RegionID       string     `json:"regionId"`
	RegionName     string     `json:"regionName"`
	Fingerprint    string     `json:"fingerprint,omitempty"`
	State          State      `json:"state"`
	Step           string     `json:"step,omitempty"`
	Progress       float64    `json:"progress"`
	Message        string     `json:"message,omitempty"`
	Error          string     `json:"error,omitempty"`
	CreatedAt      time.Time  `json:"createdAt"`
	StartedAt      *time.Time `json:"startedAt,omitempty"`
	FinishedAt     *time.Time `json:"finishedAt,omitempty"`
	Cached         bool       `json:"cached,omitempty"`
	ArtifactSize   int64      `json:"artifactSize,omitempty"`
	ArtifactSHA256 string     `json:"artifactSha256,omitempty"`
	DownloadURL    string     `json:"downloadUrl,omitempty"`
}

// Public projects a job for API clients.
func (j Job) Public() Public {
	p := Public{
		ID:             j.ID,
		RegionID:       j.RegionID,
		RegionName:     j.RegionName,
		Fingerprint:    j.Fingerprint,
		State:          j.State,
		Step:           j.Step,
		Progress:       j.Progress,
		Message:        j.Message,
		Error:          j.Error,
		CreatedAt:      j.CreatedAt,
		Cached:         j.Cached,
		ArtifactSize:   j.ArtifactSize,
		ArtifactSHA256: j.ArtifactSHA256,
	}
	if !j.StartedAt.IsZero() {
		t := j.StartedAt
		p.StartedAt = &t
	}
	if !j.FinishedAt.IsZero() {
		t := j.FinishedAt
		p.FinishedAt = &t
	}
	if j.State == StateCompleted && j.ArtifactPath != "" {
		p.DownloadURL = fmt.Sprintf("/api/v1/artifacts/%s/%s/%s.motomap", j.RegionID, j.Fingerprint, j.RegionID)
	}
	return p
}

// Active reports whether the job is still queued or running.
func (j Job) Active() bool {
	return j.State == StateQueued || j.State == StateRunning
}
