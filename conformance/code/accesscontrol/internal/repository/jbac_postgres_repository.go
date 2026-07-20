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

// CreateJbacRule creates a new JBAC rule in the database
func (r *gormRepository) CreateJbacRule(ctx context.Context, tenantID string, rule *model.CreateJbacRuleRequest) (*model.JbacRule, error) {
	log.Debug().Str("tenantID", tenantID).Msg("repo: create JBAC rule")
	userID, _ := ctx.Value(model.UserIDContextKey).(string)
	requestID, _ := ctx.Value(model.RequestIDContextKey).(string)
	newRule := &model.JbacRule{
		ID:                    uuid.New().String(),
		TenantID:              tenantID,
		Name:                  rule.Name,
		PathPattern:           rule.PathPattern,
		Methods:               rule.Methods,
		Enforcement:           rule.Enforcement,
		ParentImpliesChildren: rule.ParentImpliesChildren,
		ExtractJurisdiction:   rule.ExtractJurisdiction,
		Description:           rule.Description,
		RequestID:             requestID,
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

// GetJbacRule retrieves a JBAC rule from the database by its ID
func (r *gormRepository) GetJbacRule(ctx context.Context, tenantID, id string) (*model.JbacRule, error) {
	log.Debug().Str("tenantID", tenantID).Str("id", id).Msg("repo: get JBAC rule")
	var rule model.JbacRule
	if err := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).First(&rule).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &rule, nil
}

// UpdateJbacRule updates a JBAC rule in the database
func (r *gormRepository) UpdateJbacRule(ctx context.Context, tenantID, id string, req *model.UpdateJbacRuleRequest) (*model.JbacRule, error) {
	log.Debug().Str("tenantID", tenantID).Str("id", id).Msg("repo: update JBAC rule")
	existingRule, err := r.GetJbacRule(ctx, tenantID, id)
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
	if req.Name != nil {
		existingRule.Name = *req.Name
	}
	if req.PathPattern != nil {
		existingRule.PathPattern = *req.PathPattern
	}
	if req.Methods != nil {
		existingRule.Methods = *req.Methods
	}
	if req.Enforcement != nil {
		existingRule.Enforcement = *req.Enforcement
	}
	if req.ParentImpliesChildren != nil {
		existingRule.ParentImpliesChildren = *req.ParentImpliesChildren
	}
	// Nullable fields — three states:
	//   Set=false             → don't touch the column
	//   Set=true, Null=true   → clear the column
	//   Set=true, Null=false  → write the value
	if req.ExtractJurisdiction.Set {
		if req.ExtractJurisdiction.Null {
			existingRule.ExtractJurisdiction = nil // NULL in JSONB column
		} else {
			existingRule.ExtractJurisdiction = req.ExtractJurisdiction.Value
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

// DeleteJbacRule deletes a JBAC rule from the database
func (r *gormRepository) DeleteJbacRule(ctx context.Context, tenantID, id string) error {
	log.Debug().Str("tenantID", tenantID).Str("id", id).Msg("repo: delete JBAC rule")
	result := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).Delete(&model.JbacRule{})
	if result.Error != nil {
		return result.Error
	}

	if result.RowsAffected == 0 {
		return ErrNotFound // Not found
	}

	return nil
}

// ListJbacRules lists JBAC rules for a given tenant with optional filters
func (r *gormRepository) ListJbacRules(ctx context.Context, tenantID string, filters model.JbacRulesFilter) ([]*model.JbacRule, int, error) {
	log.Debug().Str("tenantID", tenantID).Interface("filters", filters).Msg("repo: list JBAC rules")
	var rules []*model.JbacRule
	var total int64

	db := r.db.WithContext(ctx).Model(&model.JbacRule{}).Where("tenant_id = ?", tenantID)
	if filters.Name != "" {
		db = db.Where("name ILIKE ?", "%"+filters.Name+"%")
	}
	if filters.Enforcement != "" {
		db = db.Where("enforcement = ?", filters.Enforcement)
	}

	if err := db.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := db.Order("created_at DESC").Limit(filters.Limit).Offset(filters.Offset).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, int(total), nil
}

// ListAllJbacRules lists all JBAC rules in the database, with pagination
func (r *gormRepository) ListAllJbacRules(ctx context.Context, filters model.AllRulesFilter) ([]*model.JbacRule, int, error) {
	log.Debug().Interface("filters", filters).Msg("repo: list all JBAC rules")
	var rules []*model.JbacRule
	var total int64

	db := r.db.WithContext(ctx).Model(&model.JbacRule{})

	if err := db.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := db.Order("id").Limit(filters.Limit).Offset(filters.Offset).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, int(total), nil
}

// GetAllJbacRulesVersionHash returns a compact version string that changes
// whenever the table changes. See the RBAC counterpart for the full
// design discussion and trade-offs — same hybrid approach applied here.
func (r *gormRepository) GetAllJbacRulesVersionHash(ctx context.Context) (string, error) {
	log.Debug().Msg("repo: get all JBAC rules version hash")
	var result struct {
		Hash string
	}
	tableName := model.JbacRule{}.TableName()
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

// BulkCreateJbacRules creates multiple JBAC rules in a single batch INSERT
func (r *gormRepository) BulkCreateJbacRules(ctx context.Context, tenantID string, rules []model.CreateJbacRuleRequest) (*model.BulkCreateJbacRulesResponse, error) {
	log.Debug().Str("tenantID", tenantID).Int("ruleCount", len(rules)).Msg("repo: bulk create JBAC rules")

	if len(rules) == 0 {
		return &model.BulkCreateJbacRulesResponse{Created: 0}, nil
	}

	userID, _ := ctx.Value(model.UserIDContextKey).(string)
	requestID, _ := ctx.Value(model.RequestIDContextKey).(string)
	now := time.Now().UnixMilli()

	newRules := make([]*model.JbacRule, len(rules))
	for i, rule := range rules {
		newRules[i] = &model.JbacRule{
			ID:                    uuid.New().String(),
			TenantID:              tenantID,
			Name:                  rule.Name,
			PathPattern:           rule.PathPattern,
			Methods:               rule.Methods,
			Enforcement:           rule.Enforcement,
			ParentImpliesChildren: rule.ParentImpliesChildren,
			ExtractJurisdiction:   rule.ExtractJurisdiction,
			Description:           rule.Description,
			RequestID:             requestID,
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

	return &model.BulkCreateJbacRulesResponse{Created: len(newRules)}, nil
}

// DeleteJbacRulesByTenant deletes all JBAC rules for a given tenant
func (r *gormRepository) DeleteJbacRulesByTenant(ctx context.Context, tenantID string) (int, error) {
	log.Debug().Str("tenantID", tenantID).Msg("repo: delete JBAC rules by tenant")
	result := r.db.WithContext(ctx).Where("tenant_id = ?", tenantID).Delete(&model.JbacRule{})
	if result.Error != nil {
		return 0, result.Error
	}

	return int(result.RowsAffected), nil
}
