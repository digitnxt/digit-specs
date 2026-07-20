package service

import (
	"context"

	"accesscontrol/internal/model"
	"accesscontrol/internal/repository"

	"github.com/rs/zerolog/log"
)

// rbacService is the implementation of the Service interface
type rbacService struct {
	repo repository.Repository
}

// NewRBACService creates a new rbacService
func NewRBACService(repo repository.Repository) Service {
	return &rbacService{repo: repo}
}

// CreateRbacRule creates a new RBAC rule
func (s *rbacService) CreateRbacRule(ctx context.Context, tenantID string, rule *model.CreateRbacRuleRequest) (*model.Rule, error) {
	log.Debug().Str("tenantID", tenantID).Msg("service: create RBAC rule")
	return s.repo.CreateRbacRule(ctx, tenantID, rule)
}

// GetRbacRule retrieves a RBAC rule by its ID
func (s *rbacService) GetRbacRule(ctx context.Context, tenantID, id string) (*model.Rule, error) {
	log.Debug().Str("tenantID", tenantID).Str("ruleID", id).Msg("service: get RBAC rule")
	return s.repo.GetRbacRule(ctx, tenantID, id)
}

// UpdateRbacRule updates a RBAC rule
func (s *rbacService) UpdateRbacRule(ctx context.Context, tenantID, id string, rule *model.UpdateRbacRuleRequest) (*model.Rule, error) {
	log.Debug().Str("tenantID", tenantID).Str("ruleID", id).Msg("service: update RBAC rule")
	return s.repo.UpdateRbacRule(ctx, tenantID, id, rule)
}

// DeleteRbacRule deletes a RBAC rule
func (s *rbacService) DeleteRbacRule(ctx context.Context, tenantID, id string) error {
	log.Debug().Str("tenantID", tenantID).Str("ruleID", id).Msg("service: delete RBAC rule")
	return s.repo.DeleteRbacRule(ctx, tenantID, id)
}

// ListRbacRules lists RBAC rules for a given tenant with optional filters
func (s *rbacService) ListRbacRules(ctx context.Context, tenantID string, filters model.RbacRulesFilter) (*model.RbacRuleListResponse, error) {
	log.Debug().Str("tenantID", tenantID).Interface("filters", filters).Msg("service: list RBAC rules")
	rules, total, err := s.repo.ListRbacRules(ctx, tenantID, filters)
	if err != nil {
		return nil, err
	}

	return &model.RbacRuleListResponse{
		Rules:  rules,
		Limit:  filters.Limit,
		Offset: filters.Offset,
		Total:  total,
	}, nil
}

// ListAllRbacRules lists all RBAC rules for all tenants
func (s *rbacService) ListAllRbacRules(ctx context.Context, filters model.AllRulesFilter) (*model.RbacRuleListResponse, error) {
	log.Debug().Interface("filters", filters).Msg("service: list all RBAC rules")
	rules, total, err := s.repo.ListAllRbacRules(ctx, filters)
	if err != nil {
		return nil, err
	}

	return &model.RbacRuleListResponse{
		Rules:  rules,
		Limit:  filters.Limit,
		Offset: filters.Offset,
		Total:  total,
	}, nil
}

// GetAllRbacRulesVersion gets the current version of all RBAC rules
func (s *rbacService) GetAllRbacRulesVersion(ctx context.Context) (string, error) {
	log.Debug().Msg("service: get all RBAC rules version")
	return s.repo.GetAllRbacRulesVersionHash(ctx)
}

// BulkCreateRbacRules creates multiple RBAC rules in a single transaction
func (s *rbacService) BulkCreateRbacRules(ctx context.Context, tenantID string, rules []model.CreateRbacRuleRequest) (*model.BulkCreateRbacRulesResponse, error) {
	log.Debug().Str("tenantID", tenantID).Int("ruleCount", len(rules)).Msg("service: bulk create RBAC rules")
	return s.repo.BulkCreateRbacRules(ctx, tenantID, rules)
}

// DeleteRbacRulesByTenant deletes all RBAC rules for a given tenant.
// The handler returns 204 No Content with no body, so we don't need to
// surface the row count up the call stack.
func (s *rbacService) DeleteRbacRulesByTenant(ctx context.Context, tenantID string) error {
	log.Debug().Str("tenantID", tenantID).Msg("service: delete RBAC rules by tenant")
	_, err := s.repo.DeleteRbacRulesByTenant(ctx, tenantID)
	return err
}
