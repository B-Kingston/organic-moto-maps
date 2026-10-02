package jobs

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	store, err := NewStore(filepath.Join(t.TempDir(), "jobs.json"), 5)
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func TestStorePersistsAndReloads(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "jobs.json")
	store, err := NewStore(path, 5)
	if err != nil {
		t.Fatal(err)
	}
	job := Job{ID: "a", RegionID: "queensland", State: StateQueued, CreatedAt: time.Unix(100, 0).UTC()}
	if err := store.Upsert(job); err != nil {
		t.Fatal(err)
	}
	reloaded, err := NewStore(path, 5)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := reloaded.Get("a")
	if !ok || got.RegionID != "queensland" || got.State != StateQueued {
		t.Fatalf("job not persisted: %+v ok=%v", got, ok)
	}
}

func TestStoreRecoversRunningJobs(t *testing.T) {
	store := testStore(t)
	if err := store.Upsert(Job{ID: "a", RegionID: "queensland", State: StateRunning, Progress: 0.5,
		CreatedAt: time.Unix(1, 0).UTC()}); err != nil {
		t.Fatal(err)
	}
	if err := store.Upsert(Job{ID: "b", RegionID: "queensland", State: StateCompleted,
		CreatedAt: time.Unix(2, 0).UTC()}); err != nil {
		t.Fatal(err)
	}
	recovered, err := store.RecoverRunning(time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if len(recovered) != 1 || recovered[0].ID != "a" {
		t.Fatalf("recovered %+v", recovered)
	}
	got, _ := store.Get("a")
	if got.State != StateQueued || got.Progress != 0 {
		t.Fatalf("running job not reset: %+v", got)
	}
	// A second recovery must be a no-op.
	again, err := store.RecoverRunning(time.Now())
	if err != nil || len(again) != 0 {
		t.Fatalf("second recovery: %v %v", again, err)
	}
}

func TestStoreFindsActiveAndCompleted(t *testing.T) {
	store := testStore(t)
	_ = store.Upsert(Job{ID: "a", RegionID: "queensland", State: StateCompleted, Fingerprint: "f1",
		CreatedAt: time.Unix(1, 0).UTC()})
	_ = store.Upsert(Job{ID: "b", RegionID: "queensland", State: StateRunning,
		CreatedAt: time.Unix(2, 0).UTC()})
	if active, ok := store.FindActive("queensland"); !ok || active.ID != "b" {
		t.Fatalf("active: %+v %v", active, ok)
	}
	if _, ok := store.FindActive("tasmania"); ok {
		t.Fatal("active job matched the wrong region")
	}
	if done, ok := store.FindCompletedByFingerprint("queensland", "f1"); !ok || done.ID != "a" {
		t.Fatalf("completed: %+v %v", done, ok)
	}
	if _, ok := store.FindCompletedByFingerprint("queensland", "f2"); ok {
		t.Fatal("unknown fingerprint matched")
	}
}

func TestStorePrunesFinishedHistoryButKeepsActive(t *testing.T) {
	store := testStore(t) // maxHistory 5
	base := time.Unix(1000, 0)
	for i := 0; i < 10; i++ {
		_ = store.Upsert(Job{
			ID:        string(rune('a' + i)),
			RegionID:  "queensland",
			State:     StateCompleted,
			CreatedAt: base.Add(time.Duration(i) * time.Second),
		})
	}
	_ = store.Upsert(Job{ID: "active", RegionID: "queensland", State: StateQueued, CreatedAt: base})
	all := store.All()
	if len(all) > 6 {
		t.Fatalf("history not pruned: %d jobs", len(all))
	}
	foundActive := false
	for _, j := range all {
		if j.ID == "active" {
			foundActive = true
		}
	}
	if !foundActive {
		t.Fatal("pruning dropped an active job")
	}
}

func TestNextQueuedIsOldestFirst(t *testing.T) {
	store := testStore(t)
	_ = store.Upsert(Job{ID: "new", RegionID: "queensland", State: StateQueued, CreatedAt: time.Unix(20, 0).UTC()})
	_ = store.Upsert(Job{ID: "old", RegionID: "queensland", State: StateQueued, CreatedAt: time.Unix(10, 0).UTC()})
	next, ok := store.NextQueued()
	if !ok || next.ID != "old" {
		t.Fatalf("next queued: %+v", next)
	}
	if store.QueuedCount() != 2 {
		t.Fatalf("queued count %d", store.QueuedCount())
	}
}

// A completed job must still advertise its download (and digest) after the
// server restarts and reloads jobs.json.
func TestCompletedJobKeepsItsDownloadAcrossReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "jobs.json")
	store, err := NewStore(path, 10)
	if err != nil {
		t.Fatal(err)
	}
	job := Job{
		ID: "done", RegionID: "monaco", RegionName: "Monaco", State: StateCompleted,
		Fingerprint: "abc", CreatedAt: time.Now().UTC(),
		ArtifactPath: "/state/artifacts/monaco/abc/monaco.motomap", ArtifactSize: 610037,
		ArtifactSHA256: "5d5541a1547b708aeb024c254daa85598dfc92a0e06b15ff17297bf466f870cb",
	}
	if err := store.Upsert(job); err != nil {
		t.Fatal(err)
	}
	reloaded, err := NewStore(path, 10)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := reloaded.Get("done")
	if !ok {
		t.Fatal("job lost on reload")
	}
	public := got.Public()
	if public.DownloadURL != "/api/v1/artifacts/monaco/abc/monaco.motomap" ||
		public.ArtifactSize != 610037 || public.ArtifactSHA256 != job.ArtifactSHA256 {
		t.Fatalf("reloaded public job = %+v", public)
	}
	encoded, _ := json.Marshal(public)
	if strings.Contains(string(encoded), "/state/artifacts") {
		t.Fatalf("public job leaks the filesystem path: %s", encoded)
	}
}
