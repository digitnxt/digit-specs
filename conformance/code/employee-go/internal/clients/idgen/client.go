package idgen

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/google/uuid"
)

// Client handles ID generation service integration
type Client interface {
	// GenerateIDs generates formatted IDs with optional custom variables
	GenerateIDs(ctx context.Context, tenantID string, count int, customVars map[string]string) ([]string, error)
}

type client struct {
	host      string
	path      string
	idgenName string
	enabled   bool
}

// Config holds ID generation client configuration
type Config struct {
	Host      string
	Path      string
	IDGenName string
	Enabled   bool
}

// NewClient creates a new ID generation client.
//
// Host's trailing "/" is trimmed so that concatenating with a leading-slash
// path (e.g. "/idgen/v3/generate") yields a single-slash URL. Without this,
// the default host "http://localhost:8100/" + path "/idgen/v3/generate"
// produces "http://localhost:8100//idgen/v3/generate", which gin's router
// treats as a different path and 404s — and gin's access-log middleware
// doesn't fire on NoRoute, so the call silently disappears from idgen logs.
func NewClient(cfg Config) Client {
	return &client{
		host:      strings.TrimSuffix(cfg.Host, "/"),
		path:      cfg.Path,
		idgenName: cfg.IDGenName,
		enabled:   cfg.Enabled,
	}
}

type idGenRequest struct {
	TemplateCode string            `json:"templateCode"`
	Variables    map[string]string `json:"variables"`
}

type idGenResponse struct {
	ID string `json:"id"`
}

// GenerateIDs generates formatted IDs from IDGen service.
//
// customVars is forwarded as the `variables` map in the idgen request. The
// active EmployeeCode template uses only built-in tokens ({DATE:yyyy}, {SEQ}),
// so the employee service passes nil. Kept on the signature for future
// templates that may declare caller-supplied placeholders.
func (c *client) GenerateIDs(ctx context.Context, tenantID string, count int, customVars map[string]string) ([]string, error) {
	// Dependency flag: disabled → local fallback ids (no error). Enabled → a failure is returned as
	// an error (below); we do NOT silently fall back so a broken dependency fails loudly.
	if !c.enabled {
		ids := make([]string, 0, count)
		for i := 0; i < count; i++ {
			ids = append(ids, "EMP-"+uuid.New().String()[:8])
		}
		return ids, nil
	}
	url := c.host + c.path

	ids := make([]string, 0, count)
	for i := 0; i < count; i++ {
		payload := idGenRequest{
			TemplateCode: c.idgenName,
			Variables:    customVars,
		}

		jsonData, err := json.Marshal(payload)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal IDGen request: %w", err)
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewBuffer(jsonData))
		if err != nil {
			return nil, fmt.Errorf("failed to create request: %w", err)
		}

		req.Header.Set("Content-Type", "application/json")
		if tenantID != "" {
			req.Header.Set("X-Tenant-Id", tenantID)
		}

		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("failed to call IDGen service: %w", err)
		}

		err = func() error {
			defer resp.Body.Close()

			if resp.StatusCode != http.StatusOK {
				body, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("idgen returned status=%d body=%s", resp.StatusCode, string(body))
			}

			var response idGenResponse
			if err := json.NewDecoder(resp.Body).Decode(&response); err != nil {
				return fmt.Errorf("failed to decode idgen response: %w", err)
			}

			if response.ID == "" {
				return fmt.Errorf("idgen returned empty id")
			}

			ids = append(ids, response.ID)
			return nil
		}()
		if err != nil {
			return nil, err
		}
	}

	return ids, nil
}
