// Package manifest defines the versioned on-disk contract for a complete
// regional data package (`.motomap`). The Go writer here and the Android
// importer (app/src/main/java/com/organicmoto/maps/region/RegionPackage.kt)
// read the same JSON document; any schema change must bump SchemaVersion and
// update both sides plus docs/map-server.md.
package manifest

import (
	"encoding/json"
	"fmt"
	"os"
	"path"
	"regexp"
	"sort"
	"strings"
)

// SchemaVersion is the only package schema this module writes or accepts.
const SchemaVersion = 1

// PackageFileExtension is the conventional file extension for a package.
const PackageFileExtension = ".motomap"

// Attribution is the OSM/OpenMapTiles attribution every package must carry.
const Attribution = "© OpenMapTiles.org © OpenStreetMap contributors · Noto (OFL) · icons CC BY 4.0"

// Component names. Every package carries exactly these three.
const (
	ComponentTiles    = "tiles"
	ComponentGraph    = "graph"
	ComponentGeocoder = "geocoder"
)

// Formats recorded for each component.
const (
	FormatPMTilesV3 = "pmtiles-v3"
	FormatGraphDir  = "graphhopper-graph"
	FormatGeocoder  = "omgeo01"
)

// GeocoderMagic is the fixed magic/version marker of the geocoder index.
const GeocoderMagic = "OMGEO01\n"

// GraphProfilePrefix is the profile marker stored inside a GraphHopper graph.
const GraphProfilePrefix = "motorcycle|"

// Region is the operator-approved region identity a package was built for.
type Region struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Coverage string `json:"coverage,omitempty"`
}

// Source describes the OSM extract the package was generated from.
type Source struct {
	Date   string `json:"date"`
	URL    string `json:"url,omitempty"`
	SHA256 string `json:"sha256"`
	Bytes  int64  `json:"bytes,omitempty"`
}

// Generator identifies the pipeline that produced the package.
type Generator struct {
	Name                string `json:"name"`
	Version             string `json:"version"`
	PipelineFingerprint string `json:"pipelineFingerprint"`
}

// FileEntry pins one file inside a directory component.
type FileEntry struct {
	Path   string `json:"path"`
	Bytes  int64  `json:"bytes"`
	SHA256 string `json:"sha256"`
}

// Component describes one deliverable inside the package.
type Component struct {
	Name    string      `json:"name"`
	Kind    string      `json:"kind"` // "file" or "directory"
	Path    string      `json:"path"` // package-relative path
	Format  string      `json:"format"`
	Bytes   int64       `json:"bytes"`
	SHA256  string      `json:"sha256"`            // digest of the component content
	Files   []FileEntry `json:"files,omitempty"`   // directory components: sorted by path
	Profile string      `json:"profile,omitempty"` // graph: stored profile marker
}

// Compatibility pins the reader contracts the package was built against.
type Compatibility struct {
	PackageSchema     int    `json:"packageSchema"`
	GraphProfile      string `json:"graphProfile"`
	GeocoderMagic     string `json:"geocoderMagic"`
	TilesFormat       string `json:"tilesFormat"`
	MinAppVersionCode int    `json:"minAppVersionCode,omitempty"`
}

// Manifest is the complete package description written as manifest.json.
type Manifest struct {
	SchemaVersion int           `json:"schemaVersion"`
	PackageID     string        `json:"packageId"`
	Region        Region        `json:"region"`
	Source        Source        `json:"source"`
	GeneratedAt   string        `json:"generatedAt"`
	Generator     Generator     `json:"generator"`
	Components    []Component   `json:"components"`
	Compatibility Compatibility `json:"compatibility"`
	Attribution   string        `json:"attribution"`
}

var (
	idPattern       = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{1,79}$`)
	datePattern     = regexp.MustCompile(`^([0-9]{6}|[0-9]{4}-[0-9]{2}-[0-9]{2})$`)
	hexPattern      = regexp.MustCompile(`^[0-9a-f]{64}$`)
	graphProfPattern = regexp.MustCompile(`^motorcycle\|[0-9]{1,12}$`)
)

// Component returns the named component.
func (m Manifest) Component(name string) (Component, bool) {
	for _, c := range m.Components {
		if c.Name == name {
			return c, true
		}
	}
	return Component{}, false
}

// Validate checks every invariant the Android importer also enforces. A
// manifest that passes here must be installable on a device.
func (m Manifest) Validate() error {
	if m.SchemaVersion != SchemaVersion {
		return fmt.Errorf("unsupported package schema version %d (want %d)", m.SchemaVersion, SchemaVersion)
	}
	if !idPattern.MatchString(m.PackageID) {
		return fmt.Errorf("invalid packageId %q", m.PackageID)
	}
	if !idPattern.MatchString(m.Region.ID) {
		return fmt.Errorf("invalid region id %q", m.Region.ID)
	}
	if strings.TrimSpace(m.Region.Name) == "" || len(m.Region.Name) > 120 {
		return fmt.Errorf("invalid region name %q", m.Region.Name)
	}
	if !datePattern.MatchString(m.Source.Date) {
		return fmt.Errorf("invalid source date %q (want YYMMDD or YYYY-MM-DD)", m.Source.Date)
	}
	if !hexPattern.MatchString(m.Source.SHA256) {
		return fmt.Errorf("invalid source sha256 %q", m.Source.SHA256)
	}
	if m.GeneratedAt == "" {
		return fmt.Errorf("missing generatedAt")
	}
	if m.Generator.PipelineFingerprint == "" {
		return fmt.Errorf("missing generator.pipelineFingerprint")
	}
	if strings.TrimSpace(m.Attribution) == "" {
		return fmt.Errorf("missing attribution")
	}
	if len(m.Components) != 3 {
		return fmt.Errorf("want exactly 3 components, got %d", len(m.Components))
	}
	seen := map[string]bool{}
	for _, c := range m.Components {
		if err := c.validate(); err != nil {
			return fmt.Errorf("component %q: %w", c.Name, err)
		}
		if seen[c.Name] {
			return fmt.Errorf("duplicate component %q", c.Name)
		}
		seen[c.Name] = true
	}
	for _, want := range []string{ComponentTiles, ComponentGraph, ComponentGeocoder} {
		if !seen[want] {
			return fmt.Errorf("missing component %q", want)
		}
	}
	if m.Compatibility.PackageSchema != SchemaVersion {
		return fmt.Errorf("compatibility.packageSchema %d does not match schemaVersion %d",
			m.Compatibility.PackageSchema, SchemaVersion)
	}
	if !graphProfPattern.MatchString(m.Compatibility.GraphProfile) {
		return fmt.Errorf("invalid compatibility.graphProfile %q", m.Compatibility.GraphProfile)
	}
	if m.Compatibility.GeocoderMagic != GeocoderMagic {
		return fmt.Errorf("invalid compatibility.geocoderMagic %q", m.Compatibility.GeocoderMagic)
	}
	if m.Compatibility.TilesFormat != FormatPMTilesV3 {
		return fmt.Errorf("invalid compatibility.tilesFormat %q", m.Compatibility.TilesFormat)
	}
	return nil
}

func (c Component) validate() error {
	switch c.Kind {
	case "file", "directory":
	default:
		return fmt.Errorf("invalid kind %q", c.Kind)
	}
	switch c.Name {
	case ComponentTiles:
		if c.Kind != "file" || c.Path != "tiles/basemap.pmtiles" || c.Format != FormatPMTilesV3 {
			return fmt.Errorf("tiles must be a %s file at tiles/basemap.pmtiles", FormatPMTilesV3)
		}
	case ComponentGraph:
		if c.Kind != "directory" || c.Path != "graph" || c.Format != FormatGraphDir {
			return fmt.Errorf("graph must be a %s directory at graph/", FormatGraphDir)
		}
		if !strings.HasPrefix(c.Profile, GraphProfilePrefix) {
			return fmt.Errorf("graph profile %q missing %q prefix", c.Profile, GraphProfilePrefix)
		}
	case ComponentGeocoder:
		if c.Kind != "file" || c.Path != "geocoder/geocoder.dat" || c.Format != FormatGeocoder {
			return fmt.Errorf("geocoder must be a %s file at geocoder/geocoder.dat", FormatGeocoder)
		}
	default:
		return fmt.Errorf("unknown component name %q", c.Name)
	}
	if c.Bytes <= 0 {
		return fmt.Errorf("bytes must be positive")
	}
	if !hexPattern.MatchString(c.SHA256) {
		return fmt.Errorf("invalid sha256 %q", c.SHA256)
	}
	if c.Kind == "directory" {
		if len(c.Files) == 0 {
			return fmt.Errorf("directory component needs a file list")
		}
		var total int64
		prev := ""
		for i, f := range c.Files {
			if f.Path == "" || path.IsAbs(f.Path) || strings.Contains(f.Path, "..") ||
				strings.HasPrefix(f.Path, "/") || strings.Contains(f.Path, `\`) {
				return fmt.Errorf("unsafe file path %q", f.Path)
			}
			if f.Bytes <= 0 {
				return fmt.Errorf("file %q has non-positive size", f.Path)
			}
			if !hexPattern.MatchString(f.SHA256) {
				return fmt.Errorf("file %q has invalid sha256", f.Path)
			}
			if i > 0 && f.Path <= prev {
				return fmt.Errorf("file list is not strictly sorted at %q", f.Path)
			}
			prev = f.Path
			total += f.Bytes
		}
		if total != c.Bytes {
			return fmt.Errorf("file list total %d does not match component bytes %d", total, c.Bytes)
		}
	} else if len(c.Files) != 0 {
		return fmt.Errorf("file component must not carry a file list")
	}
	return nil
}

// MarshalCanonical renders the manifest deterministically (struct field order,
// sorted component order) so package bytes are reproducible.
func (m Manifest) MarshalCanonical() ([]byte, error) {
	sort.SliceStable(m.Components, func(i, j int) bool { return m.Components[i].Name < m.Components[j].Name })
	return json.MarshalIndent(m, "", "  ")
}

// Parse decodes and validates a manifest document.
func Parse(data []byte) (Manifest, error) {
	var m Manifest
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&m); err != nil {
		return Manifest{}, fmt.Errorf("decode manifest: %w", err)
	}
	if err := m.Validate(); err != nil {
		return Manifest{}, err
	}
	return m, nil
}

// Load reads and validates a manifest file.
func Load(file string) (Manifest, error) {
	data, err := os.ReadFile(file)
	if err != nil {
		return Manifest{}, err
	}
	return Parse(data)
}
