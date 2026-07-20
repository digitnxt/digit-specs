package clients

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"individual/internal/config"

	"github.com/rs/zerolog/log"
)

// VaultClient handles encryption/decryption via HashiCorp Vault.
// Key lifecycle (creation, rotation, config) is managed entirely by Vault;
// this client only performs encrypt/decrypt against the Transit engine.
type VaultClient interface {
	Encrypt(ctx context.Context, plaintext string, key string) (string, error)
	Decrypt(ctx context.Context, ciphertext string, key string) (string, error)
}

type vaultClient struct {
	httpClient *http.Client
	config     *config.VaultConfig
	auth       *vaultAuth
	enabled    bool
}

// NewVaultClient creates a new Vault client that authenticates via AppRole.
func NewVaultClient(cfg *config.VaultConfig) (VaultClient, error) {
	if !cfg.Enabled {
		log.Info().Msg("Vault disabled — encryption/decryption will be bypassed")
		return &vaultClient{enabled: false}, nil
	}

	httpClient := &http.Client{
		Timeout: 10 * time.Second,
	}

	auth := newVaultAuth(cfg.Address, cfg.RoleID, cfg.SecretID, httpClient)
	if err := auth.login(context.Background()); err != nil {
		return nil, fmt.Errorf("vault approle login failed: %w", err)
	}

	log.Info().Str("address", cfg.Address).Msg("Vault client initialized (AppRole)")

	return &vaultClient{
		httpClient: httpClient,
		config:     cfg,
		auth:       auth,
		enabled:    true,
	}, nil
}

// doAuthed issues an authenticated request to Vault. If the token has expired
// or been revoked (HTTP 403), it performs a fresh AppRole login and retries
// the request once.
func (v *vaultClient) doAuthed(ctx context.Context, method, url string, body []byte) (*http.Response, error) {
	if err := v.auth.ensureToken(ctx); err != nil {
		return nil, fmt.Errorf("vault token unavailable: %w", err)
	}
	attempt := func() (*http.Response, error) {
		var rdr io.Reader
		if body != nil {
			rdr = bytes.NewBuffer(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, url, rdr)
		if err != nil {
			return nil, err
		}
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		req.Header.Set("X-Vault-Token", v.auth.Token())
		return v.httpClient.Do(req)
	}

	resp, err := attempt()
	if err != nil {
		return nil, err
	}
	if resp.StatusCode == http.StatusForbidden {
		resp.Body.Close()
		if lerr := v.auth.login(ctx); lerr != nil {
			return nil, fmt.Errorf("vault re-login after 403 failed: %w", lerr)
		}
		return attempt()
	}
	return resp, nil
}

// Encrypt encrypts plaintext using the Vault Transit engine.
func (v *vaultClient) Encrypt(ctx context.Context, plaintext string, key string) (string, error) {
	if !v.enabled {
		return plaintext, nil
	}

	if plaintext == "" {
		return "", nil
	}

	// Check if already encrypted (vault:v1: prefix)
	if strings.HasPrefix(plaintext, "vault:v1:") {
		return plaintext, nil
	}

	// Base64 encode the plaintext as required by Vault Transit
	encoded := base64.StdEncoding.EncodeToString([]byte(plaintext))

	requestBody := map[string]interface{}{
		"plaintext": encoded,
	}

	jsonData, err := json.Marshal(requestBody)
	if err != nil {
		return "", fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/v1/transit/encrypt/%s", strings.TrimRight(v.config.Address, "/"), key)
	resp, err := v.doAuthed(ctx, http.MethodPost, url, jsonData)
	if err != nil {
		return "", fmt.Errorf("failed to encrypt data: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("vault returned status %d: %s", resp.StatusCode, string(body))
	}

	var vaultResp struct {
		Data struct {
			Ciphertext string `json:"ciphertext"`
		} `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&vaultResp); err != nil {
		return "", fmt.Errorf("failed to decode response: %w", err)
	}

	if vaultResp.Data.Ciphertext == "" {
		return "", fmt.Errorf("empty ciphertext in response")
	}

	return vaultResp.Data.Ciphertext, nil
}

// Decrypt decrypts ciphertext using the Vault Transit engine.
func (v *vaultClient) Decrypt(ctx context.Context, ciphertext string, key string) (string, error) {
	if !v.enabled {
		return ciphertext, nil
	}

	if ciphertext == "" {
		return "", nil
	}

	// Check if encrypted (vault:v1: prefix)
	if !strings.HasPrefix(ciphertext, "vault:v1:") {
		return ciphertext, nil
	}

	requestBody := map[string]interface{}{
		"ciphertext": ciphertext,
	}

	jsonData, err := json.Marshal(requestBody)
	if err != nil {
		return "", fmt.Errorf("failed to marshal request: %w", err)
	}

	url := fmt.Sprintf("%s/v1/transit/decrypt/%s", strings.TrimRight(v.config.Address, "/"), key)
	resp, err := v.doAuthed(ctx, http.MethodPost, url, jsonData)
	if err != nil {
		return "", fmt.Errorf("failed to decrypt data: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("vault returned status %d: %s", resp.StatusCode, string(body))
	}

	var vaultResp struct {
		Data struct {
			Plaintext string `json:"plaintext"`
		} `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&vaultResp); err != nil {
		return "", fmt.Errorf("failed to decode response: %w", err)
	}

	if vaultResp.Data.Plaintext == "" {
		return "", fmt.Errorf("empty plaintext in response")
	}

	decoded, err := base64.StdEncoding.DecodeString(vaultResp.Data.Plaintext)
	if err != nil {
		return "", fmt.Errorf("failed to decode plaintext: %w", err)
	}

	return string(decoded), nil
}
