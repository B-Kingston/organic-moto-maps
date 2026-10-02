// Package ratelimit implements the server's per-IP token buckets and
// concurrency caps. Rate limiting is abuse mitigation, not authentication or
// DDoS protection: it slows casual overuse and bounds resource use per address.
package ratelimit

import (
	"sort"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

// Limiter is a bounded, expiring map of token buckets.
type Limiter struct {
	mu         sync.Mutex
	buckets    map[string]*bucket
	maxEntries int
	ttl        time.Duration
}

// NewLimiter creates a limiter holding at most maxEntries buckets, forgetting
// buckets idle for longer than ttl.
func NewLimiter(maxEntries int, ttl time.Duration) *Limiter {
	if maxEntries <= 0 {
		maxEntries = 10_000
	}
	if ttl <= 0 {
		ttl = 30 * time.Minute
	}
	return &Limiter{buckets: map[string]*bucket{}, maxEntries: maxEntries, ttl: ttl}
}

// Allow charges one token for key. It returns false with a suggested wait when
// the bucket is empty.
func (l *Limiter) Allow(key string, burst int, per time.Duration, now time.Time) (bool, time.Duration) {
	if burst <= 0 || per <= 0 {
		return true, 0
	}
	rate := per.Seconds() / float64(burst) // seconds per token
	l.mu.Lock()
	defer l.mu.Unlock()
	b, ok := l.buckets[key]
	if !ok {
		if len(l.buckets) >= l.maxEntries {
			l.evictLocked(now)
		}
		b = &bucket{tokens: float64(burst), last: now}
		l.buckets[key] = b
	}
	elapsed := now.Sub(b.last).Seconds()
	if elapsed > 0 {
		b.tokens += elapsed / rate
		if b.tokens > float64(burst) {
			b.tokens = float64(burst)
		}
		b.last = now
	}
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	missing := 1 - b.tokens
	wait := time.Duration(missing * rate * float64(time.Second))
	if wait < time.Second {
		wait = time.Second
	}
	return false, wait
}

// Sweep removes idle buckets. Call periodically.
func (l *Limiter) Sweep(now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for key, b := range l.buckets {
		if now.Sub(b.last) > l.ttl {
			delete(l.buckets, key)
		}
	}
}

// Len reports the number of tracked buckets.
func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}

// evictLocked removes idle buckets, or the oldest ones when nothing is idle.
func (l *Limiter) evictLocked(now time.Time) {
	for key, b := range l.buckets {
		if now.Sub(b.last) > l.ttl {
			delete(l.buckets, key)
		}
	}
	if len(l.buckets) < l.maxEntries {
		return
	}
	// Evict a batch of the oldest entries to keep the fast path O(1).
	target := l.maxEntries / 8
	if target < 1 {
		target = 1
	}
	type entry struct {
		key  string
		last time.Time
	}
	oldest := make([]entry, 0, len(l.buckets))
	for key, b := range l.buckets {
		oldest = append(oldest, entry{key, b.last})
	}
	sort.Slice(oldest, func(i, j int) bool { return oldest[i].last.Before(oldest[j].last) })
	for i := 0; i < target && i < len(oldest); i++ {
		delete(l.buckets, oldest[i].key)
	}
}

// Concurrency bounds simultaneous transfers globally and per client.
type Concurrency struct {
	mu        sync.Mutex
	global    int
	maxGlobal int
	per       map[string]int
	maxPer    int
	ttl       time.Duration
	seen      map[string]time.Time
}

// NewConcurrency creates a concurrency limiter.
func NewConcurrency(maxGlobal, maxPer int, ttl time.Duration) *Concurrency {
	if maxGlobal <= 0 {
		maxGlobal = 8
	}
	if maxPer <= 0 {
		maxPer = 2
	}
	if ttl <= 0 {
		ttl = 30 * time.Minute
	}
	return &Concurrency{maxGlobal: maxGlobal, maxPer: maxPer, per: map[string]int{}, ttl: ttl, seen: map[string]time.Time{}}
}

// Acquire reserves a slot for key, or returns false when a limit is reached.
func (c *Concurrency) Acquire(key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sweepLocked(time.Now())
	if c.global >= c.maxGlobal || c.per[key] >= c.maxPer {
		return false
	}
	c.global++
	c.per[key]++
	c.seen[key] = time.Now()
	return true
}

// Release returns a slot.
func (c *Concurrency) Release(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.global--
	if c.global < 0 {
		c.global = 0
	}
	c.per[key]--
	if c.per[key] <= 0 {
		delete(c.per, key)
	}
}

// Active reports the current global transfer count.
func (c *Concurrency) Active() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.global
}

func (c *Concurrency) sweepLocked(now time.Time) {
	for key, at := range c.seen {
		if c.per[key] == 0 && now.Sub(at) > c.ttl {
			delete(c.seen, key)
		}
	}
}
