package ratelimit

import (
	"testing"
	"time"
)

func TestTokenBucketRefillsAndCaps(t *testing.T) {
	l := NewLimiter(10, time.Minute)
	now := time.Unix(1_700_000_000, 0)
	// Burst of 3, one token per 10 seconds.
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a", 3, 30*time.Second, now); !ok {
			t.Fatalf("request %d should be allowed", i)
		}
	}
	ok, retry := l.Allow("a", 3, 30*time.Second, now)
	if ok {
		t.Fatal("fourth request should be limited")
	}
	if retry <= 0 || retry > 11*time.Second {
		t.Fatalf("unexpected retry delay %s", retry)
	}
	if ok, _ := l.Allow("a", 3, 30*time.Second, now.Add(10*time.Second)); !ok {
		t.Fatal("token should have refilled after 10s")
	}
	if ok, _ := l.Allow("a", 3, 30*time.Second, now.Add(10*time.Minute)); !ok {
		t.Fatal("bucket should refill")
	}
	// Cap: after a long wait the bucket holds at most burst tokens.
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a", 3, 30*time.Second, now.Add(time.Hour)); !ok {
			t.Fatalf("capped refill request %d", i)
		}
	}
	if ok, _ := l.Allow("a", 3, 30*time.Second, now.Add(time.Hour)); ok {
		t.Fatal("bucket exceeded its burst cap")
	}
}

func TestBucketsAreIndependentPerKey(t *testing.T) {
	l := NewLimiter(10, time.Minute)
	now := time.Unix(1_700_000_000, 0)
	for i := 0; i < 2; i++ {
		if ok, _ := l.Allow("a", 2, time.Minute, now); !ok {
			t.Fatal("a should be allowed")
		}
	}
	if ok, _ := l.Allow("a", 2, time.Minute, now); ok {
		t.Fatal("a should be limited")
	}
	if ok, _ := l.Allow("b", 2, time.Minute, now); !ok {
		t.Fatal("b must not share a's bucket")
	}
}

func TestLimiterStaysBoundedAndSweeps(t *testing.T) {
	l := NewLimiter(64, time.Minute)
	now := time.Unix(1_700_000_000, 0)
	for i := 0; i < 1000; i++ {
		l.Allow(string(rune('a'+i%26))+string(rune('0'+i%10)), 1, time.Minute, now)
	}
	if l.Len() > 64 {
		t.Fatalf("limiter grew beyond its bound: %d", l.Len())
	}
	l.Sweep(now.Add(2 * time.Minute))
	if l.Len() != 0 {
		t.Fatalf("sweep left %d buckets", l.Len())
	}
}

func TestConcurrencyCaps(t *testing.T) {
	c := NewConcurrency(2, 1, time.Minute)
	if !c.Acquire("a") {
		t.Fatal("first acquire should succeed")
	}
	if c.Acquire("a") {
		t.Fatal("per-client cap not enforced")
	}
	if !c.Acquire("b") {
		t.Fatal("second client should fit the global cap")
	}
	if c.Acquire("c") {
		t.Fatal("global cap not enforced")
	}
	c.Release("a")
	if !c.Acquire("a") {
		t.Fatal("released slot should be reusable")
	}
	if c.Active() != 2 {
		t.Fatalf("active %d, want 2", c.Active())
	}
	c.Release("a")
	c.Release("b")
	if c.Active() != 0 {
		t.Fatalf("active %d, want 0", c.Active())
	}
}
