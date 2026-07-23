package db

import (
	"accesscontrol/internal/config"
	"fmt"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

// NewDBPool creates a new GORM database connection and configures the
// underlying sql.DB connection pool. Pool settings are read from cfg; zero
// values mean "leave at driver default" (matches the pattern used by the
// localization service).
func NewDBPool(cfg *config.Config) (*gorm.DB, error) {
	dsn := fmt.Sprintf("host=%s user=%s password=%s dbname=%s port=%d sslmode=%s",
		cfg.Database.Host,
		cfg.Database.User,
		cfg.Database.Password,
		cfg.Database.DBName,
		cfg.Database.Port,
		cfg.Database.SSLMode,
	)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("failed to access underlying sql.DB: %w", err)
	}
	if cfg.Database.DBMaxOpenConns > 0 {
		sqlDB.SetMaxOpenConns(cfg.Database.DBMaxOpenConns)
	}
	if cfg.Database.DBMaxIdleConns > 0 {
		sqlDB.SetMaxIdleConns(cfg.Database.DBMaxIdleConns)
	}
	if cfg.Database.DBConnMaxLifetime > 0 {
		sqlDB.SetConnMaxLifetime(cfg.Database.DBConnMaxLifetime)
	}

	return db, nil
}
