package motomap

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"

	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
)

// writeFixture builds a small but structurally valid package.
func writeFixture(t *testing.T, dir string) (string, manifest.Manifest) {
	t.Helper()
	tiles := filepath.Join(dir, "basemap.pmtiles")
	tileBytes := append([]byte("PMTiles"), 3)
	tileBytes = append(tileBytes, make([]byte, 127-len(tileBytes))...)
	if err := os.WriteFile(tiles, tileBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	graph := filepath.Join(dir, "graph")
	if err := os.MkdirAll(graph, 0o755); err != nil {
		t.Fatal(err)
	}
	files := map[string]string{
		"properties": "graph.profiles=motorcycle|198752012\n",
		"nodes":      "nodes-content",
		"edges":      "edges-content",
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(graph, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	geocoder := filepath.Join(dir, "geocoder.dat")
	geoBytes := append([]byte(manifest.GeocoderMagic), make([]byte, 64)...)
	if err := os.WriteFile(geocoder, geoBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	tilesComp, err := BuildComponent(manifest.ComponentTiles, tiles, manifest.FormatPMTilesV3, "")
	if err != nil {
		t.Fatal(err)
	}
	graphComp, err := BuildComponent(manifest.ComponentGraph, graph, manifest.FormatGraphDir, "motorcycle|198752012")
	if err != nil {
		t.Fatal(err)
	}
	geoComp, err := BuildComponent(manifest.ComponentGeocoder, geocoder, manifest.FormatGeocoder, "")
	if err != nil {
		t.Fatal(err)
	}
	m := manifest.Manifest{
		SchemaVersion: manifest.SchemaVersion,
		PackageID:     "queensland-260928-abcdef123456",
		Region:        manifest.Region{ID: "queensland", Name: "Queensland"},
		Source:        manifest.Source{Date: "260928", SHA256: DigestString("source")},
		GeneratedAt:   "2026-10-01T00:00:00Z",
		Generator:     manifest.Generator{Name: "map-server", Version: "test", PipelineFingerprint: DigestString("fp")},
		Components:    []manifest.Component{tilesComp, graphComp, geoComp},
		Compatibility: manifest.Compatibility{
			PackageSchema: manifest.SchemaVersion,
			GraphProfile:  "motorcycle|198752012",
			GeocoderMagic: manifest.GeocoderMagic,
			TilesFormat:   manifest.FormatPMTilesV3,
		},
		Attribution: manifest.Attribution,
	}
	pkg := filepath.Join(dir, "out", "queensland.motomap")
	if err := os.MkdirAll(filepath.Dir(pkg), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := Write(pkg, m, map[string]string{
		manifest.ComponentTiles:    tiles,
		manifest.ComponentGraph:    graph,
		manifest.ComponentGeocoder: geocoder,
	}, nil); err != nil {
		t.Fatal(err)
	}
	return pkg, m
}

func TestWriteAndVerifyRoundTrip(t *testing.T) {
	dir := t.TempDir()
	pkg, m := writeFixture(t, dir)
	got, err := Verify(pkg)
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	if got.PackageID != m.PackageID {
		t.Fatalf("package id mismatch: %s", got.PackageID)
	}
	if _, err := WriteSidecar(pkg); err != nil {
		t.Fatal(err)
	}
	sidecar, err := os.ReadFile(pkg + ".sha256")
	if err != nil || len(bytes.TrimSpace(sidecar)) != 64 {
		t.Fatalf("sidecar: %v %q", err, sidecar)
	}
}

func TestVerifyRejectsTamperedContent(t *testing.T) {
	dir := t.TempDir()
	pkg, _ := writeFixture(t, dir)
	// Rewrite the archive with one changed byte in a graph file.
	in, err := zip.OpenReader(pkg)
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, f := range in.File {
		rc, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		var content bytes.Buffer
		if _, err := content.ReadFrom(rc); err != nil {
			t.Fatal(err)
		}
		rc.Close()
		if f.Name == "graph/nodes" {
			content.WriteString("tampered")
		}
		w, err := zw.CreateHeader(&zip.FileHeader{Name: f.Name, Method: f.Method})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write(content.Bytes()); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	in.Close()
	if err := os.WriteFile(pkg, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(pkg); err == nil {
		t.Fatal("tampered package verified successfully")
	}
}

func TestVerifyRejectsUnsafeAndDuplicateEntries(t *testing.T) {
	dir := t.TempDir()
	pkg, _ := writeFixture(t, dir)
	data, err := os.ReadFile(pkg)
	if err != nil {
		t.Fatal(err)
	}
	for name, mutate := range map[string]func(*zip.Writer){
		"zip slip": func(zw *zip.Writer) {
			w, _ := zw.Create("../../escape")
			_, _ = w.Write([]byte("x"))
		},
		"duplicate": func(zw *zip.Writer) {
			w, _ := zw.Create("manifest.json")
			_, _ = w.Write([]byte("{}"))
		},
		"absolute": func(zw *zip.Writer) {
			w, _ := zw.Create("/etc/passwd")
			_, _ = w.Write([]byte("x"))
		},
	} {
		t.Run(name, func(t *testing.T) {
			in, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
			if err != nil {
				t.Fatal(err)
			}
			var buf bytes.Buffer
			zw := zip.NewWriter(&buf)
			for _, f := range in.File {
				rc, _ := f.Open()
				var content bytes.Buffer
				_, _ = content.ReadFrom(rc)
				rc.Close()
				w, _ := zw.CreateHeader(&zip.FileHeader{Name: f.Name, Method: f.Method})
				_, _ = w.Write(content.Bytes())
			}
			mutate(zw)
			_ = zw.Close()
			bad := filepath.Join(dir, name+".motomap")
			if err := os.WriteFile(bad, buf.Bytes(), 0o644); err != nil {
				t.Fatal(err)
			}
			if _, err := Verify(bad); err == nil {
				t.Fatalf("%s package verified successfully", name)
			}
		})
	}
}

func TestDigestDirIsDeterministicAndOrderIndependent(t *testing.T) {
	dir := t.TempDir()
	root := filepath.Join(dir, "a")
	if err := os.MkdirAll(filepath.Join(root, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	for name, content := range map[string]string{
		"z.txt":     "z",
		"a.txt":     "a",
		"sub/b.txt": "b",
	} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	first, err := DigestDir(root)
	if err != nil {
		t.Fatal(err)
	}
	second, err := DigestDir(root)
	if err != nil {
		t.Fatal(err)
	}
	if first.SHA256 != second.SHA256 {
		t.Fatal("digest changed between runs")
	}
	if len(first.Files) != 3 || first.Files[0].Path != "a.txt" || first.Files[2].Path != "z.txt" {
		t.Fatalf("files not sorted: %+v", first.Files)
	}
	if err := os.Remove(filepath.Join(root, "a.txt")); err != nil {
		t.Fatal(err)
	}
	third, _ := DigestDir(root)
	if third.SHA256 == first.SHA256 {
		t.Fatal("digest ignored a removed file")
	}
}

func TestCheckPBFMagic(t *testing.T) {
	var good bytes.Buffer
	header := []byte("OSMHeader-blob")
	_ = binary.Write(&good, binary.BigEndian, uint32(len(header)))
	good.Write(header)
	if err := CheckPBFMagic(good.Bytes()); err != nil {
		t.Fatalf("valid PBF rejected: %v", err)
	}
	if err := CheckPBFMagic([]byte("<html><body>index</body></html>")); err == nil {
		t.Fatal("HTML accepted as a PBF")
	}
	if err := CheckPBFMagic([]byte{0, 0, 0, 0}); err == nil {
		t.Fatal("zero-length blob accepted")
	}
}
