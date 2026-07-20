package clients

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// vaultAuth manages a Vault token obtained via AppRole login and refreshes it
// on demand (see ensureToken) — there is no background goroutine. The service
// only ever performs encrypt/decrypt; all key lifecycle (rotation, config) is
// managed by Vault itself.
type vaultAuth struct {
	address  string
	roleID   string
	secretID string
	http     *http.Client

	mu        sync.RWMutex
	tok       string
	renewable bool
	ttl       time.Duration
	expiresAt time.Time
}

func newVaultAuth(address, roleID, secretID string, hc *http.Client) *vaultAuth {
	return &vaultAuth{
		address:  strings.TrimRight(address, "/"),
		roleID:   roleID,
		secretID: secretID,
		http:     hc,
	}
}

// Token returns the current Vault token in a thread-safe manner.
func (a *vaultAuth) Token() string {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.tok
}

type vaultAuthResponse struct {
	Auth struct {
		ClientToken   string `json:"client_token"`
		LeaseDuration int    `json:"lease_duration"`
		Renewable     bool   `json:"renewable"`
	} `json:"auth"`
}

// login performs an AppRole login and stores the resulting token.
func (a *vaultAuth) login(ctx context.Context) error {
	body, _ := json.Marshal(map[string]string{
		"role_id":   a.roleID,
		"secret_id": a.secretID,
	})
	url := fmt.Sprintf("%s/v1/auth/approle/login", a.address)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewBuffer(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := a.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("vault approle login status %d: %s", resp.StatusCode, string(data))
	}
	var out vaultAuthResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return err
	}
	if out.Auth.ClientToken == "" {
		return fmt.Errorf("vault approle login: empty client_token")
	}
	a.mu.Lock()
	a.tok = out.Auth.ClientToken
	a.renewable = out.Auth.Renewable
	a.ttl = time.Duration(out.Auth.LeaseDuration) * time.Second
	a.expiresAt = time.Now().Add(a.ttl)
	a.mu.Unlock()
	return nil
}

// renewSelf extends the lifetime of the current token.
func (a *vaultAuth) renewSelf(ctx context.Context) error {
	url := fmt.Sprintf("%s/v1/auth/token/renew-self", a.address)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Vault-Token", a.Token())
	resp, err := a.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("vault renew-self status %d: %s", resp.StatusCode, string(data))
	}
	var out vaultAuthResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return err
	}
	a.mu.Lock()
	if out.Auth.ClientToken != "" {
		a.tok = out.Auth.ClientToken
	}
	a.renewable = out.Auth.Renewable
	a.ttl = time.Duration(out.Auth.LeaseDuration) * time.Second
	a.expiresAt = time.Now().Add(a.ttl)
	a.mu.Unlock()
	return nil
}

// ensureToken makes sure a usable token is available before a Vault call.
// It is invoked on every encrypt/decrypt: the common case is a cheap in-memory
// check, and a network call (renew or re-login) only happens when the token is
// close to expiry or already gone. There is no background goroutine, so an idle
// service performs no Vault activity.
func (a *vaultAuth) ensureToken(ctx context.Context) error {
	a.mu.RLock()
	tok := a.tok
	ttl := a.ttl
	renewable := a.renewable
	expiresAt := a.expiresAt
	a.mu.RUnlock()

	if tok == "" {
		return a.login(ctx)
	}
	// A non-positive TTL means a non-expiring token; nothing to do.
	if ttl <= 0 {
		return nil
	}
	remaining := time.Until(expiresAt)
	if remaining <= 0 {
		return a.login(ctx)
	}
	// Refresh proactively once less than a quarter of the TTL remains.
	if remaining < ttl/4 {
		if renewable {
			if err := a.renewSelf(ctx); err != nil {
				return a.login(ctx)
			}
			return nil
		}
		return a.login(ctx)
	}
	return nil
}
