// Package geofabrik resolves a place name or a coordinate to one of the
// extracts Geofabrik publishes (download.geofabrik.de/index-v1.json), checks
// it against the server's size limit, and pins the dated download URL that a
// build will fetch. Callers never supply a URL: every download URL comes from
// Geofabrik's own index and must stay on the allowed host.
package geofabrik

import (
	"errors"
	"strings"
)

// Errors returned by the service. Callers map them to HTTP statuses.
var (
	// ErrUnknownExtract: the id is not in Geofabrik's index.
	ErrUnknownExtract = errors.New("unknown extract")
	// ErrTooLarge: the extract is above the server's size limit.
	ErrTooLarge = errors.New("extract is larger than this server builds")
	// ErrNotBuildable: the extract can never be built here (a continent).
	ErrNotBuildable = errors.New("extract is not buildable on this server")
	// ErrUpstream: Geofabrik could not be reached or answered unexpectedly.
	ErrUpstream = errors.New("geofabrik is unavailable")
)

// Ref names another extract (used to suggest smaller choices).
type Ref struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// Extract is one entry of Geofabrik's index.
type Extract struct {
	// ID is Geofabrik's id, e.g. "tasmania" or "us/georgia".
	ID     string `json:"id"`
	Name   string `json:"name"`
	Parent string `json:"parent,omitempty"`
	// Path is the ancestors' names, top level first, e.g.
	// ["Australia and Oceania", "Australia"] for Tasmania.
	Path []string `json:"path,omitempty"`
	// ISO holds the ISO 3166-1 alpha-2 and ISO 3166-2 codes from the index.
	ISO      []string `json:"iso,omitempty"`
	Children []Ref    `json:"children,omitempty"`
	// LatestURL is the index's "-latest.osm.pbf" URL (never sent to clients).
	LatestURL string `json:"-"`
}

// Candidate is an extract annotated with whether this server will build it.
type Candidate struct {
	Extract
	// RegionID is the catalog id a build of this extract uses.
	RegionID string `json:"regionId"`
	// Bytes is the current extract size, 0 when unknown.
	Bytes    int64  `json:"bytes,omitempty"`
	Eligible bool   `json:"eligible"`
	Reason   string `json:"reason,omitempty"`
}

// Resolved is an eligible extract pinned to one dated Geofabrik file.
type Resolved struct {
	Extract Extract
	// URL is the dated https URL, e.g.
	// https://download.geofabrik.de/australia-oceania/australia/tasmania-261001.osm.pbf
	URL string
	// Date is the file's YYMMDD stamp taken from the dated file name.
	Date  string
	Bytes int64
	// MD5 is Geofabrik's published digest of URL (lowercase hex), "" if absent.
	MD5 string
}

// RegionID maps a Geofabrik id to a catalog region id ("us/georgia" ->
// "us-georgia"). The result always satisfies the catalog id pattern for
// Geofabrik's ids (lowercase letters, digits, '-', '/').
func RegionID(extractID string) string {
	return strings.ReplaceAll(strings.ToLower(extractID), "/", "-")
}
