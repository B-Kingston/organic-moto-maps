package catalog

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func reqRegion(id string) Region {
	return Region{ID: id, Name: id, Source: Source{
		URL: "https://example.invalid/" + id + ".osm.pbf", Date: "260928",
		MD5: strings.Repeat("a", 32), MaxBytes: 1 << 20,
	}}
}

func TestRequestedStorePersistsAndEnforcesMax(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "requested.json")
	s, err := OpenRequested(path, 2)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"aa", "bb"} {
		if err := s.Upsert(reqRegion(id)); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Upsert(reqRegion("cc")); !errors.Is(err, ErrRequestedFull) {
		t.Fatalf("want ErrRequestedFull, got %v", err)
	}
	replaced := reqRegion("aa")
	replaced.Source.Date = "261001"
	if err := s.Upsert(replaced); err != nil {
		t.Fatalf("replace when full: %v", err)
	}
	if err := s.PinSHA("aa", strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	if err := s.PinSHA("zz", strings.Repeat("c", 64)); err == nil {
		t.Fatal("pin of unknown region accepted")
	}
	if err := s.PinSHA("aa", "nothex"); err == nil {
		t.Fatal("bad sha accepted")
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file left behind")
	}
	reopened, err := OpenRequested(path, 2)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := reopened.Get("aa")
	if !ok || !got.Requested || got.Source.Date != "261001" || got.Source.SHA256 != strings.Repeat("c", 64) || got.Source.MaxBytes != 1<<20 {
		t.Fatalf("not persisted: %+v ok=%v", got, ok)
	}
	if len(reopened.List()) != 2 {
		t.Fatalf("list: %+v", reopened.List())
	}
}

func TestRequestedStoreRejectsInvalid(t *testing.T) {
	s, _ := OpenRequested(filepath.Join(t.TempDir(), "r.json"), 0)
	bad := reqRegion("aa")
	bad.Source.MD5 = "XYZ"
	if err := s.Upsert(bad); err == nil {
		t.Fatal("bad md5 accepted")
	}
	bad = reqRegion("aa")
	bad.Source.MaxBytes = -1
	if err := s.Upsert(bad); err == nil {
		t.Fatal("negative maxBytes accepted")
	}
	if len(s.List()) != 0 {
		t.Fatal("invalid region stored")
	}
	path := filepath.Join(t.TempDir(), "corrupt.json")
	_ = os.WriteFile(path, []byte("{nope"), 0o644)
	if _, err := OpenRequested(path, 1); err == nil {
		t.Fatal("corrupt file accepted")
	}
}

func TestCatalogIncludesRequestedAfterOperator(t *testing.T) {
	cat := &Catalog{Regions: []Region{reqRegion("op"), reqRegion("shared")}}
	cat.Regions[1].Name = "operator wins"
	s, _ := OpenRequested(filepath.Join(t.TempDir(), "r.json"), 0)
	cat.AttachRequested(s)
	collide := reqRegion("shared")
	collide.Name = "requested loses"
	for _, r := range []Region{reqRegion("extra"), collide} {
		if err := s.Upsert(r); err != nil {
			t.Fatal(err)
		}
	}
	list := cat.List()
	var ids []string
	for _, r := range list {
		ids = append(ids, r.ID)
	}
	if strings.Join(ids, ",") != "op,shared,extra" {
		t.Fatalf("list order: %v", ids)
	}
	if got, _ := cat.Lookup("shared"); got.Name != "operator wins" {
		t.Fatalf("operator should win: %+v", got)
	}
	if got, err := cat.Lookup("extra"); err != nil || !got.Requested {
		t.Fatalf("requested lookup: %+v %v", got, err)
	}
	if len(cat.Regions) != 2 {
		t.Fatal("Regions must stay operator-only")
	}
}

func TestCatalogConcurrentWithStore(t *testing.T) {
	cat := &Catalog{Regions: []Region{reqRegion("op")}}
	s, _ := OpenRequested(filepath.Join(t.TempDir(), "r.json"), 0)
	cat.AttachRequested(s)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			id := "r" + string(rune('a'+i)) + "x"
			_ = s.Upsert(reqRegion(id))
			_ = cat.List()
			_, _ = cat.Lookup(id)
		}(i)
	}
	wg.Wait()
	if len(cat.List()) != 9 {
		t.Fatalf("list: %d", len(cat.List()))
	}
}

func TestSourceValidationMD5AndMaxBytes(t *testing.T) {
	r := reqRegion("ok")
	if err := r.Validate(); err != nil {
		t.Fatal(err)
	}
	r.Source.MD5 = strings.Repeat("A", 32)
	if err := r.Validate(); err == nil {
		t.Fatal("uppercase md5 accepted")
	}
}
