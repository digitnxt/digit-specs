package repository

import (
	"context"
	"errors"
	"time"

	"accesscontrol/internal/model"

	"github.com/google/uuid"
	"github.com/rs/zerolog/log"
	"gorm.io/gorm"
)

// gormRepository is a GORM implementation of the Repository
type gormRepository struct {
	db *gorm.DB
}

// NewGormRepository creates a new gormRepository
func NewGormRepository(db *gorm.DB) Repository {
	return &gormRepository{db: db}
}

// CreateRbacRule creates a new RBAC rule in the database.
// Priority and Enabled are pointer fields on the request; the handler has
// applied defaults before we reach this point, so deref is safe.
func (r *gormRepository) CreateRbacRule(ctx context.Context, tenantID string, rule *model.CreateRbacRuleRequest) (*model.Rule, error) {
	log.Debug().Str("tenantID", tenantID).Msg("repo: create RBAC rule")
	userID, _ := ctx.Value(model.UserIDContextKey).(string)
	requestID, _ := ctx.Value(model.RequestIDContextKey).(string)
	newRule := &model.Rule{
		ID:          uuid.New().String(),
		TenantID:    tenantID,
		RoleNames:   rule.RoleNames,
		HTTPMethod:  rule.HTTPMethod,
		Path:        rule.Path,
		Effect:      rule.Effect,
		Priority:    *rule.Priority,
		Enabled:     *rule.Enabled,
		Constraints: rule.Constraints,
		Description: rule.Description,
		RequestID:   requestID,
		AuditDetails: model.AuditDetail{
			CreatedBy:    userID,
			CreatedTime:  time.Now().UnixMilli(),
			ModifiedBy:   userID,
			ModifiedTime: time.Now().UnixMilli(),
		},
	}

	if err := r.db.WithContext(ctx).Create(newRule).Error; err != nil {
		return nil, err
	}

	return newRule, nil
}

// GetRbacRule retrieves a rule from the database by its ID
func (r *gormRepository) GetRbacRule(ctx context.Context, tenantID, id string) (*model.Rule, error) {
	log.Debug().Str("tenantID", tenantID).Str("id", id).Msg("repo: get RBAC rule")
	var rule model.Rule
	if err := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).First(&rule).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &rule, nil
}

// UpdateRbacRule updates a rule in the database
func (r *gormRepository) UpdateRbacRule(ctx context.Context, tenantID, id string, req *model.UpdateRbacRuleRequest) (*model.Rule, error) {
	log.Debug().Str("tenantID", tenantID).Str("id", id).Msg("repo: update RBAC rule")
	existingRule, err := r.GetRbacRule(ctx, tenantID, id)
	if err != nil {
		return nil, err
	}

	userID, _ := ctx.Value(model.UserIDContextKey).(string)
	requestID, _ := ctx.Value(model.RequestIDContextKey).(string)
	existingRule.AuditDetails.ModifiedBy = userID
	existingRule.AuditDetails.ModifiedTime = time.Now().UnixMilli()
	existingRule.RequestID = requestID

	// Required fields: pointer present → update. Absent or null is handled
	// upstream (handler rejects null on these).
	if req.RoleNames != nil {
		existingRule.RoleNames = *req.RoleNames
	}
	if req.HTTPMethod != nil {
		existingRule.HTTPMethod = *req.HTTPMethod
	}
	if req.Path != nil {
		existingRule.Path = *req.Path
	}
	if req.Effect != nil {
		existingRule.Effect = *req.Effect
	}
	if req.Priority != nil {
		existingRule.Priority = *req.Priority
	}
	if req.Enabled != nil {
		existingRule.Enabled = *req.Enabled
	}
	// Nullable fields — three states:
	//   Set=false             → don't touch the column
	//   Set=true, Null=true   → clear the column
	//   Set=true, Null=false  → write the value
	if req.Constraints.Set {
		if req.Constraints.Null {
			existingRule.Constraints = nil // NULL in JSONB column
		} else {
			existingRule.Constraints = req.Constraints.Value
		}
	}
	if req.Description.Set {
		if req.Description.Null {
			existingRule.Description = "" // empty string in TEXT column
		} else {
			existingRule.Description = req.Description.Value
		}
	}

	// GORM's autoUpdateTime tag on existingRule.UpdatedAt will now be triggered by Save
	if err := r.db.WithContext(ctx).Save(existingRule).Error; err != nil {
		return nil, err
	}

	return existingRule, nil
}

// DeleteRbacRule deletes a rule from the database
func (r *gormRepository) DeleteRbacRule(ctx context.Context, tenantID, id string) error {
	log.Debug().Str("tenantID", tenantID).Str("id", id).Msg("repo: delete RBAC rule")
	result := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).Delete(&model.Rule{})
	if result.Error != nil {
		return result.Error
	}

	if result.RowsAffected == 0 {
		return ErrNotFound // Not found
	}

	return nil
}

// ListRbacRules lists RBAC rules for a given tenant with optional filters
func (r *gormRepository) ListRbacRules(ctx context.Context, tenantID string, filters model.RbacRulesFilter) ([]*model.Rule, int, error) {
	log.Debug().Str("tenantID", tenantID).Interface("filters", filters).Msg("repo: list RBAC rules")
	var rules []*model.Rule
	var total int64

	db := r.db.WithContext(ctx).Model(&model.Rule{}).Where("tenant_id = ?", tenantID)
	if filters.RoleName != "" {
		db = db.Where("? = ANY(role_names)", filters.RoleName)
	}
	if filters.HTTPMethod != "" {
		db = db.Where("http_method = ?", filters.HTTPMethod)
	}
	if filters.Effect != "" {
		db = db.Where("effect = ?", filters.Effect)
	}
	if filters.Enabled != nil {
		db = db.Where("enabled = ?", *filters.Enabled)
	}

	if err := db.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := db.Order("created_at DESC").Limit(filters.Limit).Offset(filters.Offset).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, int(total), nil
}

// ListAllRbacRules lists all rules in the database, with pagination
func (r *gormRepository) ListAllRbacRules(ctx context.Context, filters model.AllRulesFilter) ([]*model.Rule, int, error) {
	log.Debug().Interface("filters", filters).Msg("repo: list all RBAC rules")
	var rules []*model.Rule
	var total int64

	db := r.db.WithContext(ctx).Model(&model.Rule{})

	if err := db.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := db.Order("id").Limit(filters.Limit).Offset(filters.Offset).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, int(total), nil
}

// GetAllRbacRulesVersionHash returns a compact version string that changes
// whenever the table changes. Format: "<maxUpdatedAt>:<count>:<rowFingerprint>"
// where rowFingerprint is SUM(hashtext(id || updated_at)) across all rows.
//
// Each component catches a different class of mutation:
//   - max(updated_at)  — any insert or update bumps the latest mtime
//   - count(*)         — any insert or delete changes the row count
//   - sum(hashtext(.)) — order-independent fingerprint; catches the rare
//     edge case where two same-millisecond writes leave max+count unchanged
//     (e.g. swap-style updates within 1 ms, or insert+delete with net-zero
//     count change at the same timestamp)
//
// Trade-off vs the previous md5(string_agg(...)) approach:
//   - O(1) memory in Postgres (no megabyte-scale string buffer, no disk spill)
//   - No sort step
//   - hashtext per row is ~50 ns vs the realloc-heavy string_agg path
//   - At 100k rows the cost drops from ~500 ms to ~10–50 ms
//   - The output is no longer a fixed 32-char hex digest, but callers only
//     compare strings for equality — the format change is transparent.
func (r *gormRepository) GetAllRbacRulesVersionHash(ctx context.Context) (string, error) {
	log.Debug().Msg("repo: get all RBAC rules version hash")
	var result struct {
		Hash string
	}
	tableName := model.Rule{}.TableName()
	query := `
        SELECT COALESCE(
            MAX(updated_at)::text || ':' ||
            COUNT(*)::text         || ':' ||
            SUM(hashtext(id::text || updated_at::text))::text,
            'no-rules'
        ) AS hash
        FROM ` + tableName

	if err := r.db.WithContext(ctx).Raw(query).Scan(&result).Error; err != nil {
		return "", err
	}
	return result.Hash, nil
}

// BulkCreateRbacRules creates multiple RBAC rules in a single batch INSERT
func (r *gormRepository) BulkCreateRbacRules(ctx context.Context, tenantID string, rules []model.CreateRbacRuleRequest) (*model.BulkCreateRbacRulesResponse, error) {
	log.Debug().Str("tenantID", tenantID).Int("ruleCount", len(rules)).Msg("repo: bulk create RBAC rules")

	if len(rules) == 0 {
		return &model.BulkCreateRbacRulesResponse{Created: 0}, nil
	}

	userID, _ := ctx.Value(model.UserIDContextKey).(string)
	requestID, _ := ctx.Value(model.RequestIDContextKey).(string)
	now := time.Now().UnixMilli()

	newRules := make([]*model.Rule, len(rules))
	for i, rule := range rules {
		newRules[i] = &model.Rule{
			ID:          uuid.New().String(),
			TenantID:    tenantID,
			RoleNames:   rule.RoleNames,
			HTTPMethod:  rule.HTTPMethod,
			Path:        rule.Path,
			Effect:      rule.Effect,
			Priority:    *rule.Priority,
			Enabled:     *rule.Enabled,
			Constraints: rule.Constraints,
			Description: rule.Description,
			RequestID:   requestID,
			AuditDetails: model.AuditDetail{
				CreatedBy:    userID,
				CreatedTime:  now,
				ModifiedBy:   userID,
				ModifiedTime: now,
			},
		}
	}

	if err := r.db.WithContext(ctx).Create(&newRules).Error; err != nil {
		return nil, err
	}

	return &model.BulkCreateRbacRulesResponse{Created: len(newRules)}, nil
}

// DeleteRbacRulesByTenant deletes all RBAC rules for a given tenant
func (r *gormRepository) DeleteRbacRulesByTenant(ctx context.Context, tenantID string) (int, error) {
	log.Debug().Str("tenantID", tenantID).Msg("repo: delete RBAC rules by tenant")
	result := r.db.WithContext(ctx).Where("tenant_id = ?", tenantID).Delete(&model.Rule{})
	if result.Error != nil {
		return 0, result.Error
	}

	return int(result.RowsAffected), nil
}
