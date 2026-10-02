// Command map-server serves curveMaps regional data packages: it generates
// complete offline packages (vector tiles + routing graph + place-search
// index) from operator-approved OSM extracts, queues public build requests,
// and serves the results with resumable downloads.
package main

import (
	"os"

	"github.com/organicmoto/curveMaps/map-server/internal/cli"
)

func main() {
	os.Exit(cli.Run(os.Args, os.Stdout, os.Stderr))
}
