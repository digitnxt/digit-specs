# Boundary Service (boundary)

A DIGIT 3.0 microservice that manages tenant-scoped administrative boundaries,
boundary hierarchies and parent-child relationships. It supports GeoJSON
boundary data, PostGIS-backed geo search (point-in-polygon), hierarchical
relationship validation, cache/pubsub integration and canonical APIs.

## Overview

**Service Name:** boundary

**Purpose:** Provide a reusable boundary master-data service for cities, states
and agencies to model administrative, revenue or service delivery areas.

**Owner/Team:** DIGIT Platform Team

## Architecture

**Tech Stack:**
- Java 25
- Spring Boot 4
- PostgreSQL with the PostGIS extension (geo search)
- Redis optional for cache
- `org.digit:tracer` platform library
- Kafka / Redis optional pubsub
- DIGIT tenant migration library

**Core Responsibilities:**
- Create and update boundary entities
- Search boundaries by tenant and filters, including geo (point-in-polygon) search
- Create and update hierarchy definitions
- Search hierarchy definitions
- Create and update boundary relationships
- Search boundary relationships
- Publish boundary lifecycle events when pubsub is enabled
- Expose canonical APIs using request/response metadata envelopes

**Dependencies:**
- PostgreSQL required — must be PostGIS-capable (e.g. the `postgis/postgis`
  image); the migration runs `CREATE EXTENSION IF NOT EXISTS postgis`
- Redis optional for cache
- Kafka or Redis optional for pubsub events
- OpenTelemetry Collector optional

## Features

- Boundary entity CRUD-style APIs
- GeoJSON geometry support with validation (Point, LineString, Polygon,
  MultiPoint, MultiLineString, MultiPolygon, GeometryCollection; polygon rings
  must be closed)
- PostGIS geo search: find boundaries/relationships containing a
  latitude/longitude point
- Hierarchy definition APIs
- Parent-child relationship APIs with hierarchy-order validation and
  materialized-path tree search (`includeParents`/`includeChildren`)
- Cache configuration with Redis support
- Pubsub events for boundary, hierarchy and relationship lifecycle changes
- Tenant schema separation support
- Canonical APIs using `requestMetadata`, `ResponseMetadata` and `data`
- Canonical API verification report in
  [CANONICAL_API_VERIFICATION_REPORT.md](./CANONICAL_API_VERIFICATION_REPORT.md)
- Postman collection with standard, canonical and geo search examples

## Configuration

Main runtime properties are configured in
`src/main/resources/application.properties`.

| Property | Default | Description |
|---|---|---|
| `server.servlet.context-path` | `${SERVER_CONTEXT_PATH:/boundary}` | Service context path |
| `server.port` | `${HTTP_PORT:8081}` | HTTP port |
| `boundary.api.canonical-prefix` | `${CANONICAL_API_PREFIX:canonical}` | Canonical route prefix |
| `boundary.cache.type` | `${CACHE_TYPE:redis}` | Cache backend |
| `boundary.cache.redis.address` | `${CACHE_REDIS_ADDR:localhost:6379}` | Redis cache address |
| `boundary.pubsub.enabled` | `${PUBSUB_ENABLED:true}` | Event publishing toggle |
| `boundary.pubsub.type` | `${PUBSUB_TYPE:kafka}` | Pubsub backend: Kafka or Redis |
| `digit.tenant-migration.enabled` | `${TENANT_MIGRATION_ENABLED:false}` | Tenant schema separation |

## API Reference

**Base path:** `/boundary/v3`

3.0 routes use request metadata in headers:

| Header | Required on | Description |
|---|---|---|
| `X-Tenant-ID` | All endpoints | Tenant identifier |
| `X-User-ID` | Write endpoints | Audit user |
| `X-Request-ID` | Optional | Request/audit correlation |

### Boundary APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/boundaries` | Create boundaries (batch) |
| `GET` | `/v3/boundaries` | Search boundaries |
| `PUT` | `/v3/boundaries/{id}` | Update boundary |

Create request — a `boundary` array; `geometry` is optional GeoJSON and is
validated when present:

```json
{
  "boundary": [
    {
      "tenantId": "ESMAGICO",
      "code": "WARD-001",
      "geometry": {
        "type": "Polygon",
        "coordinates": [[[77.59, 12.97], [77.60, 12.97], [77.60, 12.98], [77.59, 12.97]]]
      },
      "additionalAttributes": { "name": "Ward 001" }
    }
  ]
}
```

Search query parameters (`GET /v3/boundaries`) — either `codes` or the
`latitude`+`longitude` pair is required:

| Parameter | Description |
|---|---|
| `codes` | Boundary codes (repeatable) |
| `latitude`, `longitude` | Geo search point (see [Geo Search](#geo-search-postgis)); must be passed together |
| `limit`, `offset` | Pagination |

### Hierarchy APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/hierarchy` | Create hierarchy definition |
| `GET` | `/v3/hierarchy` | Search hierarchy definition (`hierarchyType` query parameter required) |
| `PUT` | `/v3/hierarchy/{id}` | Update hierarchy definition (`hierarchyType` itself is not updatable) |

A hierarchy definition names a `hierarchyType` and lists its levels as
`boundaryType` → `parentBoundaryType` links. Validation rules: at least one
level, boundary types unique, every `parentBoundaryType` must exist as a
`boundaryType` in the same list, no self-parenting, and exactly one root
level (with `parentBoundaryType: null`). `hierarchyType` is unique per tenant.

```json
{
  "hierarchy": {
    "tenantId": "ESMAGICO",
    "hierarchyType": "ADMIN",
    "boundaryHierarchy": [
      { "boundaryType": "CITY", "parentBoundaryType": null, "active": true },
      { "boundaryType": "ZONE", "parentBoundaryType": "CITY", "active": true },
      { "boundaryType": "WARD", "parentBoundaryType": "ZONE", "active": true }
    ]
  }
}
```

### Relationship APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/relationship` | Create boundary relationship |
| `GET` | `/v3/relationship` | Search boundary relationships |
| `PUT` | `/v3/relationship/{id}` | Update boundary relationship (re-parent within the hierarchy) |

A relationship places an existing boundary at a level of an existing
hierarchy and links it to a parent relationship:

```json
{
  "relationship": {
    "tenantId": "ESMAGICO",
    "code": "WARD-001",
    "hierarchyType": "ADMIN",
    "boundaryType": "WARD",
    "parent": "ZONE-01"
  }
}
```

Validation on create/update: the hierarchy definition must exist, the
`boundaryType` must be a level in it, the boundary `code` must already exist
as a boundary entity, the (tenant, code, hierarchyType) combination must be
unique, and when `parent` is set the parent relationship must exist and its
`boundaryType` must be the direct parent of the child's type in the hierarchy
definition. Root-level types cannot have a parent. Ancestor paths are stored
as materialized paths and kept consistent when a subtree is re-parented.

Search query parameters (`GET /v3/relationship`):

| Parameter | Description |
|---|---|
| `hierarchyType` | Filter by hierarchy type |
| `boundaryType` | Filter by level |
| `codes` | Relationship codes (repeatable) |
| `parent` | Filter by direct parent code |
| `includeChildren` | `true` returns each match with its full subtree |
| `includeParents` | `true` returns each match with its ancestor chain |
| `latitude`, `longitude` | Geo search point (see [Geo Search](#geo-search-postgis)); must be passed together |
| `limit`, `offset` | Pagination |

With `includeChildren`/`includeParents` the response nests boundaries as a
tree; otherwise it is a flat list.

### Setting up boundary data

Order matters — each step validates against the previous one:

1. `POST /v3/hierarchy` — define the hierarchy (e.g. CITY → ZONE → WARD).
2. `POST /v3/boundaries` — create the boundary entities with geometry.
3. `POST /v3/relationship` — link boundaries top-down, root first (parent
   relationships must exist before their children).

## Geo Search (PostGIS)

Boundary geometry is stored twice: the raw GeoJSON in the `geometry` jsonb
column, and a derived native PostGIS `geom` column
(`GEOMETRY(Geometry, 4326)`, WGS84) with a GIST index, populated via
`ST_GeomFromGeoJSON` on every create and update.

Passing `latitude` and `longitude` to the boundary search runs an
`ST_Contains` point-in-polygon query against `geom`:

```
GET /v3/boundaries?latitude=12.9716&longitude=77.5946
```

The relationship search accepts the same pair: the point is first resolved to
the boundary codes containing it, then the normal relationship search runs on
those codes (caller-supplied `codes` narrow the spatial matches, never widen
them). A point outside every boundary returns an empty result, not an
unfiltered search.

Rules and caveats:

- `latitude` and `longitude` must be passed together; ranges are validated
  (latitude −90..90, longitude −180..180).
- The migration adds `geom` without backfilling: rows created before the geo
  migration keep `geom = NULL` and never match a geo search until they are
  updated through the API.
- The database must be PostGIS-capable. The migration runs
  `CREATE EXTENSION IF NOT EXISTS postgis` when the migration user has the
  privilege; otherwise create the extension manually before migrating.

## Canonical API

Canonical boundary APIs are available under:

`/boundary/v3/{CANONICAL_API_PREFIX}`

Default prefix: `canonical`

Canonical routes carry a top-level `RequestMetadata` object in the request
body instead of the `X-*` headers (the user id comes from the JWT claims in
`RequestMetadata.userInfo`), alongside the same named business payload the
legacy route uses (`boundary`, `hierarchy`, `relationship`) or a named search
criteria object (`boundarySearchCriteria`, `boundaryHierarchySearchCriteria`,
`boundaryRelationshipSearchCriteria`). Responses add a top-level
`ResponseMetadata`.

Create example:

```json
{
  "RequestMetadata": {
    "tenantId": "ESMAGICO",
    "ts": 1712830200000,
    "requestId": "req-123",
    "userInfo": { "sub": "user-1" }
  },
  "boundary": [
    {
      "tenantId": "ESMAGICO",
      "code": "WARD-001",
      "geometry": { "type": "Point", "coordinates": [77.5946, 12.9716] }
    }
  ]
}
```

Search example (canonical GET requests carry criteria in the body; like the
legacy search, either `codes` or `latitude`+`longitude` is required):

```json
{
  "RequestMetadata": { "tenantId": "ESMAGICO", "ts": 1712830200000 },
  "boundarySearchCriteria": { "latitude": 12.9716, "longitude": 77.5946 }
}
```

## Postman Collection

Import [Boundary-3.0.postman_collection.json](./Boundary-3.0.postman_collection.json)
for ready-to-run examples of every endpoint: boundary/hierarchy/relationship
CRUD, the canonical variants, and geo (point-in-polygon) search requests for
both APIs. Collection variables (`baseUrl`, `tenantId`, `hierarchyType`,
`latitude`, `longitude`, ...) drive all requests — adjust them once at the
collection level.

Canonical GET requests carry their metadata in a JSON request body, so those
requests set `protocolProfileBehavior.disableBodyPruning` — keep it if you
copy a request, or Postman silently drops the body.

## API Specification

See [docs/services/boundary/boundary-3.0.0.yaml](../../../docs/services/boundary/boundary-3.0.0.yaml)
for the public API specification.

## Local Run

Start a PostGIS-capable Postgres (required for geo search), then run the
service:

```bash
docker run -d --name boundary-db -p 5432:5432 \
  -e POSTGRES_DB=conformance -e POSTGRES_PASSWORD=1234 postgis/postgis:16-3.4

mvn -pl src/services/boundary spring-boot:run
```

Baseline (public schema) migrations are run out-of-band by the init container
(`src/main/resources/db/Dockerfile` + `migrate.sh`), not by the service at
startup; for a local run apply `src/main/resources/db/migration/*.sql` with
Flyway or psql. Set database and cache environment variables as needed:

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=conformance
export DB_USER=postgres
export DB_PASSWORD=1234
export SERVER_CONTEXT_PATH=/boundary
```
