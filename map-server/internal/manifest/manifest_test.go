package manifest

import (
	"encoding/json"
	"strings"
	"testing"
)

func valid() Manifest {
	return Manifest{
		SchemaVersion: SchemaVersion,
		PackageID:     "queensland-260928-0123456789ab",
		Region:        Region{ID: "queensland", Name: "Queensland", Coverage: "QLD"},
		Source:        Source{Date: "260928", URL: "https://example.invalid/x.osm.pbf", SHA256: strings.Repeat("a", 64)},
		GeneratedAt:   "2026-10-01T00:00:00Z",
		Generator:     Generator{Name: "map-server", Version: "1", PipelineFingerprint: "fp"},
		Components: []Component{
			{Name: ComponentTiles, Kind: "file", Path: "tiles/basemap.pmtiles", Format: FormatPMTilesV3,
				Bytes: 10, SHA256: strings.Repeat("b", 64)},
			{Name: ComponentGraph, Kind: "directory", Path: "graph", Format: FormatGraphDir,
				Profile: "motorcycle|198752012", Bytes: 3,
				SHA256: strings.Repeat("c", 64),
				Files:  []FileEntry{{Path: "nodes", Bytes: 3, SHA256: strings.Repeat("d", 64)}}},
			{Name: ComponentGeocoder, Kind: "file", Path: "geocoder/geocoder.dat", Format: FormatGeocoder,
				Bytes: 10, SHA256: strings.Repeat("e", 64)},
		},
		Compatibility: Compatibility{
			PackageSchema: SchemaVersion, GraphProfile: "motorcycle|198752012",
			GeocoderMagic: GeocoderMagic, TilesFormat: FormatPMTilesV3,
		},
		Attribution: Attribution,
	}
}

func TestValidateAcceptsCompleteManifest(t *testing.T) {
	if err := valid().Validate(); err != nil {
		t.Fatalf("valid manifest rejected: %v", err)
	}
}

func TestValidateRejectsEveryBrokenField(t *testing.T) {
	cases := map[string]func(*Manifest){
		"schema":        func(m *Manifest) { m.SchemaVersion = 99 },
		"package id":    func(m *Manifest) { m.PackageID = "Not Valid" },
		"region id":     func(m *Manifest) { m.Region.ID = "../etc" },
		"region name":   func(m *Manifest) { m.Region.Name = "" },
		"source date":   func(m *Manifest) { m.Source.Date = "yesterday" },
		"source sha":    func(m *Manifest) { m.Source.SHA256 = "short" },
		"generated at":  func(m *Manifest) { m.GeneratedAt = "" },
		"fingerprint":   func(m *Manifest) { m.Generator.PipelineFingerprint = "" },
		"attribution":   func(m *Manifest) { m.Attribution = "" },
		"component set": func(m *Manifest) { m.Components = m.Components[:2] },
		"graph profile": func(m *Manifest) { m.Compatibility.GraphProfile = "car|1" },
		"geocoder":      func(m *Manifest) { m.Compatibility.GeocoderMagic = "WRONG" },
		"tiles format":  func(m *Manifest) { m.Compatibility.TilesFormat = "mbtiles" },
		"file total": func(m *Manifest) {
			for i := range m.Components {
				if m.Components[i].Name == ComponentGraph {
					m.Components[i].Bytes = 99
				}
			}
		},
		"unsorted files": func(m *Manifest) {
			for i := range m.Components {
				if m.Components[i].Name == ComponentGraph {
					m.Components[i].Files = []FileEntry{
						{Path: "z", Bytes: 1, SHA256: strings.Repeat("1", 64)},
						{Path: "a", Bytes: 1, SHA256: strings.Repeat("2", 64)},
					}
					m.Components[i].Bytes = 2
				}
			}
		},
		"traversal file": func(m *Manifest) {
			for i := range m.Components {
				if m.Components[i].Name == ComponentGraph {
					m.Components[i].Files = []FileEntry{
						{Path: "../escape", Bytes: 1, SHA256: strings.Repeat("1", 64)},
					}
				}
			}
		},
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			m := valid()
			mutate(&m)
			if err := m.Validate(); err == nil {
				t.Fatal("broken manifest accepted")
			}
		})
	}
}

func TestParseRejectsUnknownFieldsAndRoundTrips(t *testing.T) {
	m := valid()
	doc, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := Parse(doc)
	if err != nil {
		t.Fatalf("round trip: %v", err)
	}
	if parsed.PackageID != m.PackageID {
		t.Fatal("package id lost")
	}
	var generic map[string]any
	if err := json.Unmarshal(doc, &generic); err != nil {
		t.Fatal(err)
	}
	generic["surprise"] = true
	withExtra, _ := json.Marshal(generic)
	if _, err := Parse(withExtra); err == nil {
		t.Fatal("unknown field accepted")
	}
}

func TestMarshalCanonicalSortsComponents(t *testing.T) {
	m := valid()
	m.Components[0], m.Components[2] = m.Components[2], m.Components[0]
	doc, err := m.MarshalCanonical()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(doc), `"name": "geocoder"`) {
		t.Fatal("components not sorted in canonical output")
	}
	first := string(doc)
	doc2, _ := m.MarshalCanonical()
	if first != string(doc2) {
		t.Fatal("canonical marshal is not deterministic")
	}
}
