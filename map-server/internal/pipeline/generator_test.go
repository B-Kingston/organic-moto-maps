package pipeline

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"testing"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
)

// repoRoot locates the checkout for tests that read the canonical pipeline
// inputs (tools/gh/config.yml and the geocoder sources).
func repoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Skip("cannot locate the test file")
	}
	dir := filepath.Dir(file)
	for {
		if _, err := os.Stat(filepath.Join(dir, "tools", "gh", "config.yml")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Skip("repository root not found; skipping pipeline test")
		}
		dir = parent
	}
}

// graphLocationValue extracts the quoted graph.location value from a generated
// config so the stub can write the graph files where the generator looks.
var graphLocationValue = regexp.MustCompile(`(?m)^\s*graph\.location:\s*"?([^"\n]+)"?\s*$`)

// stubRunner emulates Planetiler, GraphHopper, and the geocoder tool, writing
// structurally valid outputs, and records the commands it was asked to run.
type stubRunner struct {
	mu        sync.Mutex
	commands  []Command
	blockOn   string
	onRun     func(Command) error
	failGraph bool
}

func (s *stubRunner) Run(ctx context.Context, cmd Command) error {
	s.mu.Lock()
	s.commands = append(s.commands, cmd)
	s.mu.Unlock()
	if s.onRun != nil {
		if err := s.onRun(cmd); err != nil {
			return err
		}
	}
	if s.blockOn != "" && strings.Contains(strings.Join(append([]string{cmd.Name}, cmd.Args...), " "), s.blockOn) {
		<-ctx.Done()
		return ctx.Err()
	}
	env := map[string]string{}
	for _, kv := range cmd.Env {
		if i := strings.IndexByte(kv, '='); i > 0 {
			env[kv[:i]] = kv[i+1:]
		}
	}
	switch {
	case cmd.Name == "bash": // Planetiler
		out := env["TILE_OUT"]
		pmtiles := append([]byte("PMTiles"), 3)
		pmtiles = append(pmtiles, make([]byte, 127-len(pmtiles))...)
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			return err
		}
		return os.WriteFile(out, pmtiles, 0o644)
	case strings.Contains(cmd.Name, "java"): // GraphHopper import
		if s.failGraph {
			return &exitError{"graph import failed"}
		}
		configPath := cmd.Args[len(cmd.Args)-1]
		raw, err := os.ReadFile(configPath)
		if err != nil {
			return err
		}
		match := graphLocationValue.FindSubmatch(raw)
		if len(match) != 2 {
			return &exitError{"no graph.location in generated config"}
		}
		graphDir := strings.TrimSpace(string(match[1]))
		if err := os.MkdirAll(graphDir, 0o755); err != nil {
			return err
		}
		for name, content := range map[string]string{
			"properties": "graph.profiles=motorcycle|198752012\n",
			"nodes":      "stub nodes",
			"edges":      "stub edges",
		} {
			if err := os.WriteFile(filepath.Join(graphDir, name), []byte(content), 0o644); err != nil {
				return err
			}
		}
		return nil
	case strings.HasSuffix(cmd.Name, "geocoder-tool"): // geocoder build or validate
		if len(cmd.Args) > 0 && cmd.Args[0] == "--validate" {
			raw, err := os.ReadFile(cmd.Args[1])
			if err != nil {
				return err
			}
			if !strings.HasPrefix(string(raw), manifest.GeocoderMagic) {
				return &exitError{"bad geocoder magic"}
			}
			return nil
		}
		outDir := cmd.Args[1]
		if err := os.MkdirAll(outDir, 0o755); err != nil {
			return err
		}
		data := append([]byte(manifest.GeocoderMagic), make([]byte, 128)...)
		return os.WriteFile(filepath.Join(outDir, "geocoder.dat"), data, 0o644)
	default:
		return &exitError{"unexpected command " + cmd.Name}
	}
}

type exitError struct{ msg string }

func (e *exitError) Error() string { return e.msg }

func stubGenerator(t *testing.T) (*Generator, *stubRunner, string) {
	t.Helper()
	root := repoRoot(t)
	repo := t.TempDir()
	state := filepath.Join(t.TempDir(), "state")
	// Fake the pinned inputs so ApplyDefaults() succeeds; the stub runner
	// never executes them.
	for _, rel := range []string{
		"tools/tiles/build-tiles.sh",
		"tools/tiles/planetiler.jar",
		"tools/gh/graphhopper-web-11.0.jar",
		"tools/gh/motorcycle.json",
		"tools/gh/MotoGraphImport.java",
		"gradlew",
	} {
		full := filepath.Join(repo, rel)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte("stub"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	canonical, err := os.ReadFile(filepath.Join(root, "tools", "gh", "config.yml"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(repo, "tools", "gh"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, "tools", "gh", "config.yml"), canonical, 0o644); err != nil {
		t.Fatal(err)
	}
	// The geocoder sources feed ToolHashes; copy the real ones.
	if err := copyTree(filepath.Join(root, "geocoder-tool", "src"), filepath.Join(repo, "geocoder-tool", "src")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, "geocoder-tool", "build.gradle.kts"), []byte("// stub"), 0o644); err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(repo, "geocoder-tool", "build", "install", "geocoder-tool", "bin", "geocoder-tool")
	if err := os.MkdirAll(filepath.Dir(launcher), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(launcher, []byte("stub"), 0o755); err != nil {
		t.Fatal(err)
	}
	// A tiny but valid PBF: length-prefixed OSMHeader blob.
	source := filepath.Join(t.TempDir(), "tiny.osm.pbf")
	pbf := []byte("OSMHeader-stub")
	prefix := []byte{0, 0, 0, byte(len(pbf))}
	if err := os.WriteFile(source, append(prefix, pbf...), 0o644); err != nil {
		t.Fatal(err)
	}

	cfg := DefaultConfig(repo, state)
	cfg.Java17 = defaultJava17()
	cfg.Java21 = defaultJava21()
	if javaMajor(cfg.Java17) < 17 || javaMajor(cfg.Java21) < 21 {
		t.Skip("no suitable JDK available for the stub pipeline test")
	}
	cfg.MinFreeDiskBytes = 1
	if err := cfg.ApplyDefaults(); err != nil {
		t.Fatalf("stub pipeline config: %v", err)
	}
	runner := &stubRunner{}
	gen := &Generator{Cfg: cfg, Runner: runner}
	return gen, runner, source
}

func testRegion(sourcePath string) catalog.Region {
	return catalog.Region{
		ID:       "queensland",
		Name:     "Queensland",
		Coverage: "Queensland, Australia",
		Source:   catalog.Source{Path: sourcePath, Date: "260928"},
	}
}

func copyTree(from, to string) error {
	return filepath.WalkDir(from, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(from, path)
		if err != nil {
			return err
		}
		target := filepath.Join(to, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, 0o644)
	})
}

func TestBuildEndToEndWithStubProcesses(t *testing.T) {
	gen, runner, source := stubGenerator(t)
	region := testRegion(source)
	var steps []string
	result, err := gen.Build(context.Background(), region, func(p Progress) {
		steps = append(steps, p.Step)
	})
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if result.Cached {
		t.Fatal("first build reported cached")
	}
	mf, err := motomap.Verify(result.ArtifactPath)
	if err != nil {
		t.Fatalf("published package does not verify: %v", err)
	}
	if mf.Region.ID != "queensland" || mf.Source.Date != "260928" {
		t.Fatalf("manifest region/source wrong: %+v", mf)
	}
	if mf.Compatibility.GraphProfile != gen.Cfg.ExpectedGraphProfile {
		t.Fatalf("graph profile: %s", mf.Compatibility.GraphProfile)
	}
	graph, ok := mf.Component(manifest.ComponentGraph)
	if !ok || len(graph.Files) != 3 {
		t.Fatalf("graph component: %+v", graph)
	}
	if _, err := os.Stat(result.ArtifactPath + ".sha256"); err != nil {
		t.Fatalf("sidecar missing: %v", err)
	}
	// The runner must have run exactly the pinned tools: Planetiler, the
	// GraphHopper import, the geocoder build, and the geocoder's own
	// structural validation.
	var names []string
	for _, cmd := range runner.commands {
		names = append(names, filepath.Base(cmd.Name))
	}
	if len(runner.commands) != 4 {
		t.Fatalf("expected 4 external commands, got %d (%v)", len(runner.commands), names)
	}
	// Progress must be monotonic and end at 1.
	last := 0.0
	for _, p := range progressFractions(gen, region, t) {
		if p < last {
			t.Fatalf("progress went backwards: %v", p)
		}
		last = p
	}
	if last != 1 {
		t.Fatalf("progress ended at %v, want 1", last)
	}

	// A second build must be served from the artifact cache.
	before := len(runner.commands)
	again, err := gen.Build(context.Background(), region, nil)
	if err != nil {
		t.Fatalf("cached build: %v", err)
	}
	if !again.Cached {
		t.Fatal("second build was not cached")
	}
	if len(runner.commands) != before {
		t.Fatal("cached build re-ran external commands")
	}
}

// progressFractions runs a build against a fresh generator and returns the
// reported fractions.
func progressFractions(gen *Generator, region catalog.Region, t *testing.T) []float64 {
	t.Helper()
	fresh, _, source := stubGenerator(t)
	region.Source.Path = source
	var out []float64
	if _, err := fresh.Build(context.Background(), region, func(p Progress) {
		out = append(out, p.Fraction)
	}); err != nil {
		t.Fatalf("progress build: %v", err)
	}
	return out
}

func TestGeocoderHeapIsAppliedToTheLauncher(t *testing.T) {
	gen, runner, source := stubGenerator(t)
	gen.Cfg.GeocoderHeap = "3g"
	region := testRegion(source)
	if _, err := gen.Build(context.Background(), region, nil); err != nil {
		t.Fatalf("build: %v", err)
	}
	var launcherEnvs []map[string]string
	for _, cmd := range runner.commands {
		if !strings.HasSuffix(cmd.Name, "geocoder-tool") {
			continue
		}
		env := map[string]string{}
		for _, kv := range cmd.Env {
			if i := strings.IndexByte(kv, '='); i > 0 {
				env[kv[:i]] = kv[i+1:]
			}
		}
		launcherEnvs = append(launcherEnvs, env)
	}
	if len(launcherEnvs) != 2 {
		t.Fatalf("expected the build and validate launcher runs, got %d", len(launcherEnvs))
	}
	for _, env := range launcherEnvs {
		if env["JAVA_OPTS"] != "-Xmx3g" {
			t.Fatalf("geocoder heap not applied to the launcher: JAVA_OPTS=%q", env["JAVA_OPTS"])
		}
		if env["JAVA_HOME"] == "" {
			t.Fatal("geocoder launcher JAVA_HOME missing")
		}
	}
}

func TestBuildFailureIsReported(t *testing.T) {
	gen, runner, source := stubGenerator(t)
	runner.failGraph = true
	region := testRegion(source)
	if _, err := gen.Build(context.Background(), region, nil); err == nil {
		t.Fatal("build succeeded despite a failing graph step")
	}
	if _, err := os.Stat(gen.ArtifactPackagePath("queensland", motomap.DigestString("x"))); err == nil {
		t.Fatal("failed build left an artifact")
	}
}

func TestBuildCancellationStopsThePipeline(t *testing.T) {
	gen, runner, source := stubGenerator(t)
	runner.blockOn = "build-tiles.sh"
	region := testRegion(source)
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		// Let the build reach the blocked step, then cancel.
		for {
			runner.mu.Lock()
			n := len(runner.commands)
			runner.mu.Unlock()
			if n > 0 {
				cancel()
				return
			}
		}
	}()
	if _, err := gen.Build(ctx, region, nil); err == nil {
		t.Fatal("cancelled build reported success")
	}
}

func TestGenerateGraphConfigOnlyRewritesLocations(t *testing.T) {
	root := repoRoot(t)
	canonical, err := os.ReadFile(filepath.Join(root, "tools", "gh", "config.yml"))
	if err != nil {
		t.Fatal(err)
	}
	out, err := GenerateGraphConfig(canonical, "/work/source.osm.pbf", "/work/graph-cache")
	if err != nil {
		t.Fatal(err)
	}
	original := strings.Split(strings.TrimRight(string(canonical), "\n"), "\n")
	rewritten := strings.Split(strings.TrimRight(string(out), "\n"), "\n")
	if len(original) != len(rewritten) {
		t.Fatalf("line count changed: %d -> %d", len(original), len(rewritten))
	}
	changed := map[int]bool{}
	for i := range original {
		if original[i] != rewritten[i] {
			changed[i] = true
		}
	}
	if len(changed) != 2 {
		t.Fatalf("expected exactly two changed lines, got %d: %v", len(changed), changed)
	}
	for i := range original {
		trimmed := strings.TrimSpace(original[i])
		if strings.HasPrefix(trimmed, "custom_model_files:") ||
			strings.HasPrefix(trimmed, "import.osm.ignored_highways:") ||
			strings.HasPrefix(trimmed, "graph.encoded_values:") ||
			strings.HasPrefix(trimmed, "- name:") {
			if changed[i] {
				t.Fatalf("profile-defining line %d was rewritten: %q", i, original[i])
			}
		}
	}
	if !strings.Contains(string(out), `datareader.file: "/work/source.osm.pbf"`) ||
		!strings.Contains(string(out), `graph.location: "/work/graph-cache"`) {
		t.Fatalf("locations not rewritten:\n%s", out)
	}
}

func TestGenerateGraphConfigRejectsBrokenInput(t *testing.T) {
	if _, err := GenerateGraphConfig([]byte("graphhopper:\n  version: 1\n"), "a", "b"); err == nil {
		t.Fatal("config without location keys accepted")
	}
	dup := "  datareader.file: a\n  datareader.file: b\n  graph.location: c\n"
	if _, err := GenerateGraphConfig([]byte(dup), "a", "b"); err == nil {
		t.Fatal("duplicated datareader.file accepted")
	}
}

func TestFingerprintReflectsInputs(t *testing.T) {
	gen, _, source := stubGenerator(t)
	region := testRegion(source)
	first, err := gen.FingerprintFor(region, "")
	if err != nil {
		t.Fatal(err)
	}
	if again, _ := gen.FingerprintFor(region, ""); again != first {
		t.Fatal("fingerprint is not deterministic")
	}
	other := region
	other.Source.Date = "260101"
	second, _ := gen.FingerprintFor(other, "")
	if second == first {
		t.Fatal("fingerprint ignored the source date")
	}
	other = region
	other.ID = "tasmania"
	third, _ := gen.FingerprintFor(other, "")
	if third == first {
		t.Fatal("fingerprint ignored the region")
	}
	observed, _ := gen.FingerprintFor(region, motomap.DigestString("bytes"))
	if observed == first {
		t.Fatal("fingerprint ignored the observed source hash")
	}
}

func TestToolHashesCoverGeocoderSources(t *testing.T) {
	root := repoRoot(t)
	hashes, err := ToolHashes(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"tools/gh/config.yml",
		"tools/gh/motorcycle.json",
		"tools/tiles/build-tiles.sh",
		"geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/GeocoderIndexBuilder.kt",
		"geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt",
		"geocoder-tool/src/main/resources/poi_keywords.tsv",
	} {
		if hashes[want] == "" {
			t.Fatalf("tool hash missing for %s", want)
		}
	}
	again, _ := ToolHashes(root)
	for key, value := range hashes {
		if again[key] != value {
			t.Fatalf("tool hash unstable for %s", key)
		}
	}
}

func TestStatSourceRejectsNonPBF(t *testing.T) {
	dir := t.TempDir()
	html := filepath.Join(dir, "index.html")
	if err := os.WriteFile(html, []byte("<html>Geofabrik index</html>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := statSource(html); err == nil {
		t.Fatal("HTML accepted as a source extract")
	}
}

// The graph step must run the lane-aware importer, never the stock
// `-jar ... import` command that would silently drop lane guidance.
func TestGraphStepRunsTheLaneAwareImporter(t *testing.T) {
	gen, runner, source := stubGenerator(t)
	if _, err := gen.Build(context.Background(), testRegion(source), nil); err != nil {
		t.Fatalf("build: %v", err)
	}
	var graph *Command
	for i := range runner.commands {
		if strings.Contains(runner.commands[i].Name, "java") {
			graph = &runner.commands[i]
		}
	}
	if graph == nil {
		t.Fatal("no GraphHopper import command was run")
	}
	args := graph.Args
	if len(args) != 6 || args[1] != "-cp" || args[2] != gen.Cfg.GraphHopperJar ||
		args[3] != gen.Cfg.GraphImport || args[4] != "import" {
		t.Fatalf("graph import args = %q", args)
	}
	if !strings.HasSuffix(gen.Cfg.GraphImport, "tools/gh/MotoGraphImport.java") {
		t.Fatalf("graph importer = %s", gen.Cfg.GraphImport)
	}
	for _, a := range args {
		if a == "-jar" {
			t.Fatalf("graph import must not use the stock jar command: %q", args)
		}
	}
}
