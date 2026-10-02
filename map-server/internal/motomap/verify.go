package motomap

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"

	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
)

// MaxEntries caps the number of zip entries a package may contain. A graph
// cache has a few dozen files; a real package is far below this.
const MaxEntries = 4096

// Verify re-reads a written package and proves every manifest claim: entry
// set, per-file size and SHA-256, component digests, and the format magic of
// each component. It returns the parsed manifest.
func Verify(path string) (manifest.Manifest, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return manifest.Manifest{}, fmt.Errorf("open package: %w", err)
	}
	defer zr.Close()
	if len(zr.File) > MaxEntries {
		return manifest.Manifest{}, fmt.Errorf("package has %d entries (max %d)", len(zr.File), MaxEntries)
	}
	byName := map[string]*zip.File{}
	for _, f := range zr.File {
		if err := validateRelativePath(f.Name); err != nil {
			return manifest.Manifest{}, fmt.Errorf("package entry %q: %w", f.Name, err)
		}
		if f.FileInfo().IsDir() {
			return manifest.Manifest{}, fmt.Errorf("package entry %q: directory entries are not allowed", f.Name)
		}
		if f.Mode()&os.ModeSymlink != 0 {
			return manifest.Manifest{}, fmt.Errorf("package entry %q: symlinks are not allowed", f.Name)
		}
		if _, dup := byName[f.Name]; dup {
			return manifest.Manifest{}, fmt.Errorf("package entry %q: duplicate entry", f.Name)
		}
		byName[f.Name] = f
	}
	mf, ok := byName["manifest.json"]
	if !ok {
		return manifest.Manifest{}, fmt.Errorf("package has no manifest.json")
	}
	doc, err := readAll(mf, 1<<20)
	if err != nil {
		return manifest.Manifest{}, fmt.Errorf("read manifest.json: %w", err)
	}
	m, err := manifest.Parse(doc)
	if err != nil {
		return manifest.Manifest{}, err
	}

	expected := map[string]manifest.FileEntry{}
	for _, c := range m.Components {
		if c.Kind == "file" {
			expected[c.Path] = manifest.FileEntry{Path: c.Path, Bytes: c.Bytes, SHA256: c.SHA256}
			continue
		}
		for _, fe := range c.Files {
			full := c.Path + "/" + fe.Path
			expected[full] = manifest.FileEntry{Path: full, Bytes: fe.Bytes, SHA256: fe.SHA256}
		}
	}
	if len(byName) != len(expected)+1 {
		var extra []string
		for name := range byName {
			if name != "manifest.json" {
				if _, ok := expected[name]; !ok {
					extra = append(extra, name)
				}
			}
		}
		sort.Strings(extra)
		return manifest.Manifest{}, fmt.Errorf("package has %d entries, manifest lists %d%s",
			len(byName), len(expected), extraSuffix(extra))
	}
	for name, want := range expected {
		f, ok := byName[name]
		if !ok {
			return manifest.Manifest{}, fmt.Errorf("manifest lists %q but the package does not contain it", name)
		}
		sum, size, err := hashZipFile(f)
		if err != nil {
			return manifest.Manifest{}, fmt.Errorf("read %q: %w", name, err)
		}
		if size != want.Bytes {
			return manifest.Manifest{}, fmt.Errorf("%q: size %d does not match manifest %d", name, size, want.Bytes)
		}
		if sum != want.SHA256 {
			return manifest.Manifest{}, fmt.Errorf("%q: sha256 %s does not match manifest %s", name, sum, want.SHA256)
		}
	}

	// Component-level digests must be reproducible from their file lists.
	for _, c := range m.Components {
		if c.Kind != "directory" {
			continue
		}
		got := digestFromEntries(c.Files)
		if got != c.SHA256 {
			return manifest.Manifest{}, fmt.Errorf("component %s digest %s does not match its file list %s", c.Name, c.SHA256, got)
		}
	}

	if err := verifyMagic(m, byName); err != nil {
		return manifest.Manifest{}, err
	}
	return m, nil
}

func extraSuffix(extra []string) string {
	if len(extra) == 0 {
		return ""
	}
	if len(extra) > 5 {
		extra = extra[:5]
	}
	return " (unexpected: " + strings.Join(extra, ", ") + ")"
}

func digestFromEntries(files []manifest.FileEntry) string {
	h := sha256.New()
	for _, f := range files {
		fmt.Fprintf(h, "%s\x00%d\x00%s\n", f.Path, f.Bytes, f.SHA256)
	}
	return hex.EncodeToString(h.Sum(nil))
}

func verifyMagic(m manifest.Manifest, byName map[string]*zip.File) error {
	tiles, _ := m.Component(manifest.ComponentTiles)
	tf, ok := byName[tiles.Path]
	if !ok {
		return fmt.Errorf("tiles component missing")
	}
	head, err := readPrefix(tf, 8)
	if err != nil {
		return err
	}
	if !bytes.HasPrefix(head, []byte("PMTiles")) {
		return fmt.Errorf("tiles archive is not PMTiles")
	}
	if head[7] != 3 {
		return fmt.Errorf("tiles archive is PMTiles version %d, want 3", head[7])
	}
	geo, _ := m.Component(manifest.ComponentGeocoder)
	gf, ok := byName[geo.Path]
	if !ok {
		return fmt.Errorf("geocoder component missing")
	}
	ghead, err := readPrefix(gf, len(manifest.GeocoderMagic))
	if err != nil {
		return err
	}
	if string(ghead) != manifest.GeocoderMagic {
		return fmt.Errorf("geocoder index has wrong magic %q", string(ghead))
	}
	graph, _ := m.Component(manifest.ComponentGraph)
	props, ok := byName[graph.Path+"/properties"]
	if !ok {
		return fmt.Errorf("graph/properties missing")
	}
	body, err := readAll(props, 1<<20)
	if err != nil {
		return err
	}
	if !bytes.Contains(body, []byte("profiles="+m.Compatibility.GraphProfile)) {
		return fmt.Errorf("graph properties do not contain profiles=%s", m.Compatibility.GraphProfile)
	}
	return nil
}

func readPrefix(f *zip.File, n int) ([]byte, error) {
	rc, err := f.Open()
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	buf := make([]byte, n)
	if _, err := io.ReadFull(rc, buf); err != nil {
		return nil, fmt.Errorf("read %q prefix: %w", f.Name, err)
	}
	return buf, nil
}

func readAll(f *zip.File, limit int64) ([]byte, error) {
	rc, err := f.Open()
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	return io.ReadAll(io.LimitReader(rc, limit))
}

func hashZipFile(f *zip.File) (string, int64, error) {
	rc, err := f.Open()
	if err != nil {
		return "", 0, err
	}
	defer rc.Close()
	h := sha256.New()
	n, err := io.Copy(h, rc)
	if err != nil {
		return "", 0, err
	}
	if n != int64(f.UncompressedSize64) {
		return "", 0, fmt.Errorf("uncompressed size %d does not match declared %d", n, f.UncompressedSize64)
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// CheckPBFMagic verifies that data begins with an OSM PBF blob header naming
// an OSMHeader block. Geofabrik's shallow paths return an HTML index with
// HTTP 200, so bytes alone are not proof of a valid extract.
func CheckPBFMagic(data []byte) error {
	if len(data) < 4 {
		return fmt.Errorf("file too small to be a PBF")
	}
	headerLen := binary.BigEndian.Uint32(data[:4])
	if headerLen == 0 || headerLen > 64*1024 {
		return fmt.Errorf("invalid PBF blob header length %d", headerLen)
	}
	if int(headerLen) > len(data)-4 {
		return fmt.Errorf("PBF blob header length %d exceeds available data", headerLen)
	}
	if !bytes.Contains(data[4:4+headerLen], []byte("OSMHeader")) {
		return fmt.Errorf("first PBF blob is not an OSMHeader")
	}
	return nil
}
