// Package cli implements the map-server command line: serve (UI or headless),
// local build/export, catalog maintenance, and admin inspection.
package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/api"
	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/config"
	"github.com/organicmoto/curveMaps/map-server/internal/geofabrik"
	"github.com/organicmoto/curveMaps/map-server/internal/jobs"
	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
	"github.com/organicmoto/curveMaps/map-server/internal/pipeline"
	"github.com/organicmoto/curveMaps/map-server/internal/security"
	"github.com/organicmoto/curveMaps/map-server/internal/trustedproxy"
)

// Version is the map-server release string.
const Version = "0.1.0"

// Run executes the CLI and returns a process exit code.
func Run(args []string, stdout, stderr io.Writer) int {
	if len(args) < 2 {
		usage(stderr)
		return 2
	}
	switch args[1] {
	case "serve":
		return runServe(args[2:], stdout, stderr)
	case "build":
		return runBuild(args[2:], stdout, stderr)
	case "catalog":
		return runCatalog(args[2:], stdout, stderr)
	case "admin":
		return runAdmin(args[2:], stdout, stderr)
	case "verify":
		return runVerify(args[2:], stdout, stderr)
	case "extracts":
		return runExtracts(args[2:], stdout, stderr)
	case "version", "--version", "-version":
		fmt.Fprintf(stdout, "map-server %s (package schema %d)\n", Version, manifest.SchemaVersion)
		return 0
	case "help", "--help", "-h":
		usage(stdout)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n", args[1])
		usage(stderr)
		return 2
	}
}

func usage(w io.Writer) {
	fmt.Fprint(w, `map-server - curveMaps regional package server

Usage:
  map-server serve   [flags]                 HTTP server (HTMX UI + JSON API)
  map-server build   --region ID [flags]     build a package locally and export it
  map-server verify  --package FILE [flags]  re-verify a published .motomap package
  map-server catalog validate|list|pin       inspect or pin the region catalog
  map-server admin   jobs|artifacts|cancel   inspect the persistent state
  map-server extracts [flags] search TEXT | locate LAT LON | resolve ID
                                             look up Geofabrik extracts as a request would
  map-server version

Common flags:
  --config PATH     JSON configuration file
  --state DIR       persistent state directory (work, cache, artifacts, queue)
  --catalog PATH    region catalog JSON (defaults to the built-in catalog)
  --repo-root DIR   checkout containing tools/ and geocoder-tool/

Serve flags:
  --addr HOST:PORT  listen address (default :8080)
  --headless        API only, no HTML UI
  --trusted-proxies comma-separated CIDRs allowed to set X-Forwarded-For

Build flags:
  --out DIR         export the package (plus sidecar and manifest) into DIR
  --keep-work       keep per-job scratch files
  --no-verify       skip re-reading the finished package for verification
`)
}

type commonFlags struct {
	configPath  string
	stateDir    string
	catalogPath string
	repoRoot    string
}

func addCommon(fs *flag.FlagSet, c *commonFlags) {
	fs.StringVar(&c.configPath, "config", "", "JSON configuration file")
	fs.StringVar(&c.stateDir, "state", "", "persistent state directory")
	fs.StringVar(&c.catalogPath, "catalog", "", "region catalog JSON")
	fs.StringVar(&c.repoRoot, "repo-root", "", "repository root")
}

type environment struct {
	cfg       config.File
	catalog   *catalog.Catalog
	generator *pipeline.Generator
	stateDir  string
	repoRoot  string
}

func resolve(c commonFlags) (environment, error) {
	file, err := config.Load(c.configPath)
	if err != nil {
		return environment{}, err
	}
	repoRoot := firstNonEmpty(c.repoRoot, os.Getenv("MOTO_REPO_ROOT"))
	if file.Pipeline != nil {
		repoRoot = firstNonEmpty(repoRoot, file.Pipeline.RepoRoot)
	}
	if repoRoot == "" {
		repoRoot, err = FindRepoRoot()
		if err != nil {
			// Serving already-built artifacts and inspecting state must work
			// without a checkout; build/serve-with-generation report the
			// missing pipeline inputs per job instead.
			cwd, cwdErr := os.Getwd()
			if cwdErr != nil {
				return environment{}, err
			}
			repoRoot = cwd
		}
	}
	stateDir := firstNonEmpty(c.stateDir, os.Getenv("MOTO_STATE_DIR"), file.StateDir,
		filepath.Join(repoRoot, "map-server", "state"))

	cat := catalog.Default()
	catalogPath := firstNonEmpty(c.catalogPath, os.Getenv("MOTO_CATALOG"), file.CatalogPath)
	if catalogPath != "" {
		cat, err = catalog.Load(catalogPath)
		if err != nil {
			return environment{}, err
		}
	}

	genCfg := pipeline.DefaultConfig(repoRoot, stateDir)
	genCfg.GeocoderDist = firstNonEmpty(os.Getenv("MOTO_GEOCODER_DIST"), genCfg.GeocoderDist)
	if p := file.Pipeline; p != nil {
		genCfg.WorkRoot = p.WorkRoot
		genCfg.ArtifactRoot = p.ArtifactRoot
		genCfg.CacheDir = p.CacheDir
		genCfg.Java17 = firstNonEmpty(p.Java17, genCfg.Java17)
		genCfg.Java21 = firstNonEmpty(p.Java21, genCfg.Java21)
		genCfg.GraphHeap = firstNonEmpty(p.GraphHeap, genCfg.GraphHeap)
		genCfg.TilesHeap = firstNonEmpty(p.TilesHeap, genCfg.TilesHeap)
		genCfg.GeocoderHeap = firstNonEmpty(p.GeocoderHeap, genCfg.GeocoderHeap)
		genCfg.MinFreeDiskBytes = p.MinFreeDiskBytes
		genCfg.MaxSourceBytes = p.MaxSourceBytes
		genCfg.ExpectedGraphProfile = firstNonEmpty(p.ExpectedGraphProfile, genCfg.ExpectedGraphProfile)
		if p.KeepWork != nil {
			genCfg.KeepWork = *p.KeepWork
		}
		if p.VerifyArchive != nil {
			genCfg.VerifyArchive = *p.VerifyArchive
		}
	}
	genCfg.VerifyArchive = true
	// Derive work/artifact/cache paths even when the pipeline inputs are not
	// present, so admin commands and artifact-only hosting still find state.
	_ = genCfg.ApplyDefaults()
	gen := pipeline.New(genCfg)
	return environment{cfg: file, catalog: cat, generator: gen, stateDir: stateDir, repoRoot: repoRoot}, nil
}

// FindRepoRoot walks up from the working directory looking for the checkout.
func FindRepoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "tools", "gh", "config.yml")); err == nil {
			if _, err := os.Stat(filepath.Join(dir, "geocoder-tool")); err == nil {
				return dir, nil
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", errors.New("could not locate the repository root (run with --repo-root)")
		}
		dir = parent
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func runServe(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(stderr)
	var common commonFlags
	addCommon(fs, &common)
	addr := fs.String("addr", "", "listen address")
	headless := fs.Bool("headless", false, "API only")
	trusted := fs.String("trusted-proxies", "", "comma-separated trusted proxy CIDRs")
	adminToken := fs.String("admin-token", "", "admin API bearer token")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	env, err := resolve(common)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	logger := slog.New(slog.NewTextHandler(stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

	listen := firstNonEmpty(*addr, os.Getenv("MOTO_ADDR"), env.cfg.Addr, ":8080")
	apiHeadless := *headless || env.cfg.Headless || envVarBool("MOTO_HEADLESS")
	proxies := strings.Split(firstNonEmpty(*trusted, os.Getenv("MOTO_TRUSTED_PROXIES"), strings.Join(env.cfg.TrustedProxies, ",")), ",")
	proxySet, err := trustedproxy.Parse(proxies)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	token := firstNonEmpty(*adminToken, os.Getenv("MOTO_ADMIN_TOKEN"), env.cfg.AdminToken)

	if err := env.generator.Cfg.ApplyDefaults(); err != nil {
		// The pipeline inputs (tools/, jars, geocoder distribution) are only
		// needed to build new packages. A server that merely hosts already
		// generated artifacts can run without them; build requests fail
		// honestly with this message.
		logger.Warn("generation pipeline unavailable; serving cached artifacts only", "error", err)
	}
	if err := os.MkdirAll(env.stateDir, 0o755); err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	secret, err := security.LoadOrCreateSecret(filepath.Join(env.stateDir, "secret.key"))
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	store, err := jobs.NewStore(filepath.Join(env.stateDir, "jobs.json"), 200)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	extracts, requested, err := requestsFrom(env, logger)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	// A completed requested build pins its observed source hash so the next
	// request is a cache hit instead of another download.
	onCompleted := func(region catalog.Region, result pipeline.Result) {
		if !region.Requested || requested == nil || result.Source.SHA256 == "" {
			return
		}
		if err := requested.PinSHA(region.ID, result.Source.SHA256); err != nil {
			logger.Warn("pin requested region", "region", region.ID, "error", err)
		}
	}
	manager := jobs.NewManager(store, env.catalog, env.generator,
		jobs.Options{MaxQueued: 8, MaxHistory: 200, OnCompleted: onCompleted}, logger)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := manager.Start(ctx); err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}

	cfg := api.Config{
		Version:    Version,
		Catalog:    env.catalog,
		Jobs:       manager,
		Generator:  env.generator,
		Trusted:    proxySet,
		CSRF:       security.NewCSRF(secret, 24*time.Hour, false),
		Limits:     limitsFrom(env.cfg.Limits),
		Headless:   apiHeadless,
		AdminToken: token,
		Log:        logger,
	}
	if extracts != nil {
		cfg.Extracts = extracts
		cfg.Requested = requested
		cfg.RefreshAfter = requestRefresh(env.cfg.Requests)
	}
	server, err := api.New(cfg)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	defer server.Close()

	httpServer := &http.Server{
		Addr:              listen,
		Handler:           server.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		// No WriteTimeout: package downloads legitimately take minutes.
		IdleTimeout:    120 * time.Second,
		MaxHeaderBytes: 1 << 16,
		ErrorLog:       slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdownCtx)
	}()
	mode := "UI + API"
	if apiHeadless {
		mode = "headless API"
	}
	fmt.Fprintf(stdout, "map-server %s listening on %s (%s)\n", Version, listen, mode)
	fmt.Fprintf(stdout, "state: %s\ncatalog: %d region(s)\nartifacts: %s\n",
		env.stateDir, len(env.catalog.Regions), env.generator.Cfg.ArtifactRoot)
	if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = manager.Shutdown(shutdownCtx)
	return 0
}

// requestsFrom builds the Geofabrik resolver and the requested-region store.
// Both are nil when requests are disabled. The store is attached to the
// catalog here so jobs requeued at startup can still find their region.
func requestsFrom(env environment, logger *slog.Logger) (*geofabrik.Service, *catalog.RequestedStore, error) {
	r := env.cfg.Requests
	if r == nil {
		r = &config.RequestsEntry{}
	}
	if envVarBool("MOTO_DISABLE_REQUESTS") || (r.Enabled != nil && !*r.Enabled) {
		return nil, nil, nil
	}
	maxRegions := r.MaxRegions
	if maxRegions <= 0 {
		maxRegions = 24
	}
	requested, err := catalog.OpenRequested(filepath.Join(env.stateDir, "requested-regions.json"), maxRegions)
	if err != nil {
		return nil, nil, err
	}
	env.catalog.AttachRequested(requested)
	service := geofabrik.New(geofabrik.Options{
		IndexURL:        firstNonEmpty(os.Getenv("MOTO_GEOFABRIK_INDEX"), r.IndexURL),
		CachePath:       filepath.Join(env.generator.Cfg.CacheDir, "geofabrik-index-v1.json"),
		MaxExtractBytes: r.MaxExtractBytes,
		Logf: func(format string, args ...any) {
			logger.Info(fmt.Sprintf(format, args...), "component", "geofabrik")
		},
	})
	return service, requested, nil
}

func runExtracts(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("extracts", flag.ContinueOnError)
	fs.SetOutput(stderr)
	var common commonFlags
	addCommon(fs, &common)
	limit := fs.Int("limit", 10, "maximum candidates")
	const extractsUsage = "usage: map-server extracts [flags] search TEXT | locate LAT LON | resolve ID"
	// Flags come first: parsing stops at the subcommand, so a negative
	// latitude after it stays positional.
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() < 1 {
		fmt.Fprintln(stderr, extractsUsage)
		return 2
	}
	sub, rest := fs.Arg(0), fs.Args()[1:]
	env, err := resolve(common)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	logger := slog.New(slog.NewTextHandler(stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	service, _, err := requestsFrom(env, logger)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	if service == nil {
		fmt.Fprintln(stderr, "map-server: requests are disabled in the configuration")
		return 1
	}
	ctx := context.Background()
	var out any
	switch {
	case sub == "search" && len(rest) >= 1:
		out, err = service.Search(ctx, strings.Join(rest, " "), *limit)
	case sub == "locate" && len(rest) == 2:
		var lat, lon float64
		if _, err = fmt.Sscanf(rest[0]+" "+rest[1], "%g %g", &lat, &lon); err == nil {
			out, err = service.Locate(ctx, lat, lon, *limit)
		}
	case sub == "resolve" && len(rest) == 1:
		out, err = service.Resolve(ctx, rest[0])
	default:
		fmt.Fprintln(stderr, extractsUsage)
		return 2
	}
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	enc := json.NewEncoder(stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(out)
	return 0
}

func requestRefresh(r *config.RequestsEntry) time.Duration {
	if r == nil || r.RefreshDays <= 0 {
		return 0
	}
	return time.Duration(r.RefreshDays) * 24 * time.Hour
}

func limitsFrom(l *config.Limits) api.Limits {
	out := api.DefaultLimits()
	if l == nil {
		return out
	}
	if l.BuildBurst > 0 {
		out.BuildBurst = l.BuildBurst
	}
	if l.BuildPerSeconds > 0 {
		out.BuildPer = time.Duration(l.BuildPerSeconds) * time.Second
	}
	if l.PollBurst > 0 {
		out.PollBurst = l.PollBurst
	}
	if l.PollPerSeconds > 0 {
		out.PollPer = time.Duration(l.PollPerSeconds) * time.Second
	}
	if l.DownloadBurst > 0 {
		out.DownloadBurst = l.DownloadBurst
	}
	if l.DownloadPerSeconds > 0 {
		out.DownloadPer = time.Duration(l.DownloadPerSeconds) * time.Second
	}
	if l.DefaultBurst > 0 {
		out.DefaultBurst = l.DefaultBurst
	}
	if l.DefaultPerSeconds > 0 {
		out.DefaultPer = time.Duration(l.DefaultPerSeconds) * time.Second
	}
	if l.MaxConcurrentDownloads > 0 {
		out.MaxConcurrentDownloads = l.MaxConcurrentDownloads
	}
	if l.MaxConcurrentPerClient > 0 {
		out.MaxConcurrentPerClient = l.MaxConcurrentPerClient
	}
	if l.MaxBodyBytes > 0 {
		out.MaxBodyBytes = l.MaxBodyBytes
	}
	if l.SearchBurst > 0 {
		out.SearchBurst = l.SearchBurst
	}
	if l.SearchPerSeconds > 0 {
		out.SearchPer = time.Duration(l.SearchPerSeconds) * time.Second
	}
	return out
}

func envVarBool(name string) bool {
	v := strings.ToLower(strings.TrimSpace(os.Getenv(name)))
	return v == "1" || v == "true" || v == "yes"
}

func runBuild(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("build", flag.ContinueOnError)
	fs.SetOutput(stderr)
	var common commonFlags
	addCommon(fs, &common)
	regionID := fs.String("region", "", "region id from the catalog")
	out := fs.String("out", "", "export directory")
	keepWork := fs.Bool("keep-work", false, "keep scratch files")
	noVerify := fs.Bool("no-verify", false, "skip package verification")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *regionID == "" {
		fmt.Fprintln(stderr, "map-server build: --region is required")
		return 2
	}
	env, err := resolve(common)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	env.generator.Cfg.KeepWork = env.generator.Cfg.KeepWork || *keepWork
	if *noVerify {
		env.generator.Cfg.VerifyArchive = false
	}
	region, err := env.catalog.Lookup(*regionID)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	result, err := env.generator.Build(ctx, region, func(p pipeline.Progress) {
		fmt.Fprintf(stdout, "[%3.0f%%] %-9s %s\n", p.Fraction*100, p.Step, p.Message)
	})
	if err != nil {
		fmt.Fprintf(stderr, "map-server: build failed: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "package: %s (%s)\n", result.ArtifactPath, packageSize(result.ArtifactPath))
	fmt.Fprintf(stdout, "fingerprint: %s\n", result.Fingerprint)
	if *out != "" {
		if err := export(result, *out); err != nil {
			fmt.Fprintf(stderr, "map-server: export failed: %v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "exported to: %s\n", *out)
	}
	return 0
}

func export(result pipeline.Result, outDir string) error {
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return err
	}
	copyFile := func(src, dst string) error {
		data, err := os.ReadFile(src)
		if err != nil {
			return err
		}
		return os.WriteFile(dst, data, 0o644)
	}
	if err := copyFile(result.ArtifactPath, filepath.Join(outDir, filepath.Base(result.ArtifactPath))); err != nil {
		return err
	}
	if err := copyFile(result.SidecarPath, filepath.Join(outDir, filepath.Base(result.SidecarPath))); err != nil {
		return err
	}
	doc, err := result.Manifest.MarshalCanonical()
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(outDir, "manifest.json"), doc, 0o644)
}

// packageSize reports the published package file's size. Components[0] is the
// alphabetically first component, which is the geocoder on a cache hit and the
// tiles on a fresh build — never the package size.
func packageSize(path string) string {
	if info, err := os.Stat(path); err == nil {
		return humanBytes(info.Size())
	}
	return "unknown size"
}

func runVerify(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	fs.SetOutput(stderr)
	pkg := fs.String("package", "", "package to verify")
	asJSON := fs.Bool("json", false, "emit the verified manifest as JSON")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *pkg == "" {
		fmt.Fprintln(stderr, "map-server verify: --package is required")
		return 2
	}
	mf, err := motomap.Verify(*pkg)
	if err != nil {
		fmt.Fprintf(stderr, "map-server verify: %s: %v\n", *pkg, err)
		return 1
	}
	if *asJSON {
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		if err := enc.Encode(mf); err != nil {
			fmt.Fprintf(stderr, "map-server verify: %v\n", err)
			return 1
		}
		return 0
	}
	fmt.Fprintf(stdout, "package OK: %s\n", *pkg)
	fmt.Fprintf(stdout, "  packageId:   %s\n", mf.PackageID)
	fmt.Fprintf(stdout, "  region:      %s (%s)\n", mf.Region.ID, mf.Region.Name)
	fmt.Fprintf(stdout, "  source:      date=%s bytes=%d sha256=%s\n", mf.Source.Date, mf.Source.Bytes, mf.Source.SHA256)
	fmt.Fprintf(stdout, "  generatedAt: %s\n", mf.GeneratedAt)
	fmt.Fprintf(stdout, "  generator:   %s %s fingerprint=%s\n",
		mf.Generator.Name, mf.Generator.Version, mf.Generator.PipelineFingerprint)
	fmt.Fprintf(stdout, "  compatibility: schema=%d graphProfile=%s geocoderMagic=%q tilesFormat=%s\n",
		mf.Compatibility.PackageSchema, mf.Compatibility.GraphProfile,
		mf.Compatibility.GeocoderMagic, mf.Compatibility.TilesFormat)
	for _, c := range mf.Components {
		if c.Kind == "directory" {
			fmt.Fprintf(stdout, "  component %-8s %s (%d files, %d bytes, sha256 %s)\n",
				c.Name, c.Format, len(c.Files), c.Bytes, c.SHA256)
			continue
		}
		fmt.Fprintf(stdout, "  component %-8s %s (%d bytes, sha256 %s)\n",
			c.Name, c.Format, c.Bytes, c.SHA256)
	}
	return 0
}

func runCatalog(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprintln(stderr, "usage: map-server catalog validate|list|pin")
		return 2
	}
	sub := args[0]
	fs := flag.NewFlagSet("catalog "+sub, flag.ContinueOnError)
	fs.SetOutput(stderr)
	var common commonFlags
	addCommon(fs, &common)
	regionID := fs.String("region", "", "region id (pin)")
	sha := fs.String("sha256", "", "source sha256 (pin)")
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	path := firstNonEmpty(common.catalogPath, os.Getenv("MOTO_CATALOG"))
	if path == "" {
		fmt.Fprintln(stderr, "map-server: --catalog is required for catalog commands")
		return 2
	}
	switch sub {
	case "validate":
		cat, err := catalog.Load(path)
		if err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "catalog OK: %d region(s)\n", len(cat.Regions))
		return 0
	case "list":
		cat, err := catalog.Load(path)
		if err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		for _, r := range cat.Regions {
			state := "enabled"
			if r.Disabled {
				state = "disabled"
			}
			fmt.Fprintf(stdout, "%-24s %-24s %-9s source=%s date=%s sha=%s\n",
				r.ID, r.Name, state, sourceKind(r), r.Source.Date, shortSHA(r.Source.SHA256))
		}
		return 0
	case "pin":
		if *regionID == "" || *sha == "" {
			fmt.Fprintln(stderr, "map-server catalog pin: --region and --sha256 are required")
			return 2
		}
		if !isHex64(*sha) {
			fmt.Fprintln(stderr, "map-server catalog pin: --sha256 must be 64 lowercase hex characters")
			return 2
		}
		cat, err := catalog.Load(path)
		if err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		found := false
		for i := range cat.Regions {
			if cat.Regions[i].ID == *regionID {
				cat.Regions[i].Source.SHA256 = *sha
				found = true
			}
		}
		if !found {
			fmt.Fprintf(stderr, "map-server: unknown region %q\n", *regionID)
			return 1
		}
		doc, err := json.MarshalIndent(cat, "", "  ")
		if err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		if err := os.WriteFile(path, append(doc, '\n'), 0o644); err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "pinned %s source to %s\n", *regionID, *sha)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown catalog command %q\n", sub)
		return 2
	}
}

func runAdmin(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprintln(stderr, "usage: map-server admin jobs|artifacts|cancel")
		return 2
	}
	sub := args[0]
	fs := flag.NewFlagSet("admin "+sub, flag.ContinueOnError)
	fs.SetOutput(stderr)
	var common commonFlags
	addCommon(fs, &common)
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	env, err := resolve(common)
	if err != nil {
		fmt.Fprintf(stderr, "map-server: %v\n", err)
		return 1
	}
	switch sub {
	case "jobs":
		store, err := jobs.NewStore(filepath.Join(env.stateDir, "jobs.json"), 200)
		if err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		for _, j := range store.All() {
			fmt.Fprintf(stdout, "%-24s %-10s %-9s %5.0f%% %s %s\n",
				j.ID, j.State, j.RegionID, j.Progress*100, j.CreatedAt.UTC().Format(time.RFC3339), j.Message)
		}
		return 0
	case "artifacts":
		root := env.generator.Config().ArtifactRoot
		entries, err := os.ReadDir(root)
		if err != nil {
			if os.IsNotExist(err) {
				fmt.Fprintln(stdout, "no artifacts")
				return 0
			}
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		var lines []string
		for _, region := range entries {
			if !region.IsDir() {
				continue
			}
			versions, err := os.ReadDir(filepath.Join(root, region.Name()))
			if err != nil {
				continue
			}
			for _, v := range versions {
				pkg := filepath.Join(root, region.Name(), v.Name(), region.Name()+manifest.PackageFileExtension)
				if info, err := os.Stat(pkg); err == nil {
					lines = append(lines, fmt.Sprintf("%-16s %s %s", region.Name(), v.Name()[:12], humanBytes(info.Size())))
				}
			}
		}
		sort.Strings(lines)
		for _, line := range lines {
			fmt.Fprintln(stdout, line)
		}
		return 0
	case "cancel":
		if len(args) < 2 {
			fmt.Fprintln(stderr, "usage: map-server admin cancel <job-id>")
			return 2
		}
		id := args[len(args)-1]
		store, err := jobs.NewStore(filepath.Join(env.stateDir, "jobs.json"), 200)
		if err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		job, ok := store.Get(id)
		if !ok {
			fmt.Fprintf(stderr, "map-server: no such job %q\n", id)
			return 1
		}
		if job.State == jobs.StateRunning {
			fmt.Fprintln(stderr, "map-server: a running build can only be cancelled by the running server (UI or admin API)")
			return 1
		}
		if !job.Active() {
			fmt.Fprintf(stderr, "map-server: job is already %s\n", job.State)
			return 1
		}
		job.State = jobs.StateCanceled
		job.Message = "cancelled from the admin CLI"
		job.FinishedAt = time.Now().UTC()
		if err := store.Upsert(job); err != nil {
			fmt.Fprintf(stderr, "map-server: %v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "cancelled %s\n", id)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown admin command %q\n", sub)
		return 2
	}
}

func sourceKind(r catalog.Region) string {
	if r.Source.URL != "" {
		return "url"
	}
	return "file"
}

func shortSHA(sha string) string {
	if len(sha) >= 12 {
		return sha[:12]
	}
	if sha == "" {
		return "unpinned"
	}
	return sha
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, r := range s {
		if !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'f')) {
			return false
		}
	}
	return true
}

func humanBytes(n int64) string {
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
