package service

import (
	"context"
	"employee/internal/models"
)

// JurisdictionService defines the interface for jurisdiction operations.
//
// Request identity (tenantID, userID) flows through ctx; implementations
// extract it once at method entry via middleware.GetRequestContextFromContext.
//
// Jurisdictions are a nested resource under /employees/:id/jurisdictions — the
// owning employeeID is therefore passed explicitly on every method that
// addresses a jurisdiction (create, get, replace). DeleteJurisdiction is
// intentionally addressed by jurisdiction ID alone because it is invoked from
// internal cascade paths (e.g. employee delete) where the owning employee is
// already known.
type JurisdictionService interface {
	CreateJurisdiction(ctx context.Context, employeeID string, req *models.CreateJurisdictionRequest) (*models.JurisdictionResponse, error)
	DeleteJurisdiction(ctx context.Context, id string) error
	SearchJurisdictions(ctx context.Context, employeeID string, criteria *models.JurisdictionSearchCriteria) ([]*models.JurisdictionResponse, error)
	GetJurisdictionByUUID(ctx context.Context, employeeID, jurisdictionID string) (*models.JurisdictionResponse, error)
	UpdateJurisdiction(ctx context.Context, employeeID, jurisdictionID string, req *models.UpdateJurisdictionRequest) (*models.JurisdictionResponse, error)
	// ReconcileJurisdictions applies an employee PUT/PATCH's jurisdiction array
	// to the collection: id+version → update in place, id-less → insert, omitted
	// → deactivate, foreign/id-without-version → reject. See the implementation
	// for the full three-way diff.
	ReconcileJurisdictions(ctx context.Context, employeeID string, fresh []*models.Jurisdiction) error
}
