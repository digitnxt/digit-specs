package common

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"time"

	"github.com/google/uuid"
)

// GenerateUUID generates a new UUID string
func GenerateUUID() string {
	return uuid.New().String()
}

// GetCurrentTimestamp returns current Unix millisecond timestamp
func GetCurrentTimestamp() int64 {
	return time.Now().UnixMilli()
}

// HashMobileNumber computes a keyed HMAC-SHA256 of the mobile number, hex-encoded.
//
// It is a deterministic "blind index": the same (secret, mobile) always yields the same
// hash, so it powers indexed exact-match search and the uniqueness check — while the secret
// pepper (held in config/Vault, never stored in the DB) prevents an attacker who obtains the
// hash column from brute-forcing the small mobile-number keyspace back to plaintext. A plain
// digest would be trivially reversible for phone numbers; HMAC with a secret key is not.
// An empty mobile yields an empty hash (no index value).
func HashMobileNumber(secret []byte, mobile string) string {
	if mobile == "" {
		return ""
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(mobile)) // hash.Hash.Write never returns an error
	return hex.EncodeToString(mac.Sum(nil))
}
