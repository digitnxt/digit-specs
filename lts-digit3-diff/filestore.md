# Filestore Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-filestore` (Spring Boot 3.4.5 / Java 17) · v2.9.3  
**New:** `filestore` (Go 1.24+ / Gin + GORM) · DIGIT v3

Both handle multi-tenant file uploads, presigned S3 URLs, retrieval, and deletion. v3.0 is a ground-up Go rewrite, not a port, adding document category management, a client-side presigned upload flow, and restricting the storage backend to S3/MinIO only. This document covers only **filestore-specific** changes (platform-wide enhancements common to all v3 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | v2 (Java) | v3 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.24, Gin 1.10.1 |
| ORM / DB access | Spring Data JPA / Hibernate + custom JDBC | GORM 1.30 + sqlx for migration bootstrap |
| Build | Maven | Go modules |
| S3 client | Minio 8.6, AWS SDK S3 1.12, Azure Storage SDK 5.0 | minio-go v7 (S3/MinIO only) |
| Storage backends | S3/MinIO + Azure Blob Storage | **S3/MinIO only** |
| MIME detection | Apache Tika 3.2 (extension-based) | `gabriel-vasile/mimetype` (magic-byte content inspection) |
| Tenant identification | `?tenantId=` query param | `X-Tenant-Id` HTTP header (breaking change for all callers) |
| Auth / user context | DIGIT `RequestInfo` JSON form field | `X-User-Id` HTTP header |
| Event model | Spring Kafka | digit3/tracer/pubsub (Kafka or Redis Streams via `PUBSUB_TYPE`) |

---

## 2. Features Added in v3

- **Document category management:** every upload requires a matching active `DocumentCategory` record; static `allowed.formats.map` property replaced by DB-driven validation. Full CRUD with optimistic locking via `POST/GET /document-categories` and `GET|PUT|DELETE /document-categories/:docCode`.
- **Magic-byte MIME detection:** `gabriel-vasile/mimetype` validates actual file content, not just extension, preventing extension spoofing.
- **Concurrent multi-file upload:** goroutines per file (`sync.WaitGroup`) replace sequential v2.9 upload.
- **Client-side presigned upload flow:** `POST /upload-url` generates a presigned S3 PUT URL; `POST /confirm-upload` finalizes after the client uploads directly to S3 — no proxying through the service.
- **File deletion:** `DELETE /{fileStoreId}` removes the S3 object and DB row; v2.9 had no equivalent.
- **Separate read/write S3 buckets:** `S3_BUCKET` for writes, `S3_READ_BUCKET` for reads, supporting cross-bucket replication topologies.

> Carried over (parity): multi-file upload, presigned download URLs, multi-tenancy.

**Behavior changes to watch:** Azure Blob Storage support removed. Malware scan events (`egov.malware.file-scan-request` Kafka topic, off by default in v2.9) have no v3.0 equivalent. Presigned download URL expiry reduced from 24 hours (configurable) to 1 hour (fixed).

---

## 3. API Changes

Base path changes from `/filestore/v1/files` to `/filestore/v3/files`.

| Concern | v2 endpoint(s) | v3 endpoint(s) |
|---|---|---|
| Upload file | `POST /filestore/v1/files/upload` (`tenantId` query param; `requestInfo` form field) | `POST /filestore/v3/files/upload` (`X-Tenant-Id` header; `X-User-Id` header; module validated against `DocumentCategory`) |
| Fetch file | `GET /filestore/v1/files/{fileStoreId}` (query param `?fileStoreId=`) | `GET /filestore/v3/files/{fileStoreId}` (path param) |
| Download URLs | `GET /filestore/v1/files/url` (repeated `fileStoreIds` list param; 24-hour expiry) | `GET /filestore/v3/files/download-urls` (comma-separated single param; 1-hour expiry) |
| Delete file | *(none)* | `DELETE /filestore/v3/files/{fileStoreId}` |
| Presigned upload | *(none)* | `POST /filestore/v3/files/upload-url` + `POST /filestore/v3/files/confirm-upload` |
| Document categories | *(none)* | `POST/GET /filestore/v3/files/document-categories`, `GET|PUT|DELETE /filestore/v3/files/document-categories/:docCode` |
| DB migration | *(none)* | `POST /internal/migrate` |

---

## 4. DB Changes

| v2 table | v3 table | Key differences |
|---|---|---|
| `eg_filestoremap` | `eg_filestoremap_v2` | `requestid TEXT` added; 7 query indexes added; original table not dropped |
| *(none)* | `eg_doc_metadata_v2` | New document category table: `allowedFormats` JSONB, `minSize`/`maxSize`, `isSensitive`, `version` (optimistic lock); 3 indexes |

Other DB notes: migration tooling changed from Spring Flyway auto-integration (history table `egov_filestore_schema_version`) to external Flyway binary via `db/migrate.sh` (history table `filestore_schema`). Per-tenant schema routing available via digit3/tenant-migration; Flyway runs per schema on tenant creation events or `/internal/migrate`.

---
