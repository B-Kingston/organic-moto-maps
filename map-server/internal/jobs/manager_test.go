package jobs

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
)

type fakeBuilder struct {
	cfg         pipeline.Config
	fingerprint string
	cached      map[string]bool
	buildFn     func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error)
	calls       atomic.Int32
}

func newFakeBuilder(t *testing.T) *fakeBuilder {
	t.Helper()
	return &fakeBuilder{
		cfg:         pipeline.Config{ArtifactRoot: filepath.Join(t.TempDir(), "artifacts")},
		fingerprint: motomap.DigestString("fingerprint"),
		cached:      map[string]bool{},
	}
}

func (f *fakeBuilder) FingerprintFor(catalog.Region, string) (string, error) {
	return f.fingerprint, nil
}
func (f *fakeBuilder) ArtifactExists(regionID, fingerprint string) bool {
	return f.cached[regionID+"|"+fingerprint]
}
func (f *fakeBuilder) ArtifactPackagePath(regionID, fingerprint string) string {
	return filepath.Join(f.cfg.ArtifactRoot, regionID, fingerprint, regionID+".motomap")
}
func (f *fakeBuilder) Config() pipeline.Config { return f.cfg }

func (f *fakeBuilder) LatestArtifact(regionID string) (pipeline.ArtifactInfo, bool) {
	entries, err := os.ReadDir(filepath.Join(f.cfg.ArtifactRoot, regionID))
	if err != nil {
		return pipeline.ArtifactInfo{}, false
	}
	for _, e := range entries {
		pkg := f.ArtifactPackagePath(regionID, e.Name())
		if info, err := os.Stat(pkg); err == nil {
			return pipeline.ArtifactInfo{Fingerprint: e.Name(), PackagePath: pkg, Size: info.Size()}, true
		}
	}
	return pipeline.ArtifactInfo{}, false
}

func (f *fakeBuilder) Build(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
	f.calls.Add(1)
	if f.buildFn != nil {
		return f.buildFn(ctx, region, progress)
	}
	if progress != nil {
		progress(pipeline.Progress{Step: "tiles", Fraction: 0.5, Message: "halfway"})
	}
	pkg := f.ArtifactPackagePath(region.ID, f.fingerprint)
	if err := os.MkdirAll(filepath.Dir(pkg), 0o755); err != nil {
		return pipeline.Result{}, err
	}
	if err := os.WriteFile(pkg, []byte("package"), 0o644); err != nil {
		return pipeline.Result{}, err
	}
	f.cached[region.ID+"|"+f.fingerprint] = true
	return pipeline.Result{
		Fingerprint:  f.fingerprint,
		ArtifactPath: pkg,
		Manifest:     manifest.Manifest{Region: manifest.Region{ID: region.ID}},
	}, nil
}

func testCatalog() *catalog.Catalog {
	pinned := strings.Repeat("a", 64)
	return &catalog.Catalog{Regions: []catalog.Region{
		{ID: "queensland", Name: "Queensland", Source: catalog.Source{URL: "https://example.invalid/a.osm.pbf", Date: "260928", SHA256: pinned}},
		{ID: "tasmania", Name: "Tasmania", Source: catalog.Source{URL: "https://example.invalid/b.osm.pbf", Date: "260928", SHA256: pinned}},
		{ID: "broken", Name: "Broken", Disabled: true, Source: catalog.Source{URL: "https://example.invalid/c.osm.pbf", Date: "260928", SHA256: pinned}},
	}}
}

func newTestManager(t *testing.T, builder *fakeBuilder, opts Options) *Manager {
	t.Helper()
	store, err := NewStore(filepath.Join(t.TempDir(), "jobs.json"), 50)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	manager := NewManager(store, testCatalog(), builder, opts, logger)
	if err := manager.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = manager.Shutdown(ctx)
	})
	return manager
}

func waitForState(t *testing.T, manager *Manager, id string, state State) Job {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		job, ok := manager.Get(id)
		if ok && job.State == state {
			return job
		}
		time.Sleep(5 * time.Millisecond)
	}
	job, _ := manager.Get(id)
	t.Fatalf("job %s never reached %s (currently %s: %s)", id, state, job.State, job.Message)
	return Job{}
}

func TestSubmitRunsJobAndPublishesArtifact(t *testing.T) {
	builder := newFakeBuilder(t)
	manager := newTestManager(t, builder, Options{})
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	if job.State != StateQueued {
		t.Fatalf("new job state %s", job.State)
	}
	done := waitForState(t, manager, job.ID, StateCompleted)
	if done.Fingerprint != builder.fingerprint {
		t.Fatalf("fingerprint not recorded: %+v", done)
	}
	public := done.Public()
	if public.DownloadURL == "" || public.State != StateCompleted {
		t.Fatalf("public projection incomplete: %+v", public)
	}
}

func TestSubmitDeduplicatesActiveJob(t *testing.T) {
	builder := newFakeBuilder(t)
	release := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		<-release
		return pipeline.Result{Fingerprint: builder.fingerprint}, nil
	}
	manager := newTestManager(t, builder, Options{})
	first, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	second, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != second.ID {
		t.Fatalf("dedup failed: %s vs %s", first.ID, second.ID)
	}
	close(release)
	waitForState(t, manager, first.ID, StateCompleted)
	if builder.calls.Load() != 1 {
		t.Fatalf("builder called %d times", builder.calls.Load())
	}
}

func TestSubmitReturnsCachedArtifactWithoutBuilding(t *testing.T) {
	builder := newFakeBuilder(t)
	builder.cached["queensland|"+builder.fingerprint] = true
	pkg := builder.ArtifactPackagePath("queensland", builder.fingerprint)
	if err := os.MkdirAll(filepath.Dir(pkg), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pkg, []byte("cached"), 0o644); err != nil {
		t.Fatal(err)
	}
	manager := newTestManager(t, builder, Options{})
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	if job.State != StateCompleted || !job.Cached {
		t.Fatalf("cached artifact not served: %+v", job)
	}
	if builder.calls.Load() != 0 {
		t.Fatal("builder ran for a cached artifact")
	}
}

func TestSubmitRejectsUnknownDisabledAndFullQueue(t *testing.T) {
	builder := newFakeBuilder(t)
	block := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		select {
		case <-block:
		case <-ctx.Done():
		}
		return pipeline.Result{Fingerprint: builder.fingerprint}, nil
	}
	manager := newTestManager(t, builder, Options{MaxQueued: 1})
	if _, err := manager.Submit(context.Background(), "unknown"); err != ErrUnknownRegion {
		t.Fatalf("unknown region: %v", err)
	}
	if _, err := manager.Submit(context.Background(), "broken"); err != ErrDisabledRegion {
		t.Fatalf("disabled region: %v", err)
	}
	first, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	waitForState(t, manager, first.ID, StateRunning)
	if _, err := manager.Submit(context.Background(), "tasmania"); err != ErrQueueFull {
		t.Fatalf("full queue: %v", err)
	}
	close(block)
	waitForState(t, manager, first.ID, StateCompleted)
}

func TestCancelRunningJobStopsTheBuild(t *testing.T) {
	builder := newFakeBuilder(t)
	started := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		close(started)
		<-ctx.Done()
		return pipeline.Result{}, ctx.Err()
	}
	manager := newTestManager(t, builder, Options{})
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("build never started")
	}
	if err := manager.Cancel(job.ID); err != nil {
		t.Fatal(err)
	}
	waitForState(t, manager, job.ID, StateCanceled)
}

func TestCancelQueuedJobNeverStarts(t *testing.T) {
	builder := newFakeBuilder(t)
	block := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		<-block
		return pipeline.Result{Fingerprint: builder.fingerprint}, nil
	}
	manager := newTestManager(t, builder, Options{})
	first, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	waitForState(t, manager, first.ID, StateRunning)
	second, err := manager.Submit(context.Background(), "tasmania")
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Cancel(second.ID); err != nil {
		t.Fatal(err)
	}
	waitForState(t, manager, second.ID, StateCanceled)
	close(block)
	waitForState(t, manager, first.ID, StateCompleted)
}

func TestRestartRecoveryRequeuesRunningJob(t *testing.T) {
	dir := t.TempDir()
	store, err := NewStore(filepath.Join(dir, "jobs.json"), 50)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Upsert(Job{
		ID: "interrupted", RegionID: "queensland", State: StateRunning, Progress: 0.7,
		CreatedAt: time.Now().Add(-time.Minute).UTC(),
	}); err != nil {
		t.Fatal(err)
	}
	builder := newFakeBuilder(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	manager := NewManager(store, testCatalog(), builder, Options{}, logger)
	if err := manager.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = manager.Shutdown(ctx)
	}()
	waitForState(t, manager, "interrupted", StateCompleted)
}

func TestSubmitProgressAndFailureAreTruthful(t *testing.T) {
	builder := newFakeBuilder(t)
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		progress(pipeline.Progress{Step: "graph", Fraction: 0.5, Message: "halfway"})
		return pipeline.Result{}, fmt.Errorf("planetiler exploded\nwith details")
	}
	manager := newTestManager(t, builder, Options{})
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	failed := waitForState(t, manager, job.ID, StateFailed)
	if failed.Error == "" || failed.Step != "graph" {
		t.Fatalf("failure not truthful: %+v", failed)
	}
	if public := failed.Public(); public.DownloadURL != "" {
		t.Fatal("failed job advertised a download")
	}
}

func TestOnCompletedRunsOnlyForSuccessfulBuilds(t *testing.T) {
	builder := newFakeBuilder(t)
	type seen struct {
		id string
		fp string
	}
	got := make(chan seen, 4)
	manager := newTestManager(t, builder, Options{OnCompleted: func(region catalog.Region, result pipeline.Result) {
		got <- seen{region.ID, result.Fingerprint}
	}})
	ok, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	waitForState(t, manager, ok.ID, StateCompleted)
	select {
	case s := <-got:
		if s.id != "queensland" || s.fp != builder.fingerprint {
			t.Fatalf("callback args: %+v", s)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("OnCompleted not called")
	}

	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		return pipeline.Result{}, fmt.Errorf("boom")
	}
	bad, err := manager.Submit(context.Background(), "tasmania")
	if err != nil {
		t.Fatal(err)
	}
	waitForState(t, manager, bad.ID, StateFailed)
	select {
	case s := <-got:
		t.Fatalf("OnCompleted called for a failed build: %+v", s)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestActiveForReportsOnlyActiveJobs(t *testing.T) {
	builder := newFakeBuilder(t)
	release := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		<-release
		return pipeline.Result{Fingerprint: builder.fingerprint}, nil
	}
	manager := newTestManager(t, builder, Options{})
	if _, ok := manager.ActiveFor("queensland"); ok {
		t.Fatal("active job before any submit")
	}
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	if active, ok := manager.ActiveFor("queensland"); !ok || active.ID != job.ID {
		t.Fatalf("ActiveFor: %+v %v", active, ok)
	}
	if _, ok := manager.ActiveFor("tasmania"); ok {
		t.Fatal("other region reported active")
	}
	close(release)
	waitForState(t, manager, job.ID, StateCompleted)
	if _, ok := manager.ActiveFor("queensland"); ok {
		t.Fatal("completed job reported active")
	}
}

// A step that starts right after the previous one finished must still be
// visible to pollers while it runs (Planetiler can run for minutes).
func TestNewStepIsPersistedImmediately(t *testing.T) {
	builder := newFakeBuilder(t)
	inTiles := make(chan struct{})
	release := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		progress(pipeline.Progress{Step: "source", Fraction: 0.02, Message: "source ready"})
		progress(pipeline.Progress{Step: "tiles", Fraction: 0.02, Message: "generating vector tiles"})
		close(inTiles)
		<-release
		return pipeline.Result{}, fmt.Errorf("stop")
	}
	manager := newTestManager(t, builder, Options{})
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	<-inTiles
	got, _ := manager.Get(job.ID)
	close(release)
	if got.Step != "tiles" || got.Message != "generating vector tiles" {
		t.Fatalf("poller sees step %q / %q while tiles run", got.Step, got.Message)
	}
	waitForState(t, manager, job.ID, StateFailed)
}

// A progress report arriving after Cancel must not resurrect the job as
// running, or the build error would be recorded as a failure.
func TestProgressAfterCancelKeepsTheJobCanceled(t *testing.T) {
	builder := newFakeBuilder(t)
	started := make(chan struct{})
	builder.buildFn = func(ctx context.Context, region catalog.Region, progress pipeline.ProgressFunc) (pipeline.Result, error) {
		close(started)
		<-ctx.Done()
		progress(pipeline.Progress{Step: "graph", Fraction: 0.5, Message: "late report"})
		return pipeline.Result{}, ctx.Err()
	}
	manager := newTestManager(t, builder, Options{})
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	<-started
	waitForState(t, manager, job.ID, StateRunning)
	if err := manager.Cancel(job.ID); err != nil {
		t.Fatal(err)
	}
	final := waitForState(t, manager, job.ID, StateCanceled)
	time.Sleep(50 * time.Millisecond)
	if again, _ := manager.Get(job.ID); again.State != StateCanceled || final.Error != "" {
		t.Fatalf("cancelled job ended as %s (error %q)", again.State, again.Error)
	}
}

// A completed record from before publish details were persisted is repaired
// on the next cache hit instead of being served without a download.
func TestCachedHitRepairsALegacyRecordWithoutArtifactDetails(t *testing.T) {
	builder := newFakeBuilder(t)
	builder.cached["queensland|"+builder.fingerprint] = true
	pkg := builder.ArtifactPackagePath("queensland", builder.fingerprint)
	if err := os.MkdirAll(filepath.Dir(pkg), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pkg, []byte("cached"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pkg+".sha256", []byte("feed\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	manager := newTestManager(t, builder, Options{})
	legacy := Job{ID: "legacy", RegionID: "queensland", RegionName: "Queensland", State: StateCompleted,
		Fingerprint: builder.fingerprint, CreatedAt: time.Now().UTC()}
	if err := manager.store.Upsert(legacy); err != nil {
		t.Fatal(err)
	}
	job, err := manager.Submit(context.Background(), "queensland")
	if err != nil {
		t.Fatal(err)
	}
	public := job.Public()
	if job.ID != "legacy" || public.DownloadURL == "" || public.ArtifactSize != 6 || public.ArtifactSHA256 != "feed" {
		t.Fatalf("legacy record not repaired: %+v", public)
	}
	if stored, _ := manager.Get("legacy"); stored.ArtifactPath != pkg {
		t.Fatalf("repair not persisted: %+v", stored)
	}
}
