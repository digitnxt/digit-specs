package model

type contextKey string

const (
	UserIDContextKey    contextKey = "userID"
	RequestIDContextKey contextKey = "requestID"
)

// AuditDetail captures who/when for both the create and the most recent
// modification of a row.
//
// IMPORTANT: CreatedBy and CreatedTime are write-once. They are populated by
// the Create / BulkCreate paths and MUST NOT be touched on Update. The
// repository uses db.Save in Update which writes every column back — any
// in-memory mutation of these fields in an Update path will silently
// overwrite the original creator/timestamp. Only ModifiedBy and ModifiedTime
// belong in the Update flow.
type AuditDetail struct {
	CreatedBy    string `gorm:"column:created_by"  json:"createdBy"`
	CreatedTime  int64  `gorm:"column:created_at"  json:"createdTime"`
	ModifiedBy   string `gorm:"column:modified_by" json:"modifiedBy"`
	ModifiedTime int64  `gorm:"column:updated_at"  json:"modifiedTime"`
}
