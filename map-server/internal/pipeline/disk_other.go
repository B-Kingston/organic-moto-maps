//go:build !unix

package pipeline

// diskFree is best-effort on platforms without Statfs; a large value disables
// the free-space gate rather than blocking generation on Windows.
func diskFree(string) (int64, error) {
	return int64(^uint64(0) >> 1), nil
}
