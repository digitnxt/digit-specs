package service

import (
	"context"

	"accesscontrol/internal/model"

	"github.com/rs/zerolog/log"
)

// CreateJbacRule creates a new JBAC rule
func (s *rbacService) CreateJbacRule(ctx context.Context, tenantID string, rule *model.CreateJbacRuleRequest) (*model.JbacRule, error) {
	log.Debug().Str("tenantID", tenantID).Msg("service: create JBAC rule")
	return s.repo.CreateJbacRule(ctx, tenantID, rule)
}

// GetJbacRule retrieves a JBAC rule by its ID
func (s *rbacService) GetJbacRule(ctx context.Context, tenantID, id string) (*model.JbacRule, error) {
	log.Debug().Str("tenantID", tenantID).Str("ruleID", id).Msg("service: get JBAC rule")
	return s.repo.GetJbacRule(ctx, tenantID, id)
}

// UpdateJbacRule updates a JBAC rule
func (s *rbacService) UpdateJbacRule(ctx context.Context, tenantID, id string, rule *model.UpdateJbacRuleRequest) (*model.JbacRule, error) {
	log.Debug().Str("tenantID", tenantID).Str("ruleID", id).Msg("service: update JBAC rule")
	return s.repo.UpdateJbacRule(ctx, tenantID, id, rule)
}

// DeleteJbacRule deletes a JBAC rule
func (s *rbacService) DeleteJbacRule(ctx context.Context, tenantID, id string) error {
	log.Debug().Str("tenantID", tenantID).Str("ruleID", id).Msg("service: delete JBAC rule")
	return s.repo.DeleteJbacRule(ctx, tenantID, id)
}

// ListJbacRules lists JBAC rules for a given tenant with optional filters
func (s *rbacService) ListJbacRules(ctx context.Context, tenantID string, filters model.JbacRulesFilter) (*model.JbacRuleListResponse, error) {
	log.Debug().Str("tenantID", tenantID).Interface("filters", filters).Msg("service: list JBAC rules")
	rules, total, err := s.repo.ListJbacRules(ctx, tenantID, filters)
	if err != nil {
		return nil, err
	}

	return &model.JbacRuleListResponse{
		Rules:  rules,
		Limit:  filters.Limit,
		Offset: filters.Offset,
		Total:  total,
	}, nil
}

// ListAllJbacRules lists all JBAC rules for all tenants
func (s *rbacService) ListAllJbacRules(ctx context.Context, filters model.AllRulesFilter) (*model.JbacRuleListResponse, error) {
	log.Debug().Interface("filters", filters).Msg("service: list all JBAC rules")
	rules, total, err := s.repo.ListAllJbacRules(ctx, filters)
	if err != nil {
		return nil, err
	}

	return &model.JbacRuleListResponse{
		Rules:  rules,
		Limit:  filters.Limit,
		Offset: filters.Offset,
		Total:  total,
	}, nil
}

// GetAllJbacRulesVersion gets the current version of all JBAC rules
func (s *rbacService) GetAllJbacRulesVersion(ctx context.Context) (string, error) {
	log.Debug().Msg("service: get all JBAC rules version")
	return s.repo.GetAllJbacRulesVersionHash(ctx)
}

// BulkCreateJbacRules creates multiple JBAC rules in a single transaction
func (s *rbacService) BulkCreateJbacRules(ctx context.Context, tenantID string, rules []model.CreateJbacRuleRequest) (*model.BulkCreateJbacRulesResponse, error) {
	log.Debug().Str("tenantID", tenantID).Int("ruleCount", len(rules)).Msg("service: bulk create JBAC rules")
	return s.repo.BulkCreateJbacRules(ctx, tenantID, rules)
}

// DeleteJbacRulesByTenant deletes all JBAC rules for a given tenant.
// The handler returns 204 No Content with no body, so we don't need to
// surface the row count up the call stack.
func (s *rbacService) DeleteJbacRulesByTenant(ctx context.Context, tenantID string) error {
	log.Debug().Str("tenantID", tenantID).Msg("service: delete JBAC rules by tenant")
	_, err := s.repo.DeleteJbacRulesByTenant(ctx, tenantID)
	return err
}
