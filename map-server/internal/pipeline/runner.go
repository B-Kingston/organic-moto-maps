// Package pipeline runs the existing, pinned map-generation tools (Planetiler,
// GraphHopper, :geocoder-tool) to produce a complete `.motomap` package. It
// does not reimplement any generation logic: it invokes the same scripts,
// config, and sources the app's CI pipeline uses, with configurable work
// directories, heaps, and output paths.
package pipeline

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Command is one external process invocation.
type Command struct {
	Name    string // executable
	Args    []string
	Env     []string // extra environment entries, KEY=VALUE
	Dir     string
	LogPath string // stdout+stderr append target
}

// CommandRunner executes external steps. Tests substitute a stub.
type CommandRunner interface {
	Run(ctx context.Context, cmd Command) error
}

// ExecRunner runs commands in their own process group and kills the whole
// group on cancellation, so a cancelled job cannot leave a Planetiler or JVM
// child behind.
type ExecRunner struct {
	// GracePeriod is how long a cancelled process group may keep running
	// after SIGTERM before SIGKILL.
	GracePeriod time.Duration
}

// Run implements CommandRunner.
func (r ExecRunner) Run(ctx context.Context, spec Command) error {
	if spec.Name == "" {
		return fmt.Errorf("empty command")
	}
	if err := os.MkdirAll(filepath.Dir(spec.LogPath), 0o755); err != nil {
		return err
	}
	logFile, err := os.OpenFile(spec.LogPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer logFile.Close()

	cmd := exec.Command(spec.Name, spec.Args...)
	cmd.Env = append(os.Environ(), spec.Env...)
	cmd.Dir = spec.Dir
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	setProcessGroup(cmd)

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start %s: %w", filepath.Base(spec.Name), err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case err := <-done:
		if err != nil {
			return fmt.Errorf("%s failed: %w%s", filepath.Base(spec.Name), err, tailSuffix(spec.LogPath))
		}
		return nil
	case <-ctx.Done():
		grace := r.GracePeriod
		if grace <= 0 {
			grace = 10 * time.Second
		}
		terminateProcessGroup(cmd)
		select {
		case <-done:
		case <-time.After(grace):
			killProcessGroup(cmd)
			<-done
		}
		return fmt.Errorf("%s cancelled: %w", filepath.Base(spec.Name), ctx.Err())
	}
}

func tailSuffix(logPath string) string {
	tail, err := TailFile(logPath, 2048)
	if err != nil || strings.TrimSpace(tail) == "" {
		return ""
	}
	return "\n--- " + filepath.Base(logPath) + " (tail) ---\n" + strings.TrimSpace(tail)
}

// TailFile returns up to limit bytes from the end of a file.
func TailFile(path string, limit int64) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return "", err
	}
	size := info.Size()
	offset := size - limit
	if offset < 0 {
		offset = 0
	}
	buf := make([]byte, size-offset)
	if _, err := f.ReadAt(buf, offset); err != nil {
		return "", err
	}
	return string(buf), nil
}
