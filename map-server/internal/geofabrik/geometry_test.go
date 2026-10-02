package geofabrik

import (
	"math"
	"testing"
)

func square(minLon, minLat, maxLon, maxLat float64) []point {
	return []point{{minLon, minLat}, {maxLon, minLat}, {maxLon, maxLat}, {minLon, maxLat}, {minLon, minLat}}
}

func TestPolygonContainsRespectsHoles(t *testing.T) {
	pg := polygon{square(0, 0, 10, 10), square(4, 4, 6, 6)}
	cases := []struct {
		lat, lon float64
		want     bool
	}{
		{1, 1, true},
		{5, 5, false}, // inside the hole
		{3.9, 5, true},
		{11, 5, false},
		{5, -0.1, false},
	}
	for _, c := range cases {
		if got := polygonContains(pg, c.lat, c.lon); got != c.want {
			t.Errorf("polygonContains(%v, %v) = %v, want %v", c.lat, c.lon, got, c.want)
		}
	}
}

func TestEntryAreaSubtractsHolesAndBoundsEveryPart(t *testing.T) {
	e := &entry{polys: []polygon{
		{square(0, 0, 10, 10), square(4, 4, 6, 6)},
		{square(20, 20, 21, 21)},
	}}
	e.computeShape()
	if math.Abs(e.area-(100-4+1)) > 1e-9 {
		t.Fatalf("area = %v, want 97", e.area)
	}
	if e.bbox != [4]float64{0, 0, 21, 21} {
		t.Fatalf("bbox = %v", e.bbox)
	}
	if !e.contains(20.5, 20.5) || e.contains(15, 15) || e.contains(5, 5) {
		t.Fatal("multipolygon containment is wrong")
	}
}

func TestParseGeometryAcceptsPolygonAndMultiPolygon(t *testing.T) {
	poly := parseGeometry("Polygon", []byte(`[[[0,0],[1,0],[1,1],[0,0]]]`))
	multi := parseGeometry("MultiPolygon", []byte(`[[[[0,0],[1,0],[1,1],[0,0]]],[[[5,5],[6,5],[6,6],[5,5]]]]`))
	if len(poly) != 1 || len(multi) != 2 {
		t.Fatalf("polygon parts = %d, multipolygon parts = %d", len(poly), len(multi))
	}
	if parseGeometry("Point", []byte(`[0,0]`)) != nil || parseGeometry("Polygon", []byte(`[[[0]]]`)) != nil {
		t.Fatal("unsupported or malformed geometry must be ignored")
	}
}

func TestFold(t *testing.T) {
	cases := map[string]string{
		"Île-de-France":     "ile de france",
		"Baden-Württemberg": "baden wurttemberg",
		"  AU-TAS ":         "au tas",
		"Großbritannien":    "grossbritannien",
		"Łódzkie":           "lodzkie",
	}
	for in, want := range cases {
		if got := fold(in); got != want {
			t.Errorf("fold(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestDisplayNameRepairsIDNames(t *testing.T) {
	cases := map[[2]string]string{
		{"us/california", "us/california"}:                     "California",
		{"us/district-of-columbia", "us/district-of-columbia"}: "District Of Columbia",
		{"tasmania", ""}:          "Tasmania",
		{"us/georgia", "Georgia"}: "Georgia",
	}
	for in, want := range cases {
		if got := displayName(in[0], in[1]); got != want {
			t.Errorf("displayName(%q, %q) = %q, want %q", in[0], in[1], got, want)
		}
	}
}
