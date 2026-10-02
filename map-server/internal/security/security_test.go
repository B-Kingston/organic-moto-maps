package security

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTestCSRF(t *testing.T) (*CSRF, time.Time) {
	t.Helper()
	secret := []byte("0123456789abcdef0123456789abcdef")
	return NewCSRF(secret, time.Hour, false), time.Unix(1_700_000_000, 0)
}

func TestTokenRoundTrip(t *testing.T) {
	c, now := newTestCSRF(t)
	token := c.Token(now)
	if !c.Valid(token, now) {
		t.Fatal("fresh token rejected")
	}
	if c.Valid(token, now.Add(2*time.Hour)) {
		t.Fatal("expired token accepted")
	}
	if c.Valid(token+"x", now) {
		t.Fatal("tampered token accepted")
	}
	if c.Valid("garbage", now) {
		t.Fatal("garbage token accepted")
	}
	if c.Valid(c.Token(now.Add(time.Hour)), now) {
		t.Fatal("token from the future accepted")
	}
}

func TestValidateAcceptsBrowserDoubleSubmit(t *testing.T) {
	c, now := newTestCSRF(t)
	token := c.Token(now)
	req := httptest.NewRequest(http.MethodPost, "/ui/builds", strings.NewReader("csrf_token="+token))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: CookieName, Value: token})
	if err := c.Validate(req, now); err != nil {
		t.Fatalf("valid double submit rejected: %v", err)
	}
}

func TestValidateAcceptsHeaderOnlyForAPIClients(t *testing.T) {
	c, now := newTestCSRF(t)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/builds", nil)
	req.Header.Set(HeaderName, c.Token(now))
	if err := c.Validate(req, now); err != nil {
		t.Fatalf("signed header token rejected: %v", err)
	}
	// An unsigned/garbage header must not pass.
	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/builds", nil)
	req2.Header.Set(HeaderName, "not-a-token")
	if err := c.Validate(req2, now); err == nil {
		t.Fatal("garbage header token accepted")
	}
}

func TestValidateRejectsCookieMismatchAndMissingToken(t *testing.T) {
	c, now := newTestCSRF(t)
	token := c.Token(now)
	req := httptest.NewRequest(http.MethodPost, "/ui/builds", strings.NewReader("csrf_token="+c.Token(now.Add(time.Second))))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: CookieName, Value: token})
	if err := c.Validate(req, now); err == nil {
		t.Fatal("mismatched token accepted")
	}
	empty := httptest.NewRequest(http.MethodPost, "/ui/builds", nil)
	if err := c.Validate(empty, now); err == nil {
		t.Fatal("missing token accepted")
	}
}

func TestEnsureSetsCookieOnce(t *testing.T) {
	c, now := newTestCSRF(t)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	token := c.Ensure(rec, req, now)
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != CookieName || cookies[0].Value != token {
		t.Fatalf("cookie not set: %+v", cookies)
	}
	if !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatal("cookie is not hardened")
	}
	// A request that already carries a valid cookie does not get a new one.
	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodGet, "/", nil)
	req2.AddCookie(cookies[0])
	if got := c.Ensure(rec2, req2, now); got != token {
		t.Fatal("valid cookie replaced")
	}
	if len(rec2.Result().Cookies()) != 0 {
		t.Fatal("cookie re-issued for a valid request")
	}
}

func TestHeadersAreHardened(t *testing.T) {
	rec := httptest.NewRecorder()
	Headers(rec, true)
	h := rec.Header()
	if !strings.Contains(h.Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatal("missing CSP")
	}
	if h.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("missing nosniff")
	}
	if h.Get("Strict-Transport-Security") == "" {
		t.Fatal("missing HSTS on TLS")
	}
	rec2 := httptest.NewRecorder()
	Headers(rec2, false)
	if rec2.Header().Get("Strict-Transport-Security") != "" {
		t.Fatal("HSTS sent over plaintext")
	}
}
