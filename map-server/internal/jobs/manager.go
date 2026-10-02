package jobs

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
)

// Errors returned by the manager.
var (
	ErrQueueFull      = errors.New("the build queue is full; try again later")
	ErrUnknownRegion  = errors.New("unknown region")
	ErrDisabledRegion = errors.New("region is disabled by the operator")
	ErrNotCancelable  = errors.New("job is not cancelable")
	ErrNotFound       = errors.New("job not found")
)

// Options configures admission and history.
type Options struct {
	MaxQueued  int
	MaxHistory int
	// OnCompleted, when set, runs after a successful (non-cancelled) build.
	OnCompleted func(region catalog.Region, result pipeline.Result)
}

// Manager owns the queue, the single worker, and job state.
type Manager struct {
	store   *Store
	catalog *catalog.Catalog
	gen     pipeline.Builder
	opts    Options
	log     *slog.Logger

	mu      sync.Mutex
	cancels map[string]context.CancelFunc
	wake    chan struct{}
	wg      sync.WaitGroup

	workerCancel context.CancelFunc
}

// NewManager constructs a manager. Call Start before Submit.
func NewManager(store *Store, cat *catalog.Catalog, gen pipeline.Builder, opts Options, log *slog.Logger) *Manager {
	if opts.MaxQueued <= 0 {
		opts.MaxQueued = 8
	}
	if opts.MaxHistory <= 0 {
		opts.MaxHistory = 200
	}
	if log == nil {
		log = slog.Default()
	}
	return &Manager{
		store:   store,
		catalog: cat,
		gen:     gen,
		opts:    opts,
		log:     log,
		cancels: map[string]context.CancelFunc{},
		wake:    make(chan struct{}, 1),
	}
}

// Start recovers interrupted jobs and launches the worker.
func (m *Manager) Start(ctx context.Context) error {
	recovered, err := m.store.RecoverRunning(time.Now().UTC())
	if err != nil {
		return err
	}
	for _, j := range recovered {
		m.log.Info("requeued interrupted job", "job", j.ID, "region", j.RegionID)
	}
	m.wg.Add(1)
	workerCtx, workerCancel := context.WithCancel(ctx)
	m.workerCancel = workerCancel
	go func() {
		defer m.wg.Done()
		m.worker(workerCtx)
	}()
	m.signal()
	return nil
}

// Shutdown cancels the worker and any running job, then waits.
func (m *Manager) Shutdown(ctx context.Context) error {
	m.mu.Lock()
	if m.workerCancel != nil {
		m.workerCancel()
	}
	for _, cancel := range m.cancels {
		cancel()
	}
	m.mu.Unlock()
	done := make(chan struct{})
	go func() {
		m.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (m *Manager) signal() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

// Submit admits a build request. It is idempotent for a region with an active
// job and returns the cached completed job when the artifact already exists.
func (m *Manager) Submit(ctx context.Context, regionID string) (Job, error) {
	region, err := m.catalog.Lookup(regionID)
	if err != nil {
		if errors.Is(err, catalog.ErrDisabled) {
			return Job{}, ErrDisabledRegion
		}
		return Job{}, ErrUnknownRegion
	}
	if active, ok := m.store.FindActive(region.ID); ok {
		return active, nil
	}
	if m.store.ActiveCount() >= m.opts.MaxQueued {
		return Job{}, ErrQueueFull
	}
	// Serve a completed artifact without touching the queue when the source is
	// pinned (fingerprint known up front).
	if region.Source.SHA256 != "" || region.Source.Path != "" {
		fingerprint, err := m.gen.FingerprintFor(region, "")
		if err != nil {
			return Job{}, err
		}
		if m.gen.ArtifactExists(region.ID, fingerprint) {
			if cached, ok := m.store.FindCompletedByFingerprint(region.ID, fingerprint); ok {
				if cached.ArtifactPath != "" {
					return cached, nil
				}
				// Records written before publish details were persisted lost
				// their download; repair them from the artifact on disk.
				m.fillArtifact(&cached, region.ID, fingerprint)
				if err := m.store.Upsert(cached); err != nil {
					return Job{}, err
				}
				return cached, nil
			}
			job := newJob(region)
			job.State = StateCompleted
			job.Fingerprint = fingerprint
			job.Cached = true
			job.Step = "verify"
			job.Progress = 1
			job.Message = "artifact already cached"
			job.FinishedAt = time.Now().UTC()
			m.fillArtifact(&job, region.ID, fingerprint)
			if err := m.store.Upsert(job); err != nil {
				return Job{}, err
			}
			return job, nil
		}
	}
	job := newJob(region)
	if err := m.store.Upsert(job); err != nil {
		return Job{}, err
	}
	m.signal()
	return job, nil
}

// ActiveFor returns the queued or running job for a region, if any.
func (m *Manager) ActiveFor(regionID string) (Job, bool) {
	return m.store.FindActive(regionID)
}

func (m *Manager) fillArtifact(job *Job, regionID, fingerprint string) {
	job.ArtifactPath = m.gen.ArtifactPackagePath(regionID, fingerprint)
	if info, err := os.Stat(job.ArtifactPath); err == nil {
		job.ArtifactSize = info.Size()
	}
	if sidecar, err := os.ReadFile(job.ArtifactPath + ".sha256"); err == nil {
		job.ArtifactSHA256 = strings.TrimSpace(string(sidecar))
	}
}

// Get returns a job by id.
func (m *Manager) Get(id string) (Job, bool) {
	return m.store.Get(id)
}

// QueuedCount reports jobs waiting for the worker.
func (m *Manager) QueuedCount() int { return m.store.QueuedCount() }

// MaxQueued reports the admission bound.
func (m *Manager) MaxQueued() int { return m.opts.MaxQueued }

// List returns jobs, newest first, optionally filtered by region.
func (m *Manager) List(regionID string, limit int) []Job {
	all := m.store.All()
	if limit <= 0 {
		limit = 50
	}
	out := make([]Job, 0, limit)
	for _, j := range all {
		if regionID != "" && j.RegionID != regionID {
			continue
		}
		out = append(out, j)
		if len(out) >= limit {
			break
		}
	}
	return out
}

// Cancel stops a queued or running job.
func (m *Manager) Cancel(id string) error {
	job, ok := m.store.Get(id)
	if !ok {
		return ErrNotFound
	}
	switch job.State {
	case StateQueued:
		job.State = StateCanceled
		job.Message = "cancelled before start"
		job.FinishedAt = time.Now().UTC()
		return m.store.Upsert(job)
	case StateRunning:
		job.State = StateCanceled
		job.Message = "cancelling"
		if err := m.store.Upsert(job); err != nil {
			return err
		}
		m.mu.Lock()
		cancel := m.cancels[id]
		m.mu.Unlock()
		if cancel != nil {
			cancel()
		}
		return nil
	default:
		return ErrNotCancelable
	}
}

func (m *Manager) worker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-m.wake:
		}
		for {
			job, ok := m.store.NextQueued()
			if !ok {
				break
			}
			m.runJob(ctx, job)
		}
	}
}

func (m *Manager) runJob(ctx context.Context, job Job) {
	jobCtx, cancel := context.WithCancel(ctx)
	m.mu.Lock()
	m.cancels[job.ID] = cancel
	m.mu.Unlock()
	defer func() {
		m.mu.Lock()
		delete(m.cancels, job.ID)
		m.mu.Unlock()
		cancel()
	}()

	job.State = StateRunning
	job.StartedAt = time.Now().UTC()
	job.Message = "starting"
	_ = m.store.Upsert(job)
	m.log.Info("build started", "job", job.ID, "region", job.RegionID)

	lastPersist := time.Now()
	lastStep := ""
	progress := func(p pipeline.Progress) {
		job.Step = p.Step
		job.Progress = p.Fraction
		if p.Message != "" {
			job.Message = p.Message
		}
		// A new step is always persisted so pollers see what is running now;
		// only repeated updates within one step are throttled.
		if p.Step == lastStep && time.Since(lastPersist) <= 500*time.Millisecond {
			return
		}
		// Never overwrite a cancellation that landed while the step ran.
		if current, ok := m.store.Get(job.ID); ok && current.State == StateCanceled {
			return
		}
		lastStep, lastPersist = p.Step, time.Now()
		_ = m.store.Upsert(job)
	}

	region, err := m.catalog.Lookup(job.RegionID)
	if err != nil {
		m.finish(&job, StateFailed, "region is no longer available: "+err.Error())
		return
	}
	result, err := m.gen.Build(jobCtx, region, progress)
	if err != nil {
		current, ok := m.store.Get(job.ID)
		if ok && current.State == StateCanceled {
			job.State = StateCanceled
			job.Message = "cancelled"
			job.FinishedAt = time.Now().UTC()
			_ = m.store.Upsert(job)
			m.log.Info("build cancelled", "job", job.ID, "region", job.RegionID)
			return
		}
		m.finish(&job, StateFailed, trimError(err.Error()))
		m.log.Warn("build failed", "job", job.ID, "region", job.RegionID, "error", err)
		return
	}
	job.Fingerprint = result.Fingerprint
	job.ArtifactPath = result.ArtifactPath
	job.Cached = result.Cached
	if info, err := os.Stat(result.ArtifactPath); err == nil {
		job.ArtifactSize = info.Size()
	}
	if sidecar, err := os.ReadFile(result.SidecarPath); err == nil {
		job.ArtifactSHA256 = strings.TrimSpace(string(sidecar))
	}
	job.State = StateCompleted
	job.Step = "verify"
	job.Progress = 1
	job.Message = "package ready"
	job.FinishedAt = time.Now().UTC()
	_ = m.store.Upsert(job)
	m.log.Info("build completed", "job", job.ID, "region", job.RegionID, "bytes", job.ArtifactSize)
	if m.opts.OnCompleted != nil {
		m.opts.OnCompleted(region, result)
	}
}

func (m *Manager) finish(job *Job, state State, message string) {
	job.State = state
	job.Error = message
	job.Message = message
	job.FinishedAt = time.Now().UTC()
	_ = m.store.Upsert(*job)
}

func trimError(message string) string {
	const max = 4096
	if len(message) <= max {
		return message
	}
	return message[:max] + "\n…(truncated)"
}

func newJob(region catalog.Region) Job {
	return Job{
		ID:         randomID(),
		RegionID:   region.ID,
		RegionName: region.Name,
		State:      StateQueued,
		CreatedAt:  time.Now().UTC(),
		Message:    "queued",
	}
}

func randomID() string {
	buf := make([]byte, 12)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("job-%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}
