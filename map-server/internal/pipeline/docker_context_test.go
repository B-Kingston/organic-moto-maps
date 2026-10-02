package pipeline

import (
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// TestDockerImageCarriesFingerprintInputs pins the contract between the
// generation fingerprint and the Docker image. Every file ToolHashes consumes
// must (a) survive map-server/Dockerfile.dockerignore and (b) be copied into
// the runtime stage, or FingerprintFor fails and every build request errors
// before a single tool runs.
//
// Regression this guards: the runtime image copied only tools/ inputs, so the
// server started fine but could not hash geocoder-tool sources.
func TestDockerImageCarriesFingerprintInputs(t *testing.T) {
	root := repoRoot(t)
	hashes, err := ToolHashes(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(hashes) == 0 {
		t.Fatal("ToolHashes returned no inputs")
	}

	ignoreDoc, err := os.ReadFile(filepath.Join(root, "map-server", "Dockerfile.dockerignore"))
	if err != nil {
		t.Fatal(err)
	}
	patterns := parseDockerignore(string(ignoreDoc))

	dockerfileDoc, err := os.ReadFile(filepath.Join(root, "map-server", "Dockerfile"))
	if err != nil {
		t.Fatal(err)
	}
	copies := runtimeStageCopies(string(dockerfileDoc))

	for rel := range hashes {
		if dockerignoreExcludes(patterns, rel) {
			t.Errorf("Docker build context excludes fingerprint input %s; the image cannot hash it", rel)
		}
		if !copiedIntoImage(copies, rel) {
			t.Errorf("runtime stage does not COPY fingerprint input %s", rel)
		}
	}

	// The build context must also keep the Dockerfile and the wrapper the
	// builder stages need, and keep runtime state out.
	for _, rel := range []string{
		"map-server/Dockerfile",
		"map-server/Dockerfile.dockerignore",
		"map-server/catalog.example.json",
		"map-server/config.docker.json",
		"gradlew",
		"gradle/wrapper/gradle-wrapper.properties",
		"gradle/libs.versions.toml",
		"geocoder-tool/standalone/settings.gradle.kts",
		"tools/tiles/build-tiles.sh",
	} {
		if dockerignoreExcludes(patterns, rel) {
			t.Errorf("Docker build context excludes required file %s", rel)
		}
	}
	for _, rel := range []string{
		"map-server/state/jobs.json",
		"map-server/dist/queensland.motomap",
		"geocoder-tool/build/install/geocoder-tool/bin/geocoder-tool",
		"geocoder-tool/.gradle/daemon.log",
		"data/queensland.osm.pbf",
		"app/build.gradle.kts",
	} {
		if !dockerignoreExcludes(patterns, rel) {
			t.Errorf("Docker build context unexpectedly includes %s", rel)
		}
	}
}

// TestDockerignoreMatcherMirrorsParentDirectoryMatching documents the rule the
// allowlist has to obey: a file is only in the context when its own path and
// every ancestor directory survive the patterns.
func TestDockerignoreMatcherMirrorsParentDirectoryMatching(t *testing.T) {
	old := parseDockerignore(strings.Join([]string{
		"**",
		"!map-server/**",
		"!geocoder-tool/src/**",
	}, "\n"))
	for _, rel := range []string{
		"geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt",
		"map-server/Dockerfile",
	} {
		if !dockerignoreExcludes(old, rel) {
			t.Fatalf("matcher does not reproduce Docker's parent-directory exclusion for %s", rel)
		}
	}

	fixed := parseDockerignore(strings.Join([]string{
		"**",
		"!map-server",
		"!map-server/**",
		"!geocoder-tool",
		"!geocoder-tool/src",
		"!geocoder-tool/src/**",
	}, "\n"))
	for _, rel := range []string{
		"geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt",
		"map-server/Dockerfile",
	} {
		if dockerignoreExcludes(fixed, rel) {
			t.Fatalf("re-included parent still excludes %s", rel)
		}
	}
}

type dockerignorePattern struct {
	exclude bool
	re      *regexp.Regexp
}

func parseDockerignore(doc string) []dockerignorePattern {
	var out []dockerignorePattern
	for _, line := range strings.Split(doc, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		exclude := true
		if strings.HasPrefix(line, "!") {
			exclude = false
			line = strings.TrimPrefix(line, "!")
		}
		out = append(out, dockerignorePattern{exclude: exclude, re: dockerignoreRegexp(line)})
	}
	return out
}

// dockerignoreRegexp compiles the subset of .dockerignore glob syntax the file
// uses: `**` (any number of path segments) and `*` (one segment).
func dockerignoreRegexp(pattern string) *regexp.Regexp {
	var b strings.Builder
	b.WriteString("^")
	for i := 0; i < len(pattern); {
		switch {
		case strings.HasPrefix(pattern[i:], "**/"):
			b.WriteString("(?:.*/)?")
			i += 3
		case strings.HasPrefix(pattern[i:], "**"):
			b.WriteString(".*")
			i += 2
		case pattern[i] == '*':
			b.WriteString("[^/]*")
			i++
		default:
			b.WriteString(regexp.QuoteMeta(string(pattern[i])))
			i++
		}
	}
	b.WriteString("$")
	return regexp.MustCompile(b.String())
}

// dockerignoreExcludes mirrors Docker's evaluation: patterns are matched
// against the path and every parent directory (moby patternmatcher's
// MatchesOrParentMatches), and the last matching pattern wins. A negated
// pattern for a child therefore cannot rescue a file whose parent is excluded.
func dockerignoreExcludes(patterns []dockerignorePattern, rel string) bool {
	for p := rel; ; {
		excluded := false
		for _, pat := range patterns {
			if pat.re.MatchString(p) {
				excluded = pat.exclude
			}
		}
		if excluded {
			return true
		}
		parent := path.Dir(p)
		if parent == "." || parent == p {
			return false
		}
		p = parent
	}
}

// runtimeStageCopies returns the COPY sources of the stage aliased "runtime".
func runtimeStageCopies(doc string) []string {
	var copies []string
	inRuntime := false
	for _, line := range strings.Split(doc, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(strings.ToUpper(trimmed), "FROM ") {
			fields := strings.Fields(trimmed)
			inRuntime = false
			for i := 0; i+1 < len(fields); i++ {
				if strings.EqualFold(fields[i], "AS") && fields[i+1] == "runtime" {
					inRuntime = true
				}
			}
			continue
		}
		if !inRuntime || !strings.HasPrefix(strings.ToUpper(trimmed), "COPY ") {
			continue
		}
		rest := strings.TrimSpace(trimmed[len("COPY "):])
		if strings.HasPrefix(rest, "--from=") {
			continue // built in another stage, not from the context
		}
		fields := strings.Fields(rest)
		if len(fields) < 2 {
			continue
		}
		copies = append(copies, fields[:len(fields)-1]...)
	}
	return copies
}

func copiedIntoImage(copies []string, rel string) bool {
	for _, src := range copies {
		if src == rel || strings.HasPrefix(rel, src+"/") {
			return true
		}
	}
	return false
}
