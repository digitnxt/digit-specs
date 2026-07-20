package repository

import (
	"context"

	"accesscontrol/internal/model"
)

// Repository defines the interface for database operations
type Repository interface {
	CreateRbacRule(ctx context.Context, tenantID string, rule *model.CreateRbacRuleRequest) (*model.Rule, error)
	GetRbacRule(ctx context.Context, tenantID, id string) (*model.Rule, error)
	UpdateRbacRule(ctx context.Context, tenantID, id string, rule *model.UpdateRbacRuleRequest) (*model.Rule, error)
	DeleteRbacRule(ctx context.Context, tenantID, id string) error
	ListRbacRules(ctx context.Context, tenantID string, filters model.RbacRulesFilter) ([]*model.Rule, int, error)
	ListAllRbacRules(ctx context.Context, filters model.AllRulesFilter) ([]*model.Rule, int, error)
	GetAllRbacRulesVersionHash(ctx context.Context) (string, error)
	BulkCreateRbacRules(ctx context.Context, tenantID string, rules []model.CreateRbacRuleRequest) (*model.BulkCreateRbacRulesResponse, error)
	DeleteRbacRulesByTenant(ctx context.Context, tenantID string) (int, error)

	// JBAC
	CreateJbacRule(ctx context.Context, tenantID string, rule *model.CreateJbacRuleRequest) (*model.JbacRule, error)
	GetJbacRule(ctx context.Context, tenantID, id string) (*model.JbacRule, error)
	UpdateJbacRule(ctx context.Context, tenantID, id string, rule *model.UpdateJbacRuleRequest) (*model.JbacRule, error)
	DeleteJbacRule(ctx context.Context, tenantID, id string) error
	ListJbacRules(ctx context.Context, tenantID string, filters model.JbacRulesFilter) ([]*model.JbacRule, int, error)
	ListAllJbacRules(ctx context.Context, filters model.AllRulesFilter) ([]*model.JbacRule, int, error)
	GetAllJbacRulesVersionHash(ctx context.Context) (string, error)
	BulkCreateJbacRules(ctx context.Context, tenantID string, rules []model.CreateJbacRuleRequest) (*model.BulkCreateJbacRulesResponse, error)
	DeleteJbacRulesByTenant(ctx context.Context, tenantID string) (int, error)
}
