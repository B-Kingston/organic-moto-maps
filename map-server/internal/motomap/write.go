package motomap

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/organicmoto/curveMaps/map-server/internal/manifest"
)

// Write creates the package at dest atomically: content is streamed into
// dest+".tmp", fsynced, and renamed over dest. sources maps a component name
// (tiles, graph, geocoder) to its file or directory on disk.
func Write(dest string, m manifest.Manifest, sources map[string]string, logf func(string, ...any)) error {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	if err := m.Validate(); err != nil {
		return fmt.Errorf("refusing to write invalid manifest: %w", err)
	}
	for _, c := range m.Components {
		if sources[c.Name] == "" {
			return fmt.Errorf("no source path for component %q", c.Name)
		}
	}
	doc, err := m.MarshalCanonical()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	tmp := dest + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer func() {
		out.Close()
		os.Remove(tmp)
	}()
	zw := zip.NewWriter(out)

	if err := writeEntry(zw, "manifest.json", bytes.NewReader(doc), zip.Deflate); err != nil {
		return err
	}
	components := append([]manifest.Component(nil), m.Components...)
	sort.Slice(components, func(i, j int) bool { return components[i].Name < components[j].Name })
	var written int64
	for _, c := range components {
		root := sources[c.Name]
		if c.Kind == "file" {
			info, err := os.Stat(root)
			if err != nil {
				return err
			}
			if info.Size() != c.Bytes {
				return fmt.Errorf("component %s: source size %d does not match manifest %d", c.Name, info.Size(), c.Bytes)
			}
			f, err := os.Open(root)
			if err != nil {
				return err
			}
			err = writeEntry(zw, c.Path, f, methodFor(c.Name))
			f.Close()
			if err != nil {
				return err
			}
			written += c.Bytes
			logf("packed %s (%d bytes)", c.Path, c.Bytes)
			continue
		}
		for _, fe := range c.Files {
			src := filepath.Join(root, filepath.FromSlash(fe.Path))
			info, err := os.Stat(src)
			if err != nil {
				return err
			}
			if info.Size() != fe.Bytes {
				return fmt.Errorf("component %s file %s: size %d does not match manifest %d", c.Name, fe.Path, info.Size(), fe.Bytes)
			}
			f, err := os.Open(src)
			if err != nil {
				return err
			}
			err = writeEntry(zw, c.Path+"/"+fe.Path, f, methodFor(c.Name))
			f.Close()
			if err != nil {
				return err
			}
			written += fe.Bytes
		}
		logf("packed %s/ (%d files, %d bytes)", c.Path, len(c.Files), c.Bytes)
	}
	if err := zw.Close(); err != nil {
		return err
	}
	if err := out.Sync(); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, dest); err != nil {
		return err
	}
	logf("wrote %s (%d bytes payload)", dest, written)
	return nil
}

func methodFor(component string) uint16 {
	// PMTiles archives are already internally compressed; deflating them again
	// burns CPU for almost no gain. The graph and geocoder compress well.
	if component == manifest.ComponentTiles {
		return zip.Store
	}
	return zip.Deflate
}

func writeEntry(zw *zip.Writer, name string, r io.Reader, method uint16) error {
	if err := validateRelativePath(name); err != nil {
		return err
	}
	hdr := &zip.FileHeader{Name: name, Method: method}
	// Zero timestamp keeps package bytes reproducible.
	hdr.Modified = time.Time{}
	w, err := zw.CreateHeader(hdr)
	if err != nil {
		return err
	}
	_, err = io.Copy(w, r)
	return err
}

// WriteSidecar writes the conventional `<package>.sha256` file next to the
// package (same format as the existing PMTiles sidecar: a bare hex digest).
func WriteSidecar(packagePath string) (string, error) {
	sum, _, err := hashFile(packagePath)
	if err != nil {
		return "", err
	}
	sidecar := packagePath + ".sha256"
	if err := os.WriteFile(sidecar, []byte(sum+"\n"), 0o644); err != nil {
		return "", err
	}
	return sum, nil
}

// BuildComponent digests a file or directory source into a manifest component.
func BuildComponent(name, sourcePath, format, profile string) (manifest.Component, error) {
	info, err := os.Stat(sourcePath)
	if err != nil {
		return manifest.Component{}, err
	}
	c := manifest.Component{Name: name, Format: format, Profile: profile}
	if info.IsDir() {
		d, err := DigestDir(sourcePath)
		if err != nil {
			return manifest.Component{}, err
		}
		c.Kind = "directory"
		c.Path = name
		c.Bytes = d.Bytes
		c.SHA256 = d.SHA256
		c.Files = d.ManifestFiles()
	} else {
		d, err := DigestFile(sourcePath)
		if err != nil {
			return manifest.Component{}, err
		}
		c.Kind = "file"
		switch name {
		case manifest.ComponentTiles:
			c.Path = "tiles/basemap.pmtiles"
		case manifest.ComponentGeocoder:
			c.Path = "geocoder/geocoder.dat"
		default:
			c.Path = name
		}
		c.Bytes = d.Bytes
		c.SHA256 = d.SHA256
	}
	return c, nil
}

// DigestString hashes a byte string (used for fingerprints and tokens).
func DigestString(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}
