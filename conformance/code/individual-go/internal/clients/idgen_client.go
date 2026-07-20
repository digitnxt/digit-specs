package clients

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"individual/internal/config"

	"github.com/google/uuid"
	"github.com/rs/zerolog/log"
)

// IDGenClient handles ID generation service integration
type IDGenClient interface {
	// Added optional customVars to allow passing ORG, etc.
	GenerateIDs(ctx context.Context, tenantID string, idFormat string, count int, customVars map[string]string) ([]string, error)
}

type idGenClient struct {
	config     *config.IDGenConfig
	httpClient *http.Client
	enabled    bool
}

// NewIDGenClient creates a new ID generation client
func NewIDGenClient(cfg *config.IDGenConfig) IDGenClient {
	return &idGenClient{
		config:     cfg,
		httpClient: &http.Client{},
		enabled:    cfg.Enabled,
	}
}

// localIDGenRequest matches the local IdGen service contract
type localIDGenRequest struct {
	TemplateCode string            `json:"templateCode"`
	Variables    map[string]string `json:"variables"`
}

// localIDGenResponse matches the local IdGen service response
type localIDGenResponse struct {
	ID string `json:"id"`
}

// GenerateIDs generates formatted IDs from IDGen service
// GenerateIDs generates formatted IDs from IDGen service
func (c *idGenClient) GenerateIDs(ctx context.Context, tenantID string, idFormat string, count int, customVars map[string]string) ([]string, error) {
	if !c.enabled {
		log.Warn().Int("count", count).Msg("IDGen disabled, generating fallback IDs")
		return c.generateFallbackIDs(count), nil
	}

	url := c.config.Host + c.config.Path

	// Ensure variables map is initialized
	if customVars == nil {
		customVars = make(map[string]string)
	}

	// Always include ORG (default PG) unless provided
	if _, exists := customVars["ORG"]; !exists {
		customVars["ORG"] = tenantID
	}

	ids := make([]string, 0, count)
	for i := 0; i < count; i++ {
		payload := localIDGenRequest{
			TemplateCode: idFormat,
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
		// Set tenant header as required by IDGen
		if tenantID != "" {
			req.Header.Set("X-Tenant-Id", tenantID)
		}

		// idgen is ENABLED here (disabled short-circuits to fallback above). A failure is returned as
		// an error — we do NOT silently fall back, so a broken dependency fails loudly.
		resp, err := c.httpClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("failed to call IDGen service: %w", err)
		}

		err = func() error {
			defer resp.Body.Close()

			if resp.StatusCode != http.StatusOK {
				body, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("idgen returned status=%d body=%s", resp.StatusCode, string(body))
			}

			var response localIDGenResponse
			if err := json.NewDecoder(resp.Body).Decode(&response); err != nil {
				return fmt.Errorf("failed to decode IDGen response: %w", err)
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

// generateFallbackIDs generates simple UUID-based IDs when IDGen service is unavailable
func (c *idGenClient) generateFallbackIDs(count int) []string {
	ids := make([]string, count)
	for i := 0; i < count; i++ {
		// Generate unique ID using UUID to avoid duplicates
		ids[i] = "IND-" + uuid.New().String()[:8]
	}
	return ids
}
