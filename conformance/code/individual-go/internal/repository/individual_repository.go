package repository

import (
	"context"
	"fmt"
	"strings"
	"time"

	"individual/internal/common"
	"individual/internal/models"

	tenantdb "github.com/digitnxt/digit3/src/libraries/tenant-migration/tenantdb"
	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"gorm.io/gorm"
)

// IndividualRepository handles data access for individuals
type IndividualRepository interface {
	Create(ctx context.Context, individual *models.Individual) error
	Update(ctx context.Context, individual *models.Individual, expectedVersion int) error
	Delete(ctx context.Context, id string, tenantID string) error
	FindByID(ctx context.Context, id string, tenantID string) (*models.Individual, error)
	Search(ctx context.Context, criteria *models.SearchCriteria, tenantID string, page, size int, includeDeleted bool) ([]models.Individual, int64, error)
	Exists(ctx context.Context, criteria *models.SearchCriteria, tenantID string, includeDeleted bool) (bool, error)
	FindByMobileHash(ctx context.Context, hash string, tenantID string) (*models.Individual, error)
	FindByIdentifier(ctx context.Context, identifierType, identifierID, tenantID string) (*models.Individual, error)
	FindByMobilePlain(ctx context.Context, mobile string, tenantID string) (*models.Individual, error)
	FindByName(ctx context.Context, givenName, familyName, tenantID string) (*models.Individual, error)
}

type individualRepository struct {
	db *gorm.DB
}

// NewIndividualRepository creates a new individual repository
func NewIndividualRepository(db *gorm.DB) IndividualRepository {
	return &individualRepository{db: db}
}

// isUniqueViolation reports whether err is a Postgres unique-constraint violation (SQLSTATE 23505).
// String-matched (rather than typed) to avoid a direct pgconn dependency; the repository is the
// single place this DB-specific detection lives, mirroring employee-go's pgerr.translatePgError.
func isUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "SQLSTATE 23505") || strings.Contains(strings.ToLower(msg), "duplicate key")
}

// Create inserts a new individual
func (r *individualRepository) Create(ctx context.Context, individual *models.Individual) error {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.create")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_v3"))
	start := time.Now()

	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Children are gorm:"-" (managed manually), so this writes only the
		// individual's own columns.
		if err := tx.Create(individual).Error; err != nil {
			return fmt.Errorf("failed to create individual: %w", err)
		}

		// Create addresses (direct one-to-many via individualid)
		if len(individual.Addresses) > 0 {
			for i := range individual.Addresses {
				individual.Addresses[i].IndividualID = individual.ID
				if err := tx.Create(&individual.Addresses[i]).Error; err != nil {
					return fmt.Errorf("failed to create address: %w", err)
				}
			}
		}

		// Create identifiers
		if len(individual.Identifiers) > 0 {
			for i := range individual.Identifiers {
				individual.Identifiers[i].IndividualID = individual.ID
				if err := tx.Create(&individual.Identifiers[i]).Error; err != nil {
					return fmt.Errorf("failed to create identifier: %w", err)
				}
			}
		}

		// Create documents
		if len(individual.Documents) > 0 {
			for i := range individual.Documents {
				individual.Documents[i].IndividualID = individual.ID
				if err := tx.Create(&individual.Documents[i]).Error; err != nil {
					return fmt.Errorf("failed to create document: %w", err)
				}
			}
		}

		return nil
	})

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "INSERT", "individual_v3", duration, err == nil)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to create individual")
		if isUniqueViolation(err) {
			return common.ErrDuplicate
		}
		return err
	}
	span.SetStatus(codes.Ok, "")
	return nil
}

// Update persists changes to an existing individual. The main-row write is
// guarded by the caller's expected version (optimistic compare-and-swap): if the
// row's rowversion no longer equals expectedVersion, no row matches and
// common.ErrOptimisticLock is returned so the caller can surface a 409. No lock
// is held while the caller enriches/encrypts — the guard is applied at write time.
func (r *individualRepository) Update(ctx context.Context, individual *models.Individual, expectedVersion int) error {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.update")
	defer span.End()
	span.SetAttributes(
		attribute.String("db.table", "individual_v3"),
		attribute.String("individual.id", individual.ID),
	)
	start := time.Now()

	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Optimistic guard: atomically claim the row only if its version still
		// matches what the client read. Zero rows affected => it changed in the
		// meantime => conflict. This UPDATE also locks the row for the rest of the
		// transaction, so the full-row Save below is safe.
		claim := tx.Model(&models.Individual{}).
			Where("id = ? AND tenantid = ? AND active = ? AND rowversion = ?",
				individual.ID, individual.TenantID, true, expectedVersion).
			Update("rowversion", expectedVersion+1)
		if claim.Error != nil {
			return fmt.Errorf("failed to update individual: %w", claim.Error)
		}
		if claim.RowsAffected == 0 {
			return common.ErrOptimisticLock
		}

		// Persist the remaining individual fields (children are gorm:"-", managed below).
		if err := tx.Save(individual).Error; err != nil {
			return fmt.Errorf("failed to update individual: %w", err)
		}

		// Update addresses — direct one-to-many, scoped to this individual (see identifiers above).
		if len(individual.Addresses) > 0 {
			for i := range individual.Addresses {
				individual.Addresses[i].IndividualID = individual.ID
				res := tx.Model(&individual.Addresses[i]).
					Where("individualid = ?", individual.ID).
					Select("*").
					Updates(&individual.Addresses[i])
				if res.Error != nil {
					return fmt.Errorf("failed to update address: %w", res.Error)
				}
				if res.RowsAffected == 0 {
					if err := tx.Create(&individual.Addresses[i]).Error; err != nil {
						return fmt.Errorf("failed to create address: %w", err)
					}
				}
			}
		}

		// Update identifiers — scoped to this individual: the update only matches a
		// row this individual owns (id + individualid), so another individual's row
		// can never be reassigned. Zero rows matched => new/unmatched id => insert.
		if len(individual.Identifiers) > 0 {
			for i := range individual.Identifiers {
				individual.Identifiers[i].IndividualID = individual.ID
				res := tx.Model(&individual.Identifiers[i]).
					Where("individualid = ?", individual.ID).
					Select("*").
					Updates(&individual.Identifiers[i])
				if res.Error != nil {
					return fmt.Errorf("failed to update identifier: %w", res.Error)
				}
				if res.RowsAffected == 0 {
					if err := tx.Create(&individual.Identifiers[i]).Error; err != nil {
						return fmt.Errorf("failed to create identifier: %w", err)
					}
				}
			}
		}

		// Update documents — scoped to this individual (see identifiers above).
		if len(individual.Documents) > 0 {
			for i := range individual.Documents {
				individual.Documents[i].IndividualID = individual.ID
				res := tx.Model(&individual.Documents[i]).
					Where("individualid = ?", individual.ID).
					Select("*").
					Updates(&individual.Documents[i])
				if res.Error != nil {
					return fmt.Errorf("failed to update document: %w", res.Error)
				}
				if res.RowsAffected == 0 {
					if err := tx.Create(&individual.Documents[i]).Error; err != nil {
						return fmt.Errorf("failed to create document: %w", err)
					}
				}
			}
		}

		// PUT full-replace: deactivate any existing active child of this individual
		// that was NOT in the request. Enrichment set Active=true on every request
		// child above, so the "keep" set is all request child IDs; an empty keep set
		// means the client sent none of that type => deactivate all.
		keepDocs := make([]string, 0, len(individual.Documents))
		for i := range individual.Documents {
			keepDocs = append(keepDocs, individual.Documents[i].ID)
		}
		docQ := tx.Model(&models.Document{}).Where("individualid = ? AND active = ?", individual.ID, true)
		if len(keepDocs) > 0 {
			docQ = docQ.Where("id NOT IN ?", keepDocs)
		}
		if err := docQ.Update("active", false).Error; err != nil {
			return fmt.Errorf("failed to deactivate removed documents: %w", err)
		}

		keepIdents := make([]string, 0, len(individual.Identifiers))
		for i := range individual.Identifiers {
			keepIdents = append(keepIdents, individual.Identifiers[i].ID)
		}
		identQ := tx.Model(&models.Identifier{}).Where("individualid = ? AND active = ?", individual.ID, true)
		if len(keepIdents) > 0 {
			identQ = identQ.Where("id NOT IN ?", keepIdents)
		}
		if err := identQ.Update("active", false).Error; err != nil {
			return fmt.Errorf("failed to deactivate removed identifiers: %w", err)
		}

		keepAddrs := make([]string, 0, len(individual.Addresses))
		for i := range individual.Addresses {
			keepAddrs = append(keepAddrs, individual.Addresses[i].ID)
		}
		addrQ := tx.Model(&models.Address{}).Where("individualid = ? AND active = ?", individual.ID, true)
		if len(keepAddrs) > 0 {
			addrQ = addrQ.Where("id NOT IN ?", keepAddrs)
		}
		if err := addrQ.Update("active", false).Error; err != nil {
			return fmt.Errorf("failed to deactivate removed addresses: %w", err)
		}

		return nil
	})

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "UPDATE", "individual_v3", duration, err == nil)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to update individual")
		if isUniqueViolation(err) {
			return common.ErrDuplicate
		}
		return err
	}
	span.SetStatus(codes.Ok, "")
	return nil
}

// Delete soft deletes an individual
func (r *individualRepository) Delete(ctx context.Context, id string, tenantID string) error {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.delete")
	defer span.End()
	span.SetAttributes(
		attribute.String("db.table", "individual_v3"),
		attribute.String("individual.id", id),
	)
	start := time.Now()

	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		now := common.GetCurrentTimestamp()

		// Deactivate individual
		if err := tx.Model(&models.Individual{}).
			Where("id = ? AND tenantid = ?", id, tenantID).
			Updates(map[string]interface{}{
				"active":       false,
				"modifiedTime": now,
			}).Error; err != nil {
			return fmt.Errorf("failed to delete individual: %w", err)
		}

		// Deactivate identifiers
		tx.Model(&models.Identifier{}).
			Where("individualid = ?", id).
			Update("active", false)

		// Deactivate documents
		tx.Model(&models.Document{}).
			Where("individualid = ?", id).
			Update("active", false)

		// Deactivate addresses
		tx.Model(&models.Address{}).
			Where("individualid = ?", id).
			Update("active", false)

		return nil
	})

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "UPDATE", "individual_v3", duration, err == nil)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to delete individual")
		return err
	}
	span.SetStatus(codes.Ok, "")
	return nil
}

// FindByID retrieves an individual by ID
func (r *individualRepository) FindByID(ctx context.Context, id string, tenantID string) (*models.Individual, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.findByID")
	defer span.End()
	span.SetAttributes(
		attribute.String("db.table", "individual_v3"),
		attribute.String("individual.id", id),
	)
	start := time.Now()

	var individual models.Individual
	db := tenantdb.GetTenantDB(ctx, r.db)

	err := db.WithContext(ctx).
		Where("id = ? AND tenantid = ? AND active = ?", id, tenantID, true).
		First(&individual).Error

	if err != nil {
		if err == gorm.ErrRecordNotFound {
			tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), true)
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to find individual")
		return nil, fmt.Errorf("failed to find individual: %w", err)
	}

	// Manually load addresses (active only)
	if err := db.WithContext(ctx).
		Where("individualid = ? AND active = ?", id, true).
		Find(&individual.Addresses).Error; err != nil {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to load addresses")
		return nil, fmt.Errorf("failed to load addresses: %w", err)
	}

	// Manually load identifiers (active only)
	if err := db.WithContext(ctx).
		Where("individualid = ? AND active = ?", id, true).
		Find(&individual.Identifiers).Error; err != nil {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to load identifiers")
		return nil, fmt.Errorf("failed to load identifiers: %w", err)
	}

	// Manually load documents (active only)
	if err := db.WithContext(ctx).
		Where("individualid = ? AND active = ?", id, true).
		Find(&individual.Documents).Error; err != nil {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to load documents")
		return nil, fmt.Errorf("failed to load documents: %w", err)
	}

	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), true)
	span.SetStatus(codes.Ok, "")
	return &individual, nil
}

// buildSearchQuery composes the WHERE clauses shared by Search and Exists.
func (r *individualRepository) buildSearchQuery(ctx context.Context, criteria *models.SearchCriteria, tenantID string, includeDeleted bool) *gorm.DB {
	db := tenantdb.GetTenantDB(ctx, r.db)
	base := db.WithContext(ctx).Model(&models.Individual{}).Where("tenantid = ?", tenantID)

	if !includeDeleted {
		base = base.Where("active = ?", true)
	}

	if criteria == nil {
		return base
	}

	if len(criteria.ID) > 0 {
		base = base.Where("id IN ?", criteria.ID)
	}
	if len(criteria.IndividualID) > 0 {
		base = base.Where("individualid IN ?", criteria.IndividualID)
	}
	if criteria.GivenName != "" {
		base = base.Where("givenname ILIKE ?", "%"+criteria.GivenName+"%")
	}
	if len(criteria.MobileNumber) > 0 {
		// Mobile numbers are expected to be pre-hashed by the service layer.
		base = base.Where("hashedmobilenumber IN ?", criteria.MobileNumber)
	}
	if criteria.Gender != "" {
		base = base.Where("gender = ?", criteria.Gender)
	}
	if criteria.DateOfBirth != "" {
		base = base.Where("dateofbirth = ?", criteria.DateOfBirth)
	}
	if len(criteria.UserID) > 0 {
		base = base.Where("userid IN ?", criteria.UserID)
	}
	if len(criteria.UserUUID) > 0 {
		base = base.Where("useruuid IN ?", criteria.UserUUID)
	}
	if len(criteria.Username) > 0 {
		base = base.Where("username IN ?", criteria.Username)
	}
	if criteria.CreatedFrom != nil {
		base = base.Where("\"createdTime\" >= ?", *criteria.CreatedFrom)
	}
	if criteria.CreatedTo != nil {
		base = base.Where("\"createdTime\" <= ?", *criteria.CreatedTo)
	}

	return base
}

func (r *individualRepository) Search(ctx context.Context, criteria *models.SearchCriteria, tenantID string, page, size int, includeDeleted bool) ([]models.Individual, int64, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.search")
	defer span.End()
	span.SetAttributes(
		attribute.String("db.table", "individual_v3"),
		attribute.String("tenant.id", tenantID),
		attribute.Int("page", page),
		attribute.Int("size", size),
	)
	start := time.Now()

	var individuals []models.Individual
	var totalCount int64
	db := tenantdb.GetTenantDB(ctx, r.db)

	base := r.buildSearchQuery(ctx, criteria, tenantID, includeDeleted)

	// Get total count
	if err := base.Session(&gorm.Session{}).Count(&totalCount).Error; err != nil {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to count individuals")
		return nil, 0, fmt.Errorf("failed to count individuals: %w", err)
	}

	// Short-circuit if no results
	if totalCount == 0 {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), true)
		span.SetStatus(codes.Ok, "")
		return []models.Individual{}, 0, nil
	}

	// Translate page/size to SQL LIMIT/OFFSET at the persistence boundary
	limit := size
	offset := (page - 1) * size

	// Phase 1: fetch IDs with pagination for stability
	var ids []string
	if err := base.Session(&gorm.Session{}).
		Select("id").
		Order("\"createdTime\" DESC").
		Limit(limit).
		Offset(offset).
		Pluck("id", &ids).Error; err != nil {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to fetch ids")
		return nil, 0, fmt.Errorf("failed to fetch ids: %w", err)
	}

	if len(ids) == 0 {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), true)
		span.SetStatus(codes.Ok, "")
		return []models.Individual{}, totalCount, nil
	}

	// Phase 2: fetch full rows for those IDs with preloads
	err := db.WithContext(ctx).
		Model(&models.Individual{}).
		Where("id IN ?", ids).
		Order("\"createdTime\" DESC").
		Find(&individuals).Error

	if err != nil {
		tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to search individuals")
		return nil, 0, fmt.Errorf("failed to search individuals: %w", err)
	}

	// Manually load identifiers and documents for each individual
	for i := range individuals {
		if err := db.WithContext(ctx).
			Where("individualid = ? AND active = ?", individuals[i].ID, true).
			Find(&individuals[i].Addresses).Error; err != nil {
			tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
			span.RecordError(err)
			span.SetStatus(codes.Error, "failed to load addresses")
			return nil, 0, fmt.Errorf("failed to load addresses: %w", err)
		}
		if err := db.WithContext(ctx).
			Where("individualid = ? AND active = ?", individuals[i].ID, true).
			Find(&individuals[i].Identifiers).Error; err != nil {
			tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
			span.RecordError(err)
			span.SetStatus(codes.Error, "failed to load identifiers")
			return nil, 0, fmt.Errorf("failed to load identifiers: %w", err)
		}
		if err := db.WithContext(ctx).
			Where("individualid = ? AND active = ?", individuals[i].ID, true).
			Find(&individuals[i].Documents).Error; err != nil {
			tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), false)
			span.RecordError(err)
			span.SetStatus(codes.Error, "failed to load documents")
			return nil, 0, fmt.Errorf("failed to load documents: %w", err)
		}
	}

	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", time.Since(start), true)
	span.SetAttributes(attribute.Int64("result.count", int64(len(individuals))))
	span.SetStatus(codes.Ok, "")
	return individuals, totalCount, nil
}

// Exists returns true if at least one individual matches the given criteria.
// Short-circuits at the DB level using LIMIT 1.
func (r *individualRepository) Exists(ctx context.Context, criteria *models.SearchCriteria, tenantID string, includeDeleted bool) (bool, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.exists")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_v3"))
	start := time.Now()

	var ids []string
	err := r.buildSearchQuery(ctx, criteria, tenantID, includeDeleted).
		Select("id").
		Limit(1).
		Pluck("id", &ids).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", duration, err == nil)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to check individual existence")
		return false, fmt.Errorf("failed to check individual existence: %w", err)
	}
	span.SetStatus(codes.Ok, "")
	return len(ids) > 0, nil
}

// FindByMobileHash finds an individual by mobile number hash
func (r *individualRepository) FindByMobileHash(ctx context.Context, hash string, tenantID string) (*models.Individual, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.findByMobileHash")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_v3"))
	start := time.Now()

	var individual models.Individual
	db := tenantdb.GetTenantDB(ctx, r.db)

	err := db.WithContext(ctx).
		Where("hashedmobilenumber = ? AND tenantid = ? AND active = ?", hash, tenantID, true).
		First(&individual).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", duration, err == nil || err == gorm.ErrRecordNotFound)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to find individual by mobile")
		return nil, fmt.Errorf("failed to find individual by mobile: %w", err)
	}
	span.SetStatus(codes.Ok, "")
	return &individual, nil
}

// FindByIdentifier finds an individual by identifier
func (r *individualRepository) FindByIdentifier(ctx context.Context, identifierType, identifierID, tenantID string) (*models.Individual, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.findByIdentifier")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_identifier_v3"))
	start := time.Now()

	var identifier models.Identifier
	db := tenantdb.GetTenantDB(ctx, r.db)

	err := db.WithContext(ctx).
		Table("individual_identifier_v3").
		Joins("JOIN individual_v3 ON individual_identifier_v3.individualid = individual_v3.id").
		Where("individual_identifier_v3.identifiertype = ? AND individual_identifier_v3.identifierid = ? AND individual_v3.tenantid = ? AND individual_identifier_v3.active = ? AND individual_v3.active = ?",
			identifierType, identifierID, tenantID, true, true).
		First(&identifier).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_identifier_v3", duration, err == nil || err == gorm.ErrRecordNotFound)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to find individual by identifier")
		return nil, fmt.Errorf("failed to find individual by identifier: %w", err)
	}
	span.SetStatus(codes.Ok, "")
	return r.FindByID(ctx, identifier.IndividualID, tenantID)
}

// FindByMobilePlain finds by plaintext mobile if stored as plaintext (fallback)
func (r *individualRepository) FindByMobilePlain(ctx context.Context, mobile string, tenantID string) (*models.Individual, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.findByMobilePlain")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_v3"))
	start := time.Now()

	var individual models.Individual
	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).
		Where("mobilenumber = ? AND tenantid = ? AND active = ?", mobile, tenantID, true).
		First(&individual).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", duration, err == nil || err == gorm.ErrRecordNotFound)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to find individual by plaintext mobile")
		return nil, fmt.Errorf("failed to find individual by plaintext mobile: %w", err)
	}
	span.SetStatus(codes.Ok, "")
	return &individual, nil
}

// FindByName checks an existing record by given and family name tuple in a tenant
func (r *individualRepository) FindByName(ctx context.Context, givenName, familyName, tenantID string) (*models.Individual, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.individual.findByName")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_v3"))
	start := time.Now()

	var individual models.Individual
	db := tenantdb.GetTenantDB(ctx, r.db)
	q := db.WithContext(ctx).Where("tenantid = ? AND active = ?", tenantID, true)
	if givenName != "" {
		q = q.Where("LOWER(givenname) = ?", strings.ToLower(givenName))
	}
	if familyName != "" {
		q = q.Where("LOWER(familyname) = ?", strings.ToLower(familyName))
	}
	err := q.First(&individual).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_v3", duration, err == nil || err == gorm.ErrRecordNotFound)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to find individual by name")
		return nil, fmt.Errorf("failed to find individual by name: %w", err)
	}
	span.SetStatus(codes.Ok, "")
	return &individual, nil
}
