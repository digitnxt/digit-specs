// internal/clients/individual/client.go
package individual

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"employee/internal/config"
)

// Client is a client for the individual service
type Client struct {
	baseURL    string
	// path is the full path to the individuals collection (from
	// IndividualConfig.Path, e.g. "/individuals/v3/individuals"). The
	// lookup URL is built as baseURL + path + "/" + individualID.
	path       string
	httpClient *http.Client
}

// NewClient creates a new individual service client
func NewClient(cfg config.IndividualConfig) *Client {
	return &Client{
		baseURL:    strings.TrimSuffix(cfg.Host, "/"),
		path:       "/" + strings.Trim(cfg.Path, "/"),
		httpClient: &http.Client{},
	}
}

// Individual represents an individual from the individual service
type Individual struct {
	ID string `json:"id"`
}

// GetIndividualByID gets an individual by ID from the individual service
func (c *Client) GetIndividualByID(ctx context.Context, tenantID, individualID string) (*Individual, error) {
	reqURL := fmt.Sprintf("%s%s/%s", c.baseURL, c.path, individualID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("X-Tenant-ID", tenantID)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-ID", "employee-service") //replace with actual client id if needed

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("individual service request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, nil // Not found is not an error
	}

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("individual returned status=%d body=%s", resp.StatusCode, string(body))
	}

	var individual Individual
	if err := json.NewDecoder(resp.Body).Decode(&individual); err != nil {
		return nil, fmt.Errorf("failed to decode individual service response: %w", err)
	}

	if individual.ID == "" {
		return nil, nil
	}

	return &individual, nil
}
