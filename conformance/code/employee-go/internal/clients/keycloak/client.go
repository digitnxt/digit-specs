// internal/clients/keycloak/client.go
package keycloak

import (
	"context"
	"employee/internal/config"
	"employee/internal/constants"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// Client is a client for the Keycloak service
type Client struct {
	baseURL    string
	httpClient *http.Client
}

// NewClient creates a new Keycloak service client
func NewClient(cfg config.KeycloakConfig) *Client {
	return &Client{
		baseURL:    strings.TrimSuffix(cfg.BaseURL, "/"),
		httpClient: &http.Client{},
	}
}

// User represents a Keycloak user (simplified for validation)
type User struct {
	ID string `json:"id"`
	// Add other fields if needed from Keycloak response
}

// GetUserByID gets a user by ID from the Keycloak service
func (c *Client) GetUserByID(ctx context.Context, tenantID, userID string, authHeader string) (*User, error) {
	// Construct the URL using the provided template
	realms := strings.ToUpper(tenantID)
	reqURL := fmt.Sprintf("%s/admin/realms/%s/users/%s", c.baseURL, realms, userID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create Keycloak request: %w", err)
	}

	// Add Authorization header
	req.Header.Set("Authorization", authHeader)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("keycloak service request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, nil // User not found is not an error
	}

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("keycloak returned status=%d body=%s", resp.StatusCode, string(body))
	}

	var user User
	if err := json.NewDecoder(resp.Body).Decode(&user); err != nil {
		return nil, fmt.Errorf("failed to decode keycloak service response: %w", err)
	}

	return &user, nil
}

// keycloakRoleMemberPageSize is how many role members we pull per Keycloak
// admin call. Keycloak caps `max` server-side; 100 keeps each round-trip
// small while bounding the number of pages for large roles.
const keycloakRoleMemberPageSize = constants.KeycloakRoleMemberPageSize

// roleMember is the subset of Keycloak's user representation we need from the
// role-members endpoint — only the id, which maps to employee.user_id.
type roleMember struct {
	ID string `json:"id"`
}

// GetUserIDsByRole returns the Keycloak user IDs of every user assigned the
// given realm role in the tenant's realm (realm == upper(tenantID), matching
// GetUserByID). It pages through the admin endpoint
// (GET /admin/realms/{realm}/roles/{role}/users?first&max) until a short page
// signals the end, so the full member set is returned regardless of size.
//
// A missing role (404) is treated as "no members" and returns an empty slice
// rather than an error — searching by a role nobody holds should yield no
// employees, not a hard failure.
func (c *Client) GetUserIDsByRole(ctx context.Context, tenantID, role, authHeader string) ([]string, error) {
	realm := strings.ToUpper(tenantID)

	var userIDs []string
	for first := 0; ; first += keycloakRoleMemberPageSize {
		reqURL := fmt.Sprintf("%s/admin/realms/%s/roles/%s/users?first=%d&max=%d",
			c.baseURL, realm, url.PathEscape(role), first, keycloakRoleMemberPageSize)

		req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
		if err != nil {
			return nil, fmt.Errorf("failed to create Keycloak request: %w", err)
		}
		req.Header.Set("Authorization", authHeader)

		resp, err := c.httpClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("keycloak service request failed: %w", err)
		}

		if resp.StatusCode == http.StatusNotFound {
			resp.Body.Close()
			return userIDs, nil // role does not exist → no members
		}
		if resp.StatusCode != http.StatusOK {
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			return nil, fmt.Errorf("keycloak returned status=%d body=%s", resp.StatusCode, string(body))
		}

		var members []roleMember
		if err := json.NewDecoder(resp.Body).Decode(&members); err != nil {
			resp.Body.Close()
			return nil, fmt.Errorf("failed to decode keycloak service response: %w", err)
		}
		resp.Body.Close()

		for _, m := range members {
			if m.ID != "" {
				userIDs = append(userIDs, m.ID)
			}
		}

		// A page smaller than the requested size means we've reached the end.
		if len(members) < keycloakRoleMemberPageSize {
			break
		}
	}

	return userIDs, nil
}
