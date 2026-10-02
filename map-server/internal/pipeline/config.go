package pipeline

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// PipelineVersion identifies the generator logic. Bump it whenever step
// orchestration changes in a way that alters package content.
const PipelineVersion = "1"

// Config controls the generator. Zero values are filled by ApplyDefaults.
type Config struct {
	// RepoRoot is the checkout containing tools/ and geocoder-tool/.
	RepoRoot string

	// StateDir holds work, cache, and published artifacts.
	StateDir string
	// WorkRoot is per-job scratch space (default StateDir/work).
	WorkRoot string
	// ArtifactRoot is the immutable artifact cache (default StateDir/artifacts).
	ArtifactRoot string
	// CacheDir holds shared, reusable pipeline downloads (default StateDir/cache).
	CacheDir string

	// Java17 runs the GraphHopper import; Java21 runs Planetiler.
	Java17 string
	Java21 string

	TilesScript    string
	PlanetilerJar  string
	GraphHopperJar string
	GHConfig       string
	// GraphImport is the single-file Java launcher that runs the GraphHopper
	// import with the app's lane-guidance tag parser (tools/gh/MotoGraphImport.java).
	GraphImport string

	// Gradlew and GeocoderDist build/run the standalone geocoder tool.
	Gradlew      string
	GeocoderDist string

	GraphHeap    string
	TilesHeap    string
	GeocoderHeap string

	// KeepWork retains per-job scratch directories after the build.
	KeepWork bool

	// MinFreeDiskBytes is required on the state filesystem before a job starts.
	MinFreeDiskBytes int64
	// MaxSourceBytes caps a downloaded extract.
	MaxSourceBytes int64
	// VerifyArchive re-verifies published packages before reporting success.
	VerifyArchive bool

	// ExpectedGraphProfile is the stored GraphHopper profile marker a graph
	// must carry. It is part of the package's compatibility contract.
	ExpectedGraphProfile string

	// HTTPTimeout bounds source downloads and HTTP probes.
	HTTPTimeout time.Duration
}

// DefaultConfig derives a working configuration from the repository layout.
func DefaultConfig(repoRoot, stateDir string) Config {
	return Config{
		RepoRoot:     repoRoot,
		StateDir:     stateDir,
		Java17:       defaultJava17(),
		Java21:       defaultJava21(),
		GraphHeap:    "12g",
		TilesHeap:    "12g",
		GeocoderHeap: "6g",
		HTTPTimeout:  10 * time.Minute,
	}
}

// ApplyDefaults fills derived paths and validates required tools.
func (c *Config) ApplyDefaults() error {
	if c.RepoRoot == "" {
		return fmt.Errorf("repo root is not set")
	}
	if c.StateDir == "" {
		return fmt.Errorf("state dir is not set")
	}
	abs := func(p string) string {
		if p == "" {
			return ""
		}
		if filepath.IsAbs(p) {
			return p
		}
		return filepath.Join(c.RepoRoot, p)
	}
	c.WorkRoot = defaultPath(c.WorkRoot, filepath.Join(c.StateDir, "work"))
	c.ArtifactRoot = defaultPath(c.ArtifactRoot, filepath.Join(c.StateDir, "artifacts"))
	c.CacheDir = defaultPath(c.CacheDir, filepath.Join(c.StateDir, "cache"))
	c.TilesScript = defaultPath(abs(c.TilesScript), filepath.Join(c.RepoRoot, "tools/tiles/build-tiles.sh"))
	c.PlanetilerJar = defaultPath(abs(c.PlanetilerJar), filepath.Join(c.RepoRoot, "tools/tiles/planetiler.jar"))
	c.GraphHopperJar = defaultPath(abs(c.GraphHopperJar), filepath.Join(c.RepoRoot, "tools/gh/graphhopper-web-11.0.jar"))
	c.GHConfig = defaultPath(abs(c.GHConfig), filepath.Join(c.RepoRoot, "tools/gh/config.yml"))
	c.GraphImport = defaultPath(abs(c.GraphImport), filepath.Join(c.RepoRoot, "tools/gh/MotoGraphImport.java"))
	c.Gradlew = defaultPath(abs(c.Gradlew), filepath.Join(c.RepoRoot, "gradlew"))
	// The standalone settings file points :geocoder-tool at its real project
	// directory, so installDist lands under geocoder-tool/build/install.
	c.GeocoderDist = defaultPath(abs(c.GeocoderDist), filepath.Join(c.RepoRoot, "geocoder-tool/build/install/geocoder-tool"))
	c.GraphHeap = defaultPath(c.GraphHeap, "12g")
	c.TilesHeap = defaultPath(c.TilesHeap, "12g")
	c.GeocoderHeap = defaultPath(c.GeocoderHeap, "6g")
	c.HTTPTimeout = defaultDuration(c.HTTPTimeout, 10*time.Minute)
	if c.MinFreeDiskBytes <= 0 {
		c.MinFreeDiskBytes = 5 << 30 // 5 GiB
	}
	if c.MaxSourceBytes <= 0 {
		c.MaxSourceBytes = 16 << 30 // 16 GiB
	}
	if c.ExpectedGraphProfile == "" {
		c.ExpectedGraphProfile = "motorcycle|198752012"
	}
	for _, p := range []string{c.TilesScript, c.PlanetilerJar, c.GraphHopperJar, c.GHConfig, c.GraphImport} {
		if _, err := os.Stat(p); err != nil {
			return fmt.Errorf("pipeline input missing: %s", p)
		}
	}
	// The Gradle wrapper is only needed when the standalone geocoder
	// distribution has not been built (deployments pre-build it).
	launcher := filepath.Join(c.GeocoderDist, "bin", "geocoder-tool")
	if _, err := os.Stat(launcher); err != nil {
		if _, err := os.Stat(c.Gradlew); err != nil {
			return fmt.Errorf("geocoder launcher missing (%s) and no gradle wrapper at %s", launcher, c.Gradlew)
		}
	}
	return nil
}

func defaultPath(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func defaultDuration(value, fallback time.Duration) time.Duration {
	if value <= 0 {
		return fallback
	}
	return value
}

func defaultJava17() string {
	if v := os.Getenv("MOTO_JAVA17"); v != "" {
		return v
	}
	candidates := []string{}
	if v := os.Getenv("JAVA_HOME"); v != "" {
		candidates = append(candidates, filepath.Join(v, "bin/java"))
	}
	candidates = append(candidates,
		"/opt/homebrew/opt/openjdk@17/bin/java",
		"/usr/lib/jvm/temurin-17-jdk/bin/java",
	)
	for _, candidate := range candidates {
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
	}
	return "java"
}

func defaultJava21() string {
	if v := os.Getenv("MOTO_JAVA21"); v != "" {
		return v
	}
	if v := os.Getenv("TILE_JAVA_HOME"); v != "" {
		return filepath.Join(v, "bin/java")
	}
	candidates := []string{"/opt/homebrew/opt/openjdk@21/bin/java", "/usr/lib/jvm/temurin-21-jdk/bin/java"}
	// macOS keeps JDKs outside Homebrew too; /usr/libexec/java_home resolves
	// the registered 21 install.
	if out, err := exec.Command("/usr/libexec/java_home", "-v", "21").Output(); err == nil {
		candidates = append(candidates, filepath.Join(strings.TrimSpace(string(out)), "bin", "java"))
	}
	for _, candidate := range candidates {
		if _, err := os.Stat(candidate); err == nil && javaMajor(candidate) >= 21 {
			return candidate
		}
	}
	// Fall back to any java on PATH that is actually 21+.
	if path, err := exec.LookPath("java"); err == nil && javaMajor(path) >= 21 {
		return path
	}
	return defaultJava17()
}

// JavaMajor reports the major version of a java binary (0 when unavailable).
func JavaMajor(java string) int { return javaMajor(java) }

// JavaAvailable reports whether a java binary exists and reports the given
// minimum major version.
func JavaAvailable(java string) bool {
	return javaMajor(java) >= 17
}

// javaMajor reads the major version of a java binary, or 0 when unavailable.
func javaMajor(java string) int {
	out, err := exec.Command(java, "-version").CombinedOutput()
	if err != nil {
		return 0
	}
	text := string(out)
	start := strings.Index(text, `version "`)
	if start < 0 {
		return 0
	}
	rest := text[start+len(`version "`):]
	end := strings.IndexByte(rest, '"')
	if end < 0 {
		return 0
	}
	version := rest[:end]
	parts := strings.SplitN(version, ".", 2)
	var major int
	fmt.Sscanf(parts[0], "%d", &major)
	if major == 1 && len(parts) > 1 {
		fmt.Sscanf(strings.SplitN(parts[1], ".", 2)[0], "%d", &major)
	}
	return major
}
