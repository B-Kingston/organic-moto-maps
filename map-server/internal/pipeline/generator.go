package pipeline

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
)

// Progress is one honest progress report from a running build.
type Progress struct {
	Step     string
	Fraction float64
	Message  string
}

// ProgressFunc receives progress reports; it must not block.
type ProgressFunc func(Progress)

// Result is a finished (or cached) package build.
type Result struct {
	Fingerprint  string
	ArtifactPath string
	SidecarPath  string
	Manifest     manifest.Manifest
	Source       SourceInfo
	Cached       bool
}

// Generator runs the pinned generation pipeline.
type Generator struct {
	Cfg    Config
	Runner CommandRunner
	Logf   func(string, ...any)
	// HTTPTransport overrides the source download transport (tests); nil
	// uses the default.
	HTTPTransport http.RoundTripper
}

// Builder is the generation surface the queue and API depend on. The concrete
// *Generator implements it; tests substitute a fake.
type Builder interface {
	Build(ctx context.Context, region catalog.Region, progress ProgressFunc) (Result, error)
	FingerprintFor(region catalog.Region, observedSourceSHA string) (string, error)
	ArtifactExists(regionID, fingerprint string) bool
	ArtifactPackagePath(regionID, fingerprint string) string
	LatestArtifact(regionID string) (ArtifactInfo, bool)
	Config() Config
}

// New builds a generator with the executable runner.
func New(cfg Config) *Generator {
	return &Generator{Cfg: cfg, Runner: ExecRunner{}}
}

// Config returns the generator's configuration.
func (g *Generator) Config() Config { return g.Cfg }

func (g *Generator) logf(format string, args ...any) {
	if g.Logf != nil {
		g.Logf(format, args...)
	}
}

func report(progress ProgressFunc, step string, fraction float64, format string, args ...any) {
	if progress != nil {
		progress(Progress{Step: step, Fraction: fraction, Message: fmt.Sprintf(format, args...)})
	}
}

// Step weights sum to 1.
var stepWeights = map[string]float64{
	"source":   0.02,
	"tiles":    0.40,
	"graph":    0.30,
	"geocoder": 0.20,
	"package":  0.06,
	"verify":   0.02,
}

var stepOrder = []string{"source", "tiles", "graph", "geocoder", "package", "verify"}

func fractionAfter(step string) float64 {
	var sum float64
	for _, s := range stepOrder {
		sum += stepWeights[s]
		if s == step {
			return sum
		}
	}
	return 1
}

// Build produces a complete package for region, reusing a verified cached
// artifact when the fingerprint matches. The caller supplies cancellation via
// ctx; the runner kills process groups on cancel.
func (g *Generator) Build(ctx context.Context, region catalog.Region, progress ProgressFunc) (Result, error) {
	if err := g.Cfg.ApplyDefaults(); err != nil {
		return Result{}, err
	}
	if err := g.CheckResources(); err != nil {
		return Result{}, err
	}
	if maj := javaMajor(g.Cfg.Java17); maj < 17 {
		return Result{}, fmt.Errorf("GraphHopper needs JDK 17+; %s reports major version %d", g.Cfg.Java17, maj)
	}
	if maj := javaMajor(g.Cfg.Java21); maj < 21 {
		return Result{}, fmt.Errorf("Planetiler needs JDK 21+; %s reports major version %d", g.Cfg.Java21, maj)
	}

	// A pinned source hash lets us compute the fingerprint (and serve a cache
	// hit) without downloading hundreds of megabytes.
	observed := ""
	if region.Source.Path != "" || region.Source.SHA256 == "" {
		if region.Source.Path != "" {
			info, err := statSource(absSource(g.Cfg.RepoRoot, region.Source.Path))
			if err != nil {
				return Result{}, err
			}
			observed = info.SHA256
		}
	}
	fingerprint, err := g.FingerprintFor(region, observed)
	if err != nil {
		return Result{}, err
	}
	report(progress, "source", 0, "fingerprint %s", fingerprint[:12])
	if g.ArtifactExists(region.ID, fingerprint) {
		result, err := g.cachedResult(region.ID, fingerprint)
		if err != nil {
			return Result{}, err
		}
		report(progress, "verify", 1, "using cached package")
		g.logf("cache hit for %s (%s)", region.ID, fingerprint[:12])
		return result, nil
	}

	workDir := filepath.Join(g.Cfg.WorkRoot, fingerprint)
	if err := os.RemoveAll(workDir); err != nil {
		return Result{}, err
	}
	if err := os.MkdirAll(workDir, 0o755); err != nil {
		return Result{}, err
	}
	keep := g.Cfg.KeepWork
	defer func() {
		if !keep {
			_ = os.RemoveAll(workDir)
		}
	}()

	// 1. Source extract.
	report(progress, "source", 0, "resolving OSM extract")
	source, err := g.EnsureSource(ctx, region, workDir, g.logf)
	if err != nil {
		return Result{}, err
	}
	if observed == "" {
		// An unpinned URL source: re-derive the fingerprint from the bytes we
		// actually downloaded so the artifact key cannot lie.
		fingerprint, err = g.FingerprintFor(region, source.SHA256)
		if err != nil {
			return Result{}, err
		}
		if g.ArtifactExists(region.ID, fingerprint) {
			result, err := g.cachedResult(region.ID, fingerprint)
			if err != nil {
				return Result{}, err
			}
			report(progress, "verify", 1, "using cached package")
			return result, nil
		}
		workDir = filepath.Join(g.Cfg.WorkRoot, fingerprint)
		if err := os.MkdirAll(workDir, 0o755); err != nil {
			return Result{}, err
		}
		// Re-resolve the source into the new work directory (it may already be
		// there if the download landed in the old directory).
		if source.Path != filepath.Join(workDir, "source.osm.pbf") {
			if err := os.Rename(source.Path, filepath.Join(workDir, "source.osm.pbf")); err != nil {
				return Result{}, err
			}
			source.Path = filepath.Join(workDir, "source.osm.pbf")
		}
	}
	report(progress, "source", fractionAfter("source"), "source ready (%s)", source.SHA256[:12])

	// 2. Vector tiles.
	report(progress, "tiles", fractionAfter("source"), "generating vector tiles (Planetiler)")
	tileOut := filepath.Join(workDir, "tiles", "basemap.pmtiles")
	if err := g.runTiles(ctx, region, source, workDir, tileOut); err != nil {
		return Result{}, err
	}
	report(progress, "tiles", fractionAfter("tiles"), "vector tiles ready")

	// 3. Routing graph.
	report(progress, "graph", fractionAfter("tiles"), "importing routing graph (GraphHopper)")
	graphDir := filepath.Join(workDir, "graph-cache")
	if err := g.runGraph(ctx, region, source, workDir, graphDir); err != nil {
		return Result{}, err
	}
	report(progress, "graph", fractionAfter("graph"), "routing graph ready")

	// 4. Geocoder index.
	report(progress, "geocoder", fractionAfter("graph"), "building offline geocoder index")
	geocoderOut := filepath.Join(workDir, "geocoder")
	if err := g.runGeocoder(ctx, source, workDir, geocoderOut); err != nil {
		return Result{}, err
	}
	report(progress, "geocoder", fractionAfter("geocoder"), "geocoder index ready")

	// 5. Package.
	report(progress, "package", fractionAfter("geocoder"), "assembling package")
	pkgPath, mf, err := g.writePackage(region, source, fingerprint, workDir, tileOut, graphDir, geocoderOut)
	if err != nil {
		return Result{}, err
	}
	report(progress, "package", fractionAfter("package"), "package assembled")

	// 6. Verify and publish.
	report(progress, "verify", fractionAfter("package"), "verifying package")
	if g.Cfg.VerifyArchive {
		if _, err := motomap.Verify(pkgPath); err != nil {
			return Result{}, fmt.Errorf("package verification failed: %w", err)
		}
	}
	if err := g.publish(region.ID, fingerprint, pkgPath, mf); err != nil {
		return Result{}, err
	}
	sidecar := g.ArtifactPackagePath(region.ID, fingerprint) + ".sha256"
	report(progress, "verify", 1, "package published")
	g.logf("built %s for %s in %s", fingerprint[:12], region.ID, g.Cfg.ArtifactRoot)
	return Result{
		Fingerprint:  fingerprint,
		ArtifactPath: g.ArtifactPackagePath(region.ID, fingerprint),
		SidecarPath:  sidecar,
		Manifest:     mf,
		Source:       source,
	}, nil
}

func (g *Generator) cachedResult(regionID, fingerprint string) (Result, error) {
	mf, err := manifest.Load(filepath.Join(g.ArtifactDir(regionID, fingerprint), "manifest.json"))
	if err != nil {
		return Result{}, err
	}
	return Result{
		Fingerprint:  fingerprint,
		ArtifactPath: g.ArtifactPackagePath(regionID, fingerprint),
		SidecarPath:  g.ArtifactPackagePath(regionID, fingerprint) + ".sha256",
		Manifest:     mf,
		Cached:       true,
	}, nil
}

func (g *Generator) runTiles(ctx context.Context, region catalog.Region, source SourceInfo, workDir, tileOut string) error {
	planetilerCache := filepath.Join(g.Cfg.CacheDir, "planetiler")
	if err := os.MkdirAll(planetilerCache, 0o755); err != nil {
		return err
	}
	env := []string{
		"TILE_PBF=" + source.Path,
		"TILE_OUT=" + tileOut,
		"TILE_MAX_HEAP=" + g.Cfg.TilesHeap,
		"TILE_MAXZOOM=" + fmt.Sprint(region.EffectiveMaxZoom()),
		"TILE_JAVA_HOME=" + javaHome(g.Cfg.Java21),
		"TILE_SOURCES_DIR=" + filepath.Join(planetilerCache, "sources"),
		"TILE_TMP_DIR=" + filepath.Join(planetilerCache, "tmp"),
		"TILE_TILE_WEIGHTS=" + filepath.Join(planetilerCache, "tile_weights.tsv.gz"),
		"TILE_PLANETILER_JAR=" + g.Cfg.PlanetilerJar,
	}
	if err := os.MkdirAll(filepath.Dir(tileOut), 0o755); err != nil {
		return err
	}
	return g.Runner.Run(ctx, Command{
		Name:    "bash",
		Args:    []string{g.Cfg.TilesScript},
		Env:     env,
		Dir:     g.Cfg.RepoRoot,
		LogPath: filepath.Join(workDir, "logs", "tiles.log"),
	})
}

func (g *Generator) runGraph(ctx context.Context, region catalog.Region, source SourceInfo, workDir, graphDir string) error {
	canonical, err := os.ReadFile(g.Cfg.GHConfig)
	if err != nil {
		return err
	}
	config, err := GenerateGraphConfig(canonical, source.Path, graphDir)
	if err != nil {
		return err
	}
	configPath := filepath.Join(workDir, "graph.yml")
	if err := os.WriteFile(configPath, config, 0o644); err != nil {
		return err
	}
	// The importer refuses to run over an existing graph (GraphHopper would
	// silently load it instead), so a resumed job starts from an empty dir.
	if err := os.RemoveAll(graphDir); err != nil {
		return err
	}
	// MotoGraphImport is GraphHopper's import plus the lane-guidance parser,
	// run with JDK 17's single-file source launcher against the pinned jar.
	if err := g.Runner.Run(ctx, Command{
		Name: g.Cfg.Java17,
		Args: []string{"-Xmx" + g.Cfg.GraphHeap, "-cp", g.Cfg.GraphHopperJar, g.Cfg.GraphImport, "import", configPath},
		Dir:  workDir, LogPath: filepath.Join(workDir, "logs", "graph.log"),
	}); err != nil {
		return err
	}
	// The stored profile marker is the graph's compatibility contract with the
	// app's pre-compiled weighting helper.
	props, err := os.ReadFile(filepath.Join(graphDir, "properties"))
	if err != nil {
		return fmt.Errorf("graph import produced no properties file: %w", err)
	}
	if !strings.Contains(string(props), "profiles="+g.Cfg.ExpectedGraphProfile) {
		return fmt.Errorf("graph profile marker does not match: want profiles=%s", g.Cfg.ExpectedGraphProfile)
	}
	return nil
}

func (g *Generator) runGeocoder(ctx context.Context, source SourceInfo, workDir, geocoderOut string) error {
	launcher := filepath.Join(g.Cfg.GeocoderDist, "bin", "geocoder-tool")
	if _, err := os.Stat(launcher); err != nil {
		g.logf("building the standalone geocoder distribution")
		if err := g.Runner.Run(ctx, Command{
			Name: g.Cfg.Gradlew,
			Args: []string{"-p", filepath.Join(g.Cfg.RepoRoot, "geocoder-tool", "standalone"), "--quiet", "installDist"},
			Dir:  g.Cfg.RepoRoot, LogPath: filepath.Join(workDir, "logs", "geocoder-build.log"),
		}); err != nil {
			return err
		}
	}
	if _, err := os.Stat(launcher); err != nil {
		return fmt.Errorf("geocoder launcher missing after installDist: %s", launcher)
	}
	if err := os.MkdirAll(geocoderOut, 0o755); err != nil {
		return err
	}
	// The Gradle start script appends JAVA_OPTS after its baked-in default, so
	// the configured heap wins. It is applied only to the launcher, never to
	// the gradlew fallback (which would size the Gradle daemon, not the tool).
	env := []string{
		"JAVA_HOME=" + javaHome(g.Cfg.Java17),
		"JAVA_OPTS=-Xmx" + g.Cfg.GeocoderHeap,
	}
	if err := g.Runner.Run(ctx, Command{
		Name: launcher, Args: []string{source.Path, geocoderOut},
		Env: env, Dir: workDir, LogPath: filepath.Join(workDir, "logs", "geocoder.log"),
	}); err != nil {
		return err
	}
	index := filepath.Join(geocoderOut, "geocoder.dat")
	if err := g.Runner.Run(ctx, Command{
		Name: launcher, Args: []string{"--validate", index},
		Env: env, Dir: workDir, LogPath: filepath.Join(workDir, "logs", "geocoder-validate.log"),
	}); err != nil {
		return err
	}
	return nil
}

func (g *Generator) writePackage(
	region catalog.Region,
	source SourceInfo,
	fingerprint, workDir, tileOut, graphDir, geocoderOut string,
) (string, manifest.Manifest, error) {
	tiles, err := motomap.BuildComponent(manifest.ComponentTiles, tileOut, manifest.FormatPMTilesV3, "")
	if err != nil {
		return "", manifest.Manifest{}, err
	}
	graph, err := motomap.BuildComponent(manifest.ComponentGraph, graphDir, manifest.FormatGraphDir, g.Cfg.ExpectedGraphProfile)
	if err != nil {
		return "", manifest.Manifest{}, err
	}
	geocoder, err := motomap.BuildComponent(
		manifest.ComponentGeocoder,
		filepath.Join(geocoderOut, "geocoder.dat"),
		manifest.FormatGeocoder,
		"",
	)
	if err != nil {
		return "", manifest.Manifest{}, err
	}
	mf := manifest.Manifest{
		SchemaVersion: manifest.SchemaVersion,
		PackageID:     fmt.Sprintf("%s-%s-%s", region.ID, sanitizeDate(region.Source.Date), fingerprint[:12]),
		Region: manifest.Region{
			ID:       region.ID,
			Name:     region.Name,
			Coverage: region.Coverage,
		},
		Source: manifest.Source{
			Date:   region.Source.Date,
			URL:    region.Source.URL,
			SHA256: source.SHA256,
			Bytes:  source.Bytes,
		},
		GeneratedAt: time.Now().UTC().Format(time.RFC3339),
		Generator: manifest.Generator{
			Name:                "map-server",
			Version:             PipelineVersion,
			PipelineFingerprint: fingerprint,
		},
		Components: []manifest.Component{tiles, graph, geocoder},
		Compatibility: manifest.Compatibility{
			PackageSchema: manifest.SchemaVersion,
			GraphProfile:  g.Cfg.ExpectedGraphProfile,
			GeocoderMagic: manifest.GeocoderMagic,
			TilesFormat:   manifest.FormatPMTilesV3,
		},
		Attribution: manifest.Attribution,
	}
	outDir := filepath.Join(workDir, "out")
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return "", manifest.Manifest{}, err
	}
	pkgPath := filepath.Join(outDir, region.ID+manifest.PackageFileExtension)
	if err := motomap.Write(pkgPath, mf, map[string]string{
		manifest.ComponentTiles:    tileOut,
		manifest.ComponentGraph:    graphDir,
		manifest.ComponentGeocoder: filepath.Join(geocoderOut, "geocoder.dat"),
	}, g.logf); err != nil {
		return "", manifest.Manifest{}, err
	}
	return pkgPath, mf, nil
}

// publish moves the finished package into the immutable artifact cache.
func (g *Generator) publish(regionID, fingerprint, pkgPath string, mf manifest.Manifest) error {
	dir := g.ArtifactDir(regionID, fingerprint)
	if err := os.MkdirAll(filepath.Dir(dir), 0o755); err != nil {
		return err
	}
	staging := dir + ".staging"
	_ = os.RemoveAll(staging)
	if err := os.MkdirAll(staging, 0o755); err != nil {
		return err
	}
	if err := moveFile(pkgPath, filepath.Join(staging, filepath.Base(pkgPath))); err != nil {
		return err
	}
	doc, err := mf.MarshalCanonical()
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(staging, "manifest.json"), doc, 0o644); err != nil {
		return err
	}
	if _, err := motomap.WriteSidecar(filepath.Join(staging, filepath.Base(pkgPath))); err != nil {
		return err
	}
	if err := os.RemoveAll(dir); err != nil {
		return err
	}
	return os.Rename(staging, dir)
}

func moveFile(from, to string) error {
	if err := os.Rename(from, to); err == nil {
		return nil
	}
	// Cross-device fallback.
	data, err := os.ReadFile(from)
	if err != nil {
		return err
	}
	if err := os.WriteFile(to, data, 0o644); err != nil {
		return err
	}
	return os.Remove(from)
}

var (
	ghDataReader = regexp.MustCompile(`(?m)^(\s*datareader\.file:\s*).*$`)
	ghGraphLoc   = regexp.MustCompile(`(?m)^(\s*graph\.location:\s*).*$`)
)

// GenerateGraphConfig rewrites only the two location keys of the canonical
// GraphHopper config. Everything that participates in the stored profile hash
// (profiles, custom_model_files, ignored highways, encoded values) is copied
// verbatim — this is what keeps the app's pre-compiled weighting valid.
func GenerateGraphConfig(canonical []byte, sourcePath, graphLocation string) ([]byte, error) {
	text := string(canonical)
	for name, re := range map[string]*regexp.Regexp{
		"datareader.file": ghDataReader,
		"graph.location":  ghGraphLoc,
	} {
		if len(re.FindAllString(text, -1)) != 1 {
			return nil, fmt.Errorf("canonical graph config must contain exactly one %s line", name)
		}
	}
	text = ghDataReader.ReplaceAllString(text, "${1}"+quoteYAML(sourcePath))
	text = ghGraphLoc.ReplaceAllString(text, "${1}"+quoteYAML(graphLocation))
	return []byte(text), nil
}

func quoteYAML(value string) string {
	return `"` + strings.ReplaceAll(value, `"`, `\"`) + `"`
}

func sanitizeDate(date string) string {
	return strings.ReplaceAll(date, "-", "")
}

func absSource(repoRoot, path string) string {
	if filepath.IsAbs(path) {
		return path
	}
	return filepath.Join(repoRoot, path)
}

// javaHome returns the JAVA_HOME for a java binary path, so scripts that
// expect `$JAVA_HOME/bin/java` keep working.
func javaHome(java string) string {
	if strings.HasSuffix(java, "/bin/java") {
		return strings.TrimSuffix(java, "/bin/java")
	}
	return java
}
