package jobs

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Store persists the job history as one JSON document, written atomically
// (temp file + fsync + rename) so a crash never truncates the queue.
type Store struct {
	path       string
	maxHistory int

	mu   sync.Mutex
	jobs []Job
}

type storeDoc struct {
	Jobs []Job `json:"jobs"`
}

// NewStore opens (or creates) the job store at path.
func NewStore(path string, maxHistory int) (*Store, error) {
	if maxHistory <= 0 {
		maxHistory = 200
	}
	s := &Store{path: path, maxHistory: maxHistory}
	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) load() error {
	data, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var doc storeDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		return fmt.Errorf("decode job store %s: %w", s.path, err)
	}
	s.jobs = doc.Jobs
	return nil
}

// Save writes the current history atomically.
func (s *Store) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

func (s *Store) saveLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	doc, err := json.MarshalIndent(storeDoc{Jobs: s.jobs}, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, doc, 0o644); err != nil {
		return err
	}
	if f, err := os.Open(tmp); err == nil {
		_ = f.Sync()
		_ = f.Close()
	}
	return os.Rename(tmp, s.path)
}

// All returns a copy of the job list, newest first.
func (s *Store) All() []Job {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := append([]Job(nil), s.jobs...)
	sort.SliceStable(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return out
}

// Get returns one job by id.
func (s *Store) Get(id string) (Job, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, j := range s.jobs {
		if j.ID == id {
			return j, true
		}
	}
	return Job{}, false
}

// FindActive returns the newest queued/running job, optionally filtered by
// region (empty region matches any).
func (s *Store) FindActive(regionID string) (Job, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := len(s.jobs) - 1; i >= 0; i-- {
		j := s.jobs[i]
		if !j.Active() {
			continue
		}
		if regionID == "" || j.RegionID == regionID {
			return j, true
		}
	}
	return Job{}, false
}

// FindCompletedByFingerprint returns a completed job for the fingerprint.
func (s *Store) FindCompletedByFingerprint(regionID, fingerprint string) (Job, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := len(s.jobs) - 1; i >= 0; i-- {
		j := s.jobs[i]
		if j.State == StateCompleted && j.RegionID == regionID && j.Fingerprint == fingerprint {
			return j, true
		}
	}
	return Job{}, false
}

// Upsert inserts or replaces a job and persists the store.
func (s *Store) Upsert(job Job) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	found := false
	for i := range s.jobs {
		if s.jobs[i].ID == job.ID {
			s.jobs[i] = job
			found = true
			break
		}
	}
	if !found {
		s.jobs = append(s.jobs, job)
	}
	s.pruneLocked()
	return s.saveLocked()
}

func (s *Store) pruneLocked() {
	if len(s.jobs) <= s.maxHistory {
		return
	}
	// Keep all active jobs plus the newest completed history entries.
	var active, finished []Job
	for _, j := range s.jobs {
		if j.Active() {
			active = append(active, j)
		} else {
			finished = append(finished, j)
		}
	}
	sort.SliceStable(finished, func(i, j int) bool { return finished[i].CreatedAt.After(finished[j].CreatedAt) })
	keep := s.maxHistory - len(active)
	if keep < 0 {
		keep = 0
	}
	if keep > len(finished) {
		keep = len(finished)
	}
	s.jobs = append(active, finished[:keep]...)
}

// RecoverRunning converts interrupted running jobs back to queued. Called once
// at startup, before the worker starts.
func (s *Store) RecoverRunning(now time.Time) ([]Job, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var recovered []Job
	for i := range s.jobs {
		if s.jobs[i].State == StateRunning {
			s.jobs[i].State = StateQueued
			s.jobs[i].Step = ""
			s.jobs[i].Progress = 0
			s.jobs[i].Message = "requeued after server restart"
			s.jobs[i].StartedAt = time.Time{}
			recovered = append(recovered, s.jobs[i])
		}
	}
	if len(recovered) == 0 {
		return nil, nil
	}
	return recovered, s.saveLocked()
}

// QueuedCount counts jobs waiting for the worker.
func (s *Store) QueuedCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, j := range s.jobs {
		if j.State == StateQueued {
			n++
		}
	}
	return n
}

// ActiveCount counts queued plus running jobs (the admission bound).
func (s *Store) ActiveCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, j := range s.jobs {
		if j.Active() {
			n++
		}
	}
	return n
}

// NextQueued returns the oldest queued job.
func (s *Store) NextQueued() (Job, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best *Job
	for i := range s.jobs {
		j := s.jobs[i]
		if j.State != StateQueued {
			continue
		}
		if best == nil || j.CreatedAt.Before(best.CreatedAt) {
			copy := j
			best = &copy
		}
	}
	if best == nil {
		return Job{}, false
	}
	return *best, true
}
