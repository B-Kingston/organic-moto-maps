// Package motomap writes and verifies `.motomap` regional data packages: a
// ZIP64 archive with a manifest.json plus the PMTiles basemap, the GraphHopper
// graph cache, and the offline geocoder index.
package motomap

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
)

// FileDigest pins one file's size and content hash.
type FileDigest struct {
	Path   string
	Bytes  int64
	SHA256 string
}

// Digest summarizes a file or directory.
type Digest struct {
	Bytes  int64
	SHA256 string
	Files  []FileDigest // sorted by path; empty for a single file
}

// DigestFile hashes one file.
func DigestFile(file string) (Digest, error) {
	sum, size, err := hashFile(file)
	if err != nil {
		return Digest{}, err
	}
	return Digest{Bytes: size, SHA256: sum}, nil
}

// DigestDir walks root and produces a deterministic content digest: SHA-256
// over sorted `relpath \0 bytes \0 sha256 \n` lines. Symlinks are rejected:
// a package never carries them.
func DigestDir(root string) (Digest, error) {
	var files []FileDigest
	err := filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		if d.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlink not allowed in package content: %s", p)
		}
		if !d.Type().IsRegular() {
			return fmt.Errorf("non-regular file not allowed in package content: %s", p)
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		sum, size, err := hashFile(p)
		if err != nil {
			return err
		}
		files = append(files, FileDigest{Path: rel, Bytes: size, SHA256: sum})
		return nil
	})
	if err != nil {
		return Digest{}, err
	}
	if len(files) == 0 {
		return Digest{}, fmt.Errorf("directory %s contains no files", root)
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	h := sha256.New()
	var total int64
	for _, f := range files {
		fmt.Fprintf(h, "%s\x00%d\x00%s\n", f.Path, f.Bytes, f.SHA256)
		total += f.Bytes
	}
	return Digest{Bytes: total, SHA256: hex.EncodeToString(h.Sum(nil)), Files: files}, nil
}

// ManifestFiles converts a digest's file list for the manifest schema.
func (d Digest) ManifestFiles() []manifest.FileEntry {
	out := make([]manifest.FileEntry, 0, len(d.Files))
	for _, f := range d.Files {
		out = append(out, manifest.FileEntry{Path: f.Path, Bytes: f.Bytes, SHA256: f.SHA256})
	}
	return out
}

func hashFile(file string) (string, int64, error) {
	f, err := os.Open(file)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// validateRelativePath rejects anything that could escape the package root or
// confuse the importer.
func validateRelativePath(p string) error {
	if p == "" {
		return fmt.Errorf("empty path")
	}
	if strings.HasPrefix(p, "/") || strings.Contains(p, `\`) || strings.ContainsRune(p, 0) {
		return fmt.Errorf("unsafe path %q", p)
	}
	for _, part := range strings.Split(p, "/") {
		if part == "" || part == "." || part == ".." {
			return fmt.Errorf("unsafe path segment in %q", p)
		}
	}
	return nil
}
