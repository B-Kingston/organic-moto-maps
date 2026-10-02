package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
)

func mustWrite(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

// writeTestPackage builds a minimal but structurally valid .motomap package.
func writeTestPackage(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()

	tiles := filepath.Join(dir, "basemap.pmtiles")
	tileBytes := append([]byte("PMTiles"), 3)
	tileBytes = append(tileBytes, make([]byte, 128-len(tileBytes))...)
	mustWrite(t, tiles, tileBytes)

	geocoder := filepath.Join(dir, "geocoder.dat")
	mustWrite(t, geocoder, append([]byte(manifest.GeocoderMagic), make([]byte, 64)...))

	graph := filepath.Join(dir, "graph-cache")
	mustWrite(t, filepath.Join(graph, "properties"), []byte("graph.profiles=motorcycle|198752012\n"))
	mustWrite(t, filepath.Join(graph, "nodes"), []byte("nodes"))

	tilesComponent, err := motomap.BuildComponent(manifest.ComponentTiles, tiles, manifest.FormatPMTilesV3, "")
	if err != nil {
		t.Fatal(err)
	}
	graphComponent, err := motomap.BuildComponent(manifest.ComponentGraph, graph, manifest.FormatGraphDir, "motorcycle|198752012")
	if err != nil {
		t.Fatal(err)
	}
	geocoderComponent, err := motomap.BuildComponent(manifest.ComponentGeocoder, geocoder, manifest.FormatGeocoder, "")
	if err != nil {
		t.Fatal(err)
	}
	mf := manifest.Manifest{
		SchemaVersion: manifest.SchemaVersion,
		PackageID:     "queensland-260928-0123456789ab",
		Region:        manifest.Region{ID: "queensland", Name: "Queensland", Coverage: "Queensland, Australia"},
		Source: manifest.Source{
			Date:   "260928",
			URL:    "https://example.invalid/queensland.osm.pbf",
			SHA256: strings.Repeat("a", 64),
			Bytes:  196857477,
		},
		GeneratedAt: "2026-10-01T00:00:00Z",
		Generator: manifest.Generator{
			Name:                "map-server",
			Version:             "test",
			PipelineFingerprint: strings.Repeat("b", 64),
		},
		Components: []manifest.Component{tilesComponent, graphComponent, geocoderComponent},
		Compatibility: manifest.Compatibility{
			PackageSchema: manifest.SchemaVersion,
			GraphProfile:  "motorcycle|198752012",
			GeocoderMagic: manifest.GeocoderMagic,
			TilesFormat:   manifest.FormatPMTilesV3,
		},
		Attribution: manifest.Attribution,
	}
	pkg := filepath.Join(dir, "queensland.motomap")
	if err := motomap.Write(pkg, mf, map[string]string{
		manifest.ComponentTiles:    tiles,
		manifest.ComponentGraph:    graph,
		manifest.ComponentGeocoder: geocoder,
	}, nil); err != nil {
		t.Fatal(err)
	}
	return pkg
}

func TestVerifyCommandAcceptsValidPackage(t *testing.T) {
	pkg := writeTestPackage(t)
	var out, errOut bytes.Buffer
	if code := Run([]string{"map-server", "verify", "--package", pkg}, &out, &errOut); code != 0 {
		t.Fatalf("verify exit %d: %s", code, errOut.String())
	}
	for _, want := range []string{"package OK", "motorcycle|198752012", "component graph", "component tiles", "component geocoder"} {
		if !strings.Contains(out.String(), want) {
			t.Fatalf("verify output missing %q:\n%s", want, out.String())
		}
	}
}

func TestVerifyCommandRejectsTamperedPackage(t *testing.T) {
	pkg := writeTestPackage(t)
	data, err := os.ReadFile(pkg)
	if err != nil {
		t.Fatal(err)
	}
	// The tiles entry is stored uncompressed, so its payload appears verbatim.
	needle := append([]byte("PMTiles"), 3)
	idx := bytes.Index(data, needle)
	if idx < 0 {
		t.Fatal("tiles payload not found in package")
	}
	data[idx+64] ^= 0xff
	if err := os.WriteFile(pkg, data, 0o644); err != nil {
		t.Fatal(err)
	}
	var out, errOut bytes.Buffer
	if code := Run([]string{"map-server", "verify", "--package", pkg}, &out, &errOut); code != 1 {
		t.Fatalf("verify exit %d, want 1 (out=%s err=%s)", code, out.String(), errOut.String())
	}
}

func TestVerifyCommandRequiresPackage(t *testing.T) {
	var out, errOut bytes.Buffer
	if code := Run([]string{"map-server", "verify"}, &out, &errOut); code != 2 {
		t.Fatalf("verify exit %d, want 2", code)
	}
}

// TestPackageSizeReportsTheFileNotAComponent pins the build command's size
// line: it must describe the package file, not the alphabetically first
// component (geocoder on a cache hit, tiles on a fresh build).
func TestPackageSizeReportsTheFileNotAComponent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pkg.motomap")
	if err := os.WriteFile(path, make([]byte, 1536), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := packageSize(path); got != "1.5 KiB" {
		t.Fatalf("packageSize = %q, want 1.5 KiB", got)
	}
	if got := packageSize(filepath.Join(t.TempDir(), "missing")); got != "unknown size" {
		t.Fatalf("packageSize(missing) = %q, want unknown size", got)
	}
}
