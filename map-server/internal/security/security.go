// Package security provides the CSRF defense and the response hardening
// headers used by the map server. There is no authentication here: the server
// is meant to sit on a private network or behind an authenticating proxy.
package security

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// CookieName is the double-submit cookie carrying the CSRF token.
const CookieName = "motomap_csrf"

// HeaderName is the header API clients use to echo the token.
const HeaderName = "X-CSRF-Token"

// FormField is the hidden field web forms use.
const FormField = "csrf_token"

// ErrCSRF means the request failed CSRF validation.
var ErrCSRF = errors.New("invalid or missing CSRF token")

// CSRF validates state-changing requests with a signed, stateless
// double-submit token: the cookie value must match the submitted value and
// carry a fresh HMAC signature. No server-side token storage is needed, so a
// restart never invalidates an open page.
type CSRF struct {
	secret []byte
	ttl    time.Duration
	secure bool
}

// NewCSRF builds a validator from a persisted secret.
func NewCSRF(secret []byte, ttl time.Duration, secure bool) *CSRF {
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	return &CSRF{secret: secret, ttl: ttl, secure: secure}
}

// LoadOrCreateSecret reads a 32-byte secret, creating it when absent.
func LoadOrCreateSecret(path string) ([]byte, error) {
	data, err := os.ReadFile(path)
	if err == nil && len(data) >= 32 {
		return data, nil
	}
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, secret, 0o600); err != nil {
		return nil, err
	}
	return secret, nil
}

// Token mints a signed token.
func (c *CSRF) Token(now time.Time) string {
	ts := strconv.FormatInt(now.Unix(), 10)
	return ts + "." + c.sign(ts)
}

func (c *CSRF) sign(payload string) string {
	mac := hmac.New(sha256.New, c.secret)
	mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))
}

// Valid checks a token's signature and age.
func (c *CSRF) Valid(token string, now time.Time) bool {
	dot := strings.IndexByte(token, '.')
	if dot <= 0 || dot == len(token)-1 {
		return false
	}
	ts, sig := token[:dot], token[dot+1:]
	expected := c.sign(ts)
	if subtle.ConstantTimeCompare([]byte(sig), []byte(expected)) != 1 {
		return false
	}
	seconds, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return false
	}
	issued := time.Unix(seconds, 0)
	if now.Sub(issued) > c.ttl || issued.Sub(now) > 5*time.Minute {
		return false
	}
	return true
}

// Ensure issues the CSRF cookie when the request has none and returns the
// token to render into a form or JSON response.
func (c *CSRF) Ensure(w http.ResponseWriter, r *http.Request, now time.Time) string {
	if cookie, err := r.Cookie(CookieName); err == nil && c.Valid(cookie.Value, now) {
		return cookie.Value
	}
	token := c.Token(now)
	http.SetCookie(w, &http.Cookie{
		Name:     CookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   c.secure,
		MaxAge:   int(c.ttl.Seconds()),
	})
	return token
}

// Validate enforces the CSRF check for a state-changing request. Two shapes
// are accepted:
//
//   - Browser form/fetch: the cookie is present and the submitted token equals
//     it exactly (classic double submit).
//   - API client: no cookie, but the submitted token is itself a valid signed
//     token. A cross-site browser request cannot set the custom header, and a
//     form post cannot read a token it does not already have.
func (c *CSRF) Validate(r *http.Request, now time.Time) error {
	submitted := r.Header.Get(HeaderName)
	if submitted == "" {
		submitted = r.FormValue(FormField)
	}
	if submitted == "" {
		return fmt.Errorf("%w: no token submitted", ErrCSRF)
	}
	if cookie, err := r.Cookie(CookieName); err == nil && cookie.Value != "" {
		if subtle.ConstantTimeCompare([]byte(submitted), []byte(cookie.Value)) != 1 {
			return fmt.Errorf("%w: token mismatch", ErrCSRF)
		}
		if !c.Valid(cookie.Value, now) {
			return fmt.Errorf("%w: stale or forged cookie", ErrCSRF)
		}
		return nil
	}
	if !c.Valid(submitted, now) {
		return fmt.Errorf("%w: invalid token", ErrCSRF)
	}
	return nil
}

// Headers applies the response hardening headers. tls reports whether the
// request arrived over TLS (directly or via a trusted proxy).
func Headers(w http.ResponseWriter, tls bool) {
	h := w.Header()
	h.Set("Content-Security-Policy",
		"default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "+
			"connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("X-Frame-Options", "DENY")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Permissions-Policy", "geolocation=(), camera=(), microphone=()")
	h.Set("Cross-Origin-Opener-Policy", "same-origin")
	if tls {
		h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
	}
}
