package pipeline

import (
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/catalog"
	"github.com/organicmoto/curveMaps/map-server/internal/motomap"
)

// SourceInfo describes the resolved OSM extract for one build.
type SourceInfo struct {
	Path        string
	SHA256      string
	Bytes       int64
	URL         string
	DeclaredSHA string
}

// EnsureSource resolves the region's OSM extract into workDir, downloading it
// when the catalog points at a URL. Downloads are https-only, same-host
// redirects only, size-capped, and verified against the pinned hash when one is
// configured; the bytes must also begin with a real OSMHeader blob.
func (g *Generator) EnsureSource(ctx context.Context, region catalog.Region, workDir string, logf func(string, ...any)) (SourceInfo, error) {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	if region.Source.Path != "" {
		path := region.Source.Path
		if !filepath.IsAbs(path) {
			path = filepath.Join(g.Cfg.RepoRoot, path)
		}
		info, err := statSource(path)
		if err != nil {
			return SourceInfo{}, err
		}
		info.DeclaredSHA = region.Source.SHA256
		return info, nil
	}
	dest := filepath.Join(workDir, "source.osm.pbf")
	if st, err := os.Stat(dest); err == nil && st.Size() > 0 {
		if info, err := statSource(dest); err == nil {
			info.DeclaredSHA = region.Source.SHA256
			md5ok := true
			if region.Source.MD5 != "" {
				sum, merr := fileMD5(dest)
				md5ok = merr == nil && sum == region.Source.MD5
			}
			if md5ok && (region.Source.SHA256 == "" || region.Source.SHA256 == info.SHA256) {
				info.URL = region.Source.URL
				return info, nil
			}
		}
		logf("cached source does not match the pinned hash or md5; downloading again")
		_ = os.Remove(dest)
	}
	if err := os.MkdirAll(workDir, 0o755); err != nil {
		return SourceInfo{}, err
	}
	logf("downloading %s", region.Source.URL)
	if err := g.downloadSource(ctx, region.Source.URL, dest, region.Source.MD5, region.Source.MaxBytes); err != nil {
		return SourceInfo{}, err
	}
	info, err := statSource(dest)
	if err != nil {
		return SourceInfo{}, err
	}
	info.URL = region.Source.URL
	info.DeclaredSHA = region.Source.SHA256
	if info.DeclaredSHA != "" && info.DeclaredSHA != info.SHA256 {
		return SourceInfo{}, fmt.Errorf("source sha256 mismatch: downloaded %s, catalog pins %s", info.SHA256, info.DeclaredSHA)
	}
	return info, nil
}

func fileMD5(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := md5.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func statSource(path string) (SourceInfo, error) {
	f, err := os.Open(path)
	if err != nil {
		return SourceInfo{}, fmt.Errorf("open source extract: %w", err)
	}
	defer f.Close()
	head := make([]byte, 64*1024)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return SourceInfo{}, err
	}
	if err := motomap.CheckPBFMagic(head[:n]); err != nil {
		return SourceInfo{}, fmt.Errorf("%s: %w", path, err)
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return SourceInfo{}, err
	}
	h := sha256.New()
	size, err := io.Copy(h, f)
	if err != nil {
		return SourceInfo{}, err
	}
	return SourceInfo{Path: path, SHA256: hex.EncodeToString(h.Sum(nil)), Bytes: size}, nil
}

func (g *Generator) downloadSource(ctx context.Context, rawURL, dest, wantMD5 string, maxBytes int64) error {
	limit := g.Cfg.MaxSourceBytes
	if maxBytes > 0 && maxBytes < limit {
		limit = maxBytes
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return err
	}
	if u.Scheme != "https" {
		return fmt.Errorf("source url must use https, got %q", u.Scheme)
	}
	client := &http.Client{
		Timeout:   g.Cfg.HTTPTimeout,
		Transport: g.HTTPTransport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return fmt.Errorf("too many redirects")
			}
			if req.URL.Scheme != "https" {
				return fmt.Errorf("redirect to non-https url %s", req.URL)
			}
			if req.URL.Host != u.Host {
				return fmt.Errorf("cross-host redirect to %s is not allowed", req.URL.Host)
			}
			return nil
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("download source: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download source: unexpected status %s", resp.Status)
	}
	if resp.ContentLength > limit {
		return fmt.Errorf("source is %d bytes, above the %d byte cap", resp.ContentLength, limit)
	}
	tmp := dest + ".part"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer func() {
		out.Close()
		os.Remove(tmp)
	}()
	sum := md5.New()
	limited := io.LimitReader(resp.Body, limit+1)
	n, err := io.Copy(io.MultiWriter(out, sum), limited)
	if err != nil {
		return fmt.Errorf("download source: %w", err)
	}
	if n > limit {
		return fmt.Errorf("source exceeds the %d byte cap", limit)
	}
	if resp.ContentLength >= 0 && n != resp.ContentLength {
		return fmt.Errorf("download truncated: got %d of %d bytes", n, resp.ContentLength)
	}
	if wantMD5 != "" {
		if got := hex.EncodeToString(sum.Sum(nil)); got != wantMD5 {
			return fmt.Errorf("source md5 mismatch: downloaded %s, Geofabrik publishes %s", got, wantMD5)
		}
	}
	if err := out.Sync(); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	// Verify the PBF magic before the file can be picked up as a cached source.
	if _, err := statSource(tmp); err != nil {
		return err
	}
	return os.Rename(tmp, dest)
}

// freeDiskBytes reports free space on the filesystem containing path.
func freeDiskBytes(path string) (int64, error) {
	probe := path
	for {
		if _, err := os.Stat(probe); err == nil {
			break
		}
		parent := filepath.Dir(probe)
		if parent == probe {
			break
		}
		probe = parent
	}
	return diskFree(probe)
}

// CheckResources verifies the state filesystem has the configured headroom.
func (g *Generator) CheckResources() error {
	free, err := freeDiskBytes(g.Cfg.StateDir)
	if err != nil {
		return fmt.Errorf("check free disk: %w", err)
	}
	if free < g.Cfg.MinFreeDiskBytes {
		return fmt.Errorf("only %s free on the state filesystem; %s required",
			humanBytes(free), humanBytes(g.Cfg.MinFreeDiskBytes))
	}
	return nil
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}

// waitFor polls until condition is true or the timeout elapses.
func waitFor(ctx context.Context, timeout time.Duration, condition func() bool) error {
	deadline := time.Now().Add(timeout)
	for {
		if condition() {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out after %s", timeout)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
}
