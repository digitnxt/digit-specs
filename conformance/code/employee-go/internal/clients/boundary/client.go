// internal/clients/boundary/client.go
package boundary

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"employee/internal/config"
)

// Client talks to the boundary service's relationship API to verify that
// the codes a caller supplies actually exist under the claimed hierarchyType
// and boundaryType.
type Client struct {
	httpClient *http.Client
	baseURL    string
	// path is the full relationship endpoint path (from BoundaryConfig.Path,
	// e.g. "/boundary/v3/relationship"). No leaf-name concatenation happens
	// in this client — the whole path lives in config so deployments can
	// adjust it without a redeploy.
	path string
}

// EnrichedBoundary represents a node in the boundary relationship tree.
type EnrichedBoundary struct {
	ID           string             `json:"id"`
	Code         string             `json:"code"`
	BoundaryType string             `json:"boundaryType"`
	Children     []EnrichedBoundary `json:"children,omitempty"`
}

// HierarchyRelation matches the boundary service's HierarchyRelation envelope.
type HierarchyRelation struct {
	TenantID      string             `json:"tenantId"`
	HierarchyType string             `json:"hierarchyType"`
	Boundary      []EnrichedBoundary `json:"boundary"`
}

// BoundarySearchResponse is the relationship endpoint's response envelope.
type BoundarySearchResponse struct {
	TenantBoundary []HierarchyRelation `json:"tenantBoundary"`
}

func NewClient(cfg config.BoundaryConfig) *Client {
	return &Client{
		httpClient: &http.Client{},
		baseURL:    strings.TrimSuffix(cfg.BaseURL, "/"),
		path:       "/" + strings.Trim(cfg.Path, "/"),
	}
}

// SearchRelationship queries the boundary relationship API for the given
// hierarchyType, boundaryType, and codes, and returns the set of codes the
// service recognised under that exact type. The walk descends children in
// the response tree because the boundary service may nest matching codes
// underneath their parents.
func (c *Client) SearchRelationship(ctx context.Context, tenantID, hierarchyType, boundaryType string, codes []string) (map[string]bool, error) {
	if len(codes) == 0 {
		return map[string]bool{}, nil
	}

	params := url.Values{}
	if hierarchyType != "" {
		params.Set("hierarchyType", hierarchyType)
	}
	if boundaryType != "" {
		params.Set("boundaryType", boundaryType)
	}
	for _, code := range codes {
		params.Add("codes", code)
	}
	reqURL := fmt.Sprintf("%s%s?%s", c.baseURL, c.path, params.Encode())

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("X-Tenant-Id", tenantID)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("boundary relationship request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("boundary returned status=%d body=%s", resp.StatusCode, string(body))
	}

	var rel BoundarySearchResponse
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return nil, fmt.Errorf("failed to decode boundary relationship response: %w", err)
	}

	found := make(map[string]bool)
	requested := make(map[string]bool, len(codes))
	for _, c := range codes {
		requested[c] = true
	}
	var walk func(nodes []EnrichedBoundary)
	walk = func(nodes []EnrichedBoundary) {
		for i := range nodes {
			if requested[nodes[i].Code] && nodes[i].BoundaryType == boundaryType {
				found[nodes[i].Code] = true
			}
			if len(nodes[i].Children) > 0 {
				walk(nodes[i].Children)
			}
		}
	}
	for _, tb := range rel.TenantBoundary {
		if hierarchyType != "" && tb.HierarchyType != hierarchyType {
			continue
		}
		walk(tb.Boundary)
	}
	return found, nil
}
