package geofabrik

import (
	"encoding/json"
	"fmt"
	"math"
	"net/url"
	"sort"
	"strings"
	"unicode"
)

type point struct{ lon, lat float64 }

// polygon is one outer ring followed by its holes.
type polygon [][]point

type entry struct {
	Extract
	polys []polygon
	bbox  [4]float64 // minLon, minLat, maxLon, maxLat
	area  float64
	terms []string // folded id, name and ISO codes
}

type index struct {
	list []*entry
	byID map[string]*entry
}

type rawFeature struct {
	Properties struct {
		ID     string            `json:"id"`
		Parent string            `json:"parent"`
		Name   string            `json:"name"`
		ISO1   []string          `json:"iso3166-1:alpha2"`
		ISO2   []string          `json:"iso3166-2"`
		URLs   map[string]string `json:"urls"`
	} `json:"properties"`
	Geometry struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	} `json:"geometry"`
}

// parseIndex reads Geofabrik's index-v1.json, skipping entries it cannot
// trust: no id, no pbf URL, or a pbf URL outside the allowed https host.
func parseIndex(data []byte, allowedHost string) (*index, error) {
	var doc struct {
		Features []rawFeature `json:"features"`
	}
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, fmt.Errorf("parse index: %w", err)
	}
	idx := &index{byID: map[string]*entry{}}
	for _, f := range doc.Features {
		p := f.Properties
		if p.ID == "" || p.URLs["pbf"] == "" {
			continue
		}
		if !allowedLatestURL(p.URLs["pbf"], allowedHost) {
			continue
		}
		if _, dup := idx.byID[p.ID]; dup {
			continue
		}
		e := &entry{Extract: Extract{
			ID: p.ID, Name: displayName(p.ID, p.Name), Parent: p.Parent, LatestURL: p.URLs["pbf"],
		}}
		e.ISO = append(append([]string{}, p.ISO1...), p.ISO2...)
		e.polys = parseGeometry(f.Geometry.Type, f.Geometry.Coordinates)
		e.computeShape()
		e.terms = []string{fold(e.ID), fold(e.Name)}
		for _, c := range e.ISO {
			e.terms = append(e.terms, fold(c))
		}
		idx.byID[e.ID] = e
		idx.list = append(idx.list, e)
	}
	kids := map[string][]Ref{}
	for _, e := range idx.list {
		if e.Parent != "" {
			kids[e.Parent] = append(kids[e.Parent], Ref{ID: e.ID, Name: e.Name})
		}
	}
	for _, e := range idx.list {
		e.Children = kids[e.ID]
		sort.Slice(e.Children, func(i, j int) bool {
			if e.Children[i].Name != e.Children[j].Name {
				return e.Children[i].Name < e.Children[j].Name
			}
			return e.Children[i].ID < e.Children[j].ID
		})
		var path []string
		seen := map[string]bool{e.ID: true}
		for p := idx.byID[e.Parent]; p != nil && !seen[p.ID]; p = idx.byID[p.Parent] {
			seen[p.ID] = true
			path = append([]string{p.Name}, path...)
		}
		e.Path = path
	}
	return idx, nil
}

func allowedLatestURL(raw, host string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host != host || u.RawQuery != "" {
		return false
	}
	return strings.HasSuffix(u.Path, "-latest.osm.pbf")
}

func parseGeometry(typ string, coords json.RawMessage) []polygon {
	switch typ {
	case "Polygon":
		var p [][][]float64
		if json.Unmarshal(coords, &p) != nil {
			return nil
		}
		if pg := toPolygon(p); pg != nil {
			return []polygon{pg}
		}
	case "MultiPolygon":
		var mp [][][][]float64
		if json.Unmarshal(coords, &mp) != nil {
			return nil
		}
		var out []polygon
		for _, p := range mp {
			if pg := toPolygon(p); pg != nil {
				out = append(out, pg)
			}
		}
		return out
	}
	return nil
}

func toPolygon(rings [][][]float64) polygon {
	var pg polygon
	for _, r := range rings {
		var ring []point
		for _, c := range r {
			if len(c) < 2 {
				return nil
			}
			ring = append(ring, point{c[0], c[1]})
		}
		if len(ring) < 3 {
			continue
		}
		pg = append(pg, ring)
	}
	if len(pg) == 0 {
		return nil
	}
	return pg
}

func (e *entry) computeShape() {
	e.bbox = [4]float64{math.Inf(1), math.Inf(1), math.Inf(-1), math.Inf(-1)}
	for _, pg := range e.polys {
		for i, ring := range pg {
			a := ringArea(ring)
			if i == 0 {
				e.area += a
			} else {
				e.area -= a
			}
			for _, p := range ring {
				e.bbox[0] = math.Min(e.bbox[0], p.lon)
				e.bbox[1] = math.Min(e.bbox[1], p.lat)
				e.bbox[2] = math.Max(e.bbox[2], p.lon)
				e.bbox[3] = math.Max(e.bbox[3], p.lat)
			}
		}
	}
}

func ringArea(r []point) float64 {
	var s float64
	for i, j := 0, len(r)-1; i < len(r); j, i = i, i+1 {
		s += r[j].lon*r[i].lat - r[i].lon*r[j].lat
	}
	return math.Abs(s) / 2
}

func (e *entry) contains(lat, lon float64) bool {
	if len(e.polys) == 0 || lon < e.bbox[0] || lon > e.bbox[2] || lat < e.bbox[1] || lat > e.bbox[3] {
		return false
	}
	for _, pg := range e.polys {
		if polygonContains(pg, lat, lon) {
			return true
		}
	}
	return false
}

// polygonContains uses even-odd ray casting across the outer ring and its
// holes, so a point inside a hole is outside the polygon.
func polygonContains(pg polygon, lat, lon float64) bool {
	in := false
	for _, ring := range pg {
		for i, j := 0, len(ring)-1; i < len(ring); j, i = i, i+1 {
			a, b := ring[i], ring[j]
			if (a.lat > lat) != (b.lat > lat) &&
				lon < (b.lon-a.lon)*(lat-a.lat)/(b.lat-a.lat)+a.lon {
				in = !in
			}
		}
	}
	return in
}

var foldTable = map[rune]string{
	'ß': "ss", 'æ': "ae", 'œ': "oe", 'ø': "o", 'đ': "d", 'ð': "d", 'þ': "th", 'ł': "l", 'ı': "i",
}

var accentGroups = map[rune]string{
	'a': "àáâãäåāăą", 'c': "çćĉċč", 'd': "ď", 'e': "èéêëēĕėęě", 'g': "ĝğġģ", 'h': "ĥ",
	'i': "ìíîïĩīĭį", 'j': "ĵ", 'k': "ķ", 'l': "ĺļľ", 'n': "ñńņň", 'o': "òóôõöōŏő",
	'r': "ŕŗř", 's': "śŝşš", 't': "ţť", 'u': "ùúûüũūŭůűų", 'w': "ŵ", 'y': "ýÿŷ", 'z': "źżž",
}

var accentMap = func() map[rune]rune {
	m := map[rune]rune{}
	for base, group := range accentGroups {
		for _, r := range group {
			m[r] = base
		}
	}
	return m
}()

// fold lowercases, strips diacritics and turns every run of non-alphanumeric
// characters into one space.
func fold(s string) string {
	var b strings.Builder
	space := true
	for _, r := range strings.ToLower(s) {
		if p, ok := accentMap[r]; ok {
			r = p
		}
		if rep, ok := foldTable[r]; ok {
			b.WriteString(rep)
			space = false
			continue
		}
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			space = false
		} else if !space {
			b.WriteByte(' ')
			space = true
		}
	}
	return strings.TrimRight(b.String(), " ")
}

// matchRank is 0 exact, 1 prefix, 2 substring, -1 no match.
func (e *entry) matchRank(q string) int {
	best := -1
	for _, t := range e.terms {
		r := -1
		switch {
		case t == q:
			r = 0
		case strings.HasPrefix(t, q):
			r = 1
		case strings.Contains(t, q):
			r = 2
		}
		if r >= 0 && (best < 0 || r < best) {
			best = r
		}
	}
	return best
}

// displayName repairs index entries whose name is missing or just the id
// (Geofabrik lists every US state as e.g. "us/california"): the last id
// segment is title-cased instead ("district-of-columbia" -> "District Of Columbia").
func displayName(id, name string) string {
	name = strings.TrimSpace(name)
	if name != "" && name != id {
		return name
	}
	words := strings.Split(id[strings.LastIndex(id, "/")+1:], "-")
	for i, w := range words {
		if w != "" {
			words[i] = strings.ToUpper(w[:1]) + w[1:]
		}
	}
	return strings.Join(words, " ")
}
