package pipeline

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
)

func tinyPBF() []byte {
	pbf := []byte("OSMHeader-stub")
	return append([]byte{0, 0, 0, byte(len(pbf))}, pbf...)
}

func md5Hex(b []byte) string {
	sum := md5.Sum(b)
	return hex.EncodeToString(sum[:])
}

// sourceFixture serves tinyPBF over TLS and returns a generator that trusts it.
func sourceFixture(t *testing.T, maxSource int64) (*Generator, string, *int32) {
	t.Helper()
	var hits int32
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		_, _ = w.Write(tinyPBF())
	}))
	t.Cleanup(srv.Close)
	gen := &Generator{
		Cfg:           Config{MaxSourceBytes: maxSource, HTTPTimeout: 10 * time.Second},
		HTTPTransport: srv.Client().Transport,
	}
	return gen, srv.URL + "/x.osm.pbf", &hits
}

func TestDownloadVerifiesMD5(t *testing.T) {
	gen, url, _ := sourceFixture(t, 1<<20)
	dir := t.TempDir()
	region := catalog.Region{ID: "x1", Name: "X", Source: catalog.Source{URL: url, Date: "260928", MD5: md5Hex(tinyPBF())}}
	info, err := gen.EnsureSource(context.Background(), region, dir, nil)
	if err != nil {
		t.Fatalf("matching md5 rejected: %v", err)
	}
	if info.Bytes != int64(len(tinyPBF())) {
		t.Fatalf("bytes %d", info.Bytes)
	}

	bad := t.TempDir()
	region.Source.MD5 = strings.Repeat("0", 32)
	_, err = gen.EnsureSource(context.Background(), region, bad, nil)
	if err == nil || !strings.Contains(err.Error(), "source md5 mismatch") || !strings.Contains(err.Error(), strings.Repeat("0", 32)) {
		t.Fatalf("want md5 mismatch, got %v", err)
	}
	if _, statErr := os.Stat(filepath.Join(bad, "source.osm.pbf")); !os.IsNotExist(statErr) {
		t.Fatal("mismatching download was renamed into place")
	}
}

func TestCachedSourceIsRecheckedAgainstMD5(t *testing.T) {
	gen, url, hits := sourceFixture(t, 1<<20)
	dir := t.TempDir()
	// A cached, valid-PBF file with the wrong digest must not be reused.
	stale := append(tinyPBF(), []byte("-different")...)
	if err := os.WriteFile(filepath.Join(dir, "source.osm.pbf"), stale, 0o644); err != nil {
		t.Fatal(err)
	}
	region := catalog.Region{ID: "x1", Name: "X", Source: catalog.Source{URL: url, Date: "260928", MD5: md5Hex(tinyPBF())}}
	if _, err := gen.EnsureSource(context.Background(), region, dir, nil); err != nil {
		t.Fatal(err)
	}
	if atomic.LoadInt32(hits) != 1 {
		t.Fatalf("stale cache not re-downloaded, hits=%d", *hits)
	}
	// Now the cache matches: no second download.
	if _, err := gen.EnsureSource(context.Background(), region, dir, nil); err != nil {
		t.Fatal(err)
	}
	if atomic.LoadInt32(hits) != 1 {
		t.Fatalf("matching cache re-downloaded, hits=%d", *hits)
	}
}

func TestDownloadHonoursRegionMaxBytes(t *testing.T) {
	gen, url, _ := sourceFixture(t, 1<<20)
	region := catalog.Region{ID: "x1", Name: "X", Source: catalog.Source{URL: url, Date: "260928", MaxBytes: 4}}
	_, err := gen.EnsureSource(context.Background(), region, t.TempDir(), nil)
	if err == nil || !strings.Contains(err.Error(), "4 byte cap") {
		t.Fatalf("want region cap error, got %v", err)
	}
	// The server-wide cap still wins when it is smaller.
	gen.Cfg.MaxSourceBytes = 3
	region.Source.MaxBytes = 1 << 20
	_, err = gen.EnsureSource(context.Background(), region, t.TempDir(), nil)
	if err == nil || !strings.Contains(err.Error(), "3 byte cap") {
		t.Fatalf("want server cap error, got %v", err)
	}
}
