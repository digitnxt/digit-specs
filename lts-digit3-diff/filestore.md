# Filestore Service: 2.9 (Java) → 3.0 (Go)

## Overview
`filestore` is a multi-tenant file storage microservice handling uploads, presigned S3 URLs, file retrieval, deletion, and document category management. v3.0 is a full rewrite from Java/Spring Boot to Go/Gin, with a version-bumped API (`/v1` → `/v3`), breaking changes to tenant and auth headers, and S3 as the only supported backend.

## Tech stack

| | v2.9 | v3.0 |
|---|---|---|
| Language | Java 17 | Go 1.24 |
| Framework | Spring Boot 3.4.5 (Spring MVC) | Gin 1.10.1 |
| ORM / DB access | Spring Data JPA / Hibernate + custom JDBC | GORM 1.30 (postgres) + sqlx for migration bootstrap |
| Build | Maven (spring-boot-maven-plugin) | Go modules (`go.mod`) |
| Key libs | Minio 8.6, AWS SDK S3 1.12, Azure Storage SDK 5.0, Apache Tika 3.2, Spring Kafka | minio-go v7, gabriel-vasile/mimetype, segmentio/kafka-go, go-redis, digit3 tracer |

## API changes

Base path changes from `/filestore/v1/files` to `/filestore/v3/files`.

**Changed**
- `POST /upload` — `tenantId` moved from query param to `X-Tenant-Id` header; `requestInfo` form field replaced by `X-User-Id` header; module now validated against `DocumentCategory` DB record
- `GET /{fileStoreId}` — `fileStoreId` moved from query param (`?fileStoreId=`) to path param
- `GET /download-urls` (was `/url`) — `fileStoreIds` changes from repeated list param to comma-separated single param; presigned URL expiry reduced from 24 hours to 1 hour

**Added**
- `DELETE /{fileStoreId}` — removes file from S3 and deletes DB metadata
- `POST /upload-url` — generates presigned S3 PUT URL for client-side direct upload; validates against document category
- `POST /confirm-upload` — verifies presigned upload completed; returns `VALID`/`INVALID`; cleans up DB record if S3 object missing
- `POST /document-categories` / `GET /document-categories` / `GET|PUT|DELETE /document-categories/:docCode` — full CRUD for document category management with optimistic locking
- `POST /internal/migrate` — triggers per-tenant Flyway schema migration

**Removed**
- `tenantId` as a query parameter — all callers must switch to `X-Tenant-Id` header
- DIGIT `RequestInfo` form field — replaced by `X-User-Id` header

## Core logic & feature changes

- **Upload validation now DB-driven:** every upload requires a matching active `DocumentCategory` record; v2.9 used a static `allowed.formats.map` property. Missing category = rejected upload.
- **Magic-byte MIME detection:** `gabriel-vasile/mimetype` validates actual file content, not just extension, preventing extension spoofing; replaces Apache Tika.
- **Concurrent multi-file upload:** v3.0 fans out a goroutine per file (`sync.WaitGroup`); v2.9 uploaded sequentially.
- **Presigned upload flow (new):** client can now upload directly to S3 without proxying through the service, then call `/confirm-upload` to finalize.
- **Presigned download URL expiry:** reduced from 24 hours (configurable) to 1 hour (fixed).
- **File deletion (new):** removes S3 object then DB row; no soft-delete.
- **Malware scan events removed:** v2.9 published `egov.malware.file-scan-request` Kafka events (off by default); no equivalent in v3.0.
- **Azure Blob Storage removed:** v3.0 is S3/MinIO-only.
- **Config source:** Spring `application.properties` / `@Value` → environment variables only.
- **PubSub abstraction:** Spring Kafka → digit3/tracer/pubsub supporting Kafka or Redis Streams (`PUBSUB_TYPE` env var).

## DB / schema changes

- **Table renamed:** `eg_filestoremap` → `eg_filestoremap_v2`; old table not dropped — may coexist in the same DB.
- **Audit column naming:** snake_case (`createdby`, `lastmodifiedtime`) → camelCase quoted identifiers (`"createdBy"`, `"modifiedTime"`) in both tables; breaks any direct SQL relying on column names.
- **New column:** `requestid TEXT` added to `eg_filestoremap_v2`.
- **New table:** `eg_doc_metadata_v2` — stores document categories with `allowedFormats` JSONB, `minSize`/`maxSize`, `isSensitive`, `version` (optimistic lock), camelCase audit columns.
- **Indexes added:** v2.9 had no explicit query indexes; v3.0 adds 7 indexes on `eg_filestoremap_v2` and 3 on `eg_doc_metadata_v2`.
- **Migration tooling:** Spring Flyway auto-integration (history table: `egov_filestore_schema_version`) → external Flyway binary via `db/migrate.sh` (history table: `filestore_schema`).
- **Per-tenant schemas (optional):** digit3/tenant-migration enables per-tenant schema routing; Flyway runs per schema on tenant creation events or `/internal/migrate`.

## Notable architectural changes

- **Runtime rewrite:** Java 17/JVM + embedded Tomcat → Go 1.24 + net/http; eliminates JVM startup overhead; goroutine concurrency replaces thread-pool model.
- **Tenant identification is a breaking change:** `?tenantId=` query param → `X-Tenant-Id` HTTP header on every request; all existing API callers must update.
- **Auth/user context model changed:** DIGIT `RequestInfo` JSON wrapper fully removed; only `X-User-Id` header used for audit.
- **Separate read/write S3 buckets:** `S3_BUCKET` for writes, `S3_READ_BUCKET` for reads; supports cross-bucket replication topologies.
