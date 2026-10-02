package pipeline

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
)

// FingerprintInput lists everything that can change package content. Two builds
// with the same fingerprint are the same package, which is what makes the
// artifact cache and request deduplication honest.
type FingerprintInput struct {
	PipelineVersion      string            `json:"pipelineVersion"`
	PackageSchema        int               `json:"packageSchema"`
	RegionID             string            `json:"regionId"`
	SourceDate           string            `json:"sourceDate"`
	SourceIdentity       string            `json:"sourceIdentity"`
	SourceSHA256         string            `json:"sourceSha256"`
	MaxZoom              int               `json:"maxZoom"`
	ExpectedGraphProfile string            `json:"expectedGraphProfile"`
	ToolHashes           map[string]string `json:"toolHashes"`
	PlanetilerSHA256     string            `json:"planetilerSha256"`
	GraphHopperSHA256    string            `json:"graphHopperSha256"`
}

// ComputeFingerprint hashes the canonical fingerprint input document.
func ComputeFingerprint(in FingerprintInput) string {
	doc, err := json.Marshal(in)
	if err != nil {
		// The struct only contains JSON-marshalable fields.
		panic(fmt.Sprintf("fingerprint marshal: %v", err))
	}
	return motomap.DigestString(string(doc))
}

// ToolHashes hashes every generation input that changes package content.
func ToolHashes(repoRoot string) (map[string]string, error) {
	paths := []string{
		"tools/gh/config.yml",
		"tools/gh/motorcycle.json",
		"tools/gh/MotoGraphImport.java",
		"tools/tiles/build-tiles.sh",
		"geocoder-tool/build.gradle.kts",
	}
	hashes := map[string]string{}
	for _, rel := range paths {
		full := filepath.Join(repoRoot, rel)
		d, err := motomap.DigestFile(full)
		if err != nil {
			return nil, fmt.Errorf("hash pipeline input %s: %w", rel, err)
		}
		hashes[rel] = d.SHA256
	}
	srcRoot := filepath.Join(repoRoot, "geocoder-tool/src")
	var files []string
	err := filepath.WalkDir(srcRoot, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(repoRoot, p)
		if err != nil {
			return err
		}
		files = append(files, filepath.ToSlash(rel))
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("walk geocoder-tool sources: %w", err)
	}
	sort.Strings(files)
	for _, rel := range files {
		d, err := motomap.DigestFile(filepath.Join(repoRoot, rel))
		if err != nil {
			return nil, fmt.Errorf("hash pipeline input %s: %w", rel, err)
		}
		hashes[rel] = d.SHA256
	}
	return hashes, nil
}

// SourceIdentity renders the operator-approved source location.
func SourceIdentity(region catalog.Region) string {
	if region.Source.URL != "" {
		return region.Source.URL
	}
	abs, err := filepath.Abs(region.Source.Path)
	if err != nil {
		return region.Source.Path
	}
	return abs
}

// FingerprintFor computes the cache key for a region. When the source hash is
// not pinned the observed hash must be supplied by the caller after the
// download; declaredHash is used otherwise so a cache hit needs no download.
func (g *Generator) FingerprintFor(region catalog.Region, observedSourceSHA string) (string, error) {
	toolHashes, err := ToolHashes(g.Cfg.RepoRoot)
	if err != nil {
		return "", err
	}
	planetiler, err := motomap.DigestFile(g.Cfg.PlanetilerJar)
	if err != nil {
		return "", fmt.Errorf("hash planetiler jar: %w", err)
	}
	graphhopper, err := motomap.DigestFile(g.Cfg.GraphHopperJar)
	if err != nil {
		return "", fmt.Errorf("hash graphhopper jar: %w", err)
	}
	sourceSHA := region.Source.SHA256
	if sourceSHA == "" {
		sourceSHA = observedSourceSHA
	}
	return ComputeFingerprint(FingerprintInput{
		PipelineVersion:      PipelineVersion,
		PackageSchema:        manifest.SchemaVersion,
		RegionID:             region.ID,
		SourceDate:           region.Source.Date,
		SourceIdentity:       SourceIdentity(region),
		SourceSHA256:         sourceSHA,
		MaxZoom:              region.EffectiveMaxZoom(),
		ExpectedGraphProfile: g.Cfg.ExpectedGraphProfile,
		ToolHashes:           toolHashes,
		PlanetilerSHA256:     planetiler.SHA256,
		GraphHopperSHA256:    graphhopper.SHA256,
	}), nil
}

// ArtifactInfo describes a cached package on disk.
type ArtifactInfo struct {
	Fingerprint string
	PackagePath string
	SidecarPath string
	Size        int64
	SHA256      string
	GeneratedAt string
}

// LatestArtifact returns the newest cached package for a region, if any. It
// reads only the small manifest/sidecar files, so callers can use it on a
// polling path.
func (g *Generator) LatestArtifact(regionID string) (ArtifactInfo, bool) {
	root := filepath.Join(g.Cfg.ArtifactRoot, regionID)
	entries, err := os.ReadDir(root)
	if err != nil {
		return ArtifactInfo{}, false
	}
	var best ArtifactInfo
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		fingerprint := entry.Name()
		dir := filepath.Join(root, fingerprint)
		pkg := filepath.Join(dir, regionID+manifest.PackageFileExtension)
		info, err := os.Stat(pkg)
		if err != nil || info.Size() == 0 {
			continue
		}
		mf, err := manifest.Load(filepath.Join(dir, "manifest.json"))
		if err != nil {
			continue
		}
		candidate := ArtifactInfo{
			Fingerprint: fingerprint,
			PackagePath: pkg,
			SidecarPath: pkg + ".sha256",
			Size:        info.Size(),
			GeneratedAt: mf.GeneratedAt,
		}
		if sidecar, err := os.ReadFile(candidate.SidecarPath); err == nil {
			candidate.SHA256 = strings.TrimSpace(string(sidecar))
		}
		if best.Fingerprint == "" || candidate.GeneratedAt > best.GeneratedAt {
			best = candidate
		}
	}
	return best, best.Fingerprint != ""
}

// ArtifactDir returns the immutable cache directory for a fingerprint.
func (g *Generator) ArtifactDir(regionID, fingerprint string) string {
	return filepath.Join(g.Cfg.ArtifactRoot, regionID, fingerprint)
}

// ArtifactPackagePath returns the published package path for a fingerprint.
func (g *Generator) ArtifactPackagePath(regionID, fingerprint string) string {
	return filepath.Join(g.ArtifactDir(regionID, fingerprint), regionID+manifest.PackageFileExtension)
}

// ArtifactExists reports whether a verified artifact is cached. When verify is
// true the package is re-read and every manifest claim is checked.
func (g *Generator) ArtifactExists(regionID, fingerprint string) bool {
	pkg := g.ArtifactPackagePath(regionID, fingerprint)
	if st, err := os.Stat(pkg); err != nil || st.Size() == 0 {
		return false
	}
	if !g.Cfg.VerifyArchive {
		_, err := manifest.Load(filepath.Join(g.ArtifactDir(regionID, fingerprint), "manifest.json"))
		return err == nil
	}
	_, err := motomap.Verify(pkg)
	return err == nil
}
