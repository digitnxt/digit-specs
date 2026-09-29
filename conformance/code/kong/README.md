# Kong

This directory contains the custom Kong image configuration and plugins used by DIGIT.

## One bootstrap script per environment

| Script | Cluster |
|---|---|
| `setup-test-lts.py` | test-lts |
| `setup-uat-saas.py` | uat-saas |

They differ in one thing: the upstream DNS names. test-lts runs the core services under `-java`
names (`otp-java`, `account-java`, …), uat-saas does not. `template-config-java` is the exception
and keeps the suffix in both.

**Run the one that matches the cluster.** Pointing the wrong script at a cluster does not fail
loudly — it upserts Kong services whose upstreams resolve to nothing, and requests start failing at
the gateway with no obvious cause.

Everything else in the two files — routes, whitelists, plugins — is meant to stay identical, so a
change to one almost always belongs in the other. There is no shared module, so nothing enforces
that; it is on whoever makes the change.

Both scripts are additive: they upsert what they declare and never remove routes or services they
do not know about. Several product-team services (`vc` among them) have routes created outside
these scripts, and running a script will not disturb them.

## Whitelisting an API

Whitelisting is done by creating a dedicated Kong route for the public API and leaving
authentication plugins off that route. The normal service route remains protected.

Use this when an endpoint must be reachable without JWT authentication or Keycloak RBAC
authorization.

To make it stick, add the route to `NEW_ROUTES_FOR_KONG` in **both** bootstrap scripts — routes in
that list get no plugins, which is what makes them public. The Admin API steps below are the same
thing done by hand, and are useful for trying a route out before committing it; anything created
only by hand is lost the next time someone rebuilds Kong from the script.

### 1. Identify the Kong Admin API URL

Use the Admin API URL for the environment you are changing.

Kong commonly uses these default ports:

| Port | Purpose |
| --- | --- |
| `8000` | Proxy HTTP |
| `8443` | Proxy HTTPS |
| `8001` | Admin API HTTP |
| `8444` | Admin API HTTPS |

For local Docker Compose:

```bash
export KONG_ADMIN_URL=http://localhost:8097
```

The local compose setup maps host port `8097` to Kong's Admin API port `8001`.

From inside the Docker or Kubernetes network:

```bash
export KONG_ADMIN_URL=http://kong:8001
```

### 2. Identify the owning Kong service

The whitelist route must point to the same upstream service as the protected route.

Examples:

| API path prefix | Kong service |
| --- | --- |
| `/otp` | `otp` |
| `/filestore` | `filestore` |
| `/idgen` | `idgen` |
| `/workflow` | `workflow` |
| `/mdms-v2` | `mdms-v2` |
| `/individuals` | `individual` |
| `/registry` | `registry` |
| `/billing` | `billing` |
| `/employee` | `employee` |
| `/pg-service` | `pg-service` |

Confirm the protected route if needed:

```bash
curl -s "$KONG_ADMIN_URL/routes/otp-route"
```

### 3. Create a dedicated whitelist route

Create a route with a more specific path than the protected service route. Set the
HTTP method explicitly so only the required operation is public.

Example: whitelist `POST /otp/_send`.

```bash
curl -X PUT "$KONG_ADMIN_URL/routes/whitelist-post-otp-send" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "whitelist-post-otp-send",
    "paths": ["/otp/_send"],
    "methods": ["POST"],
    "strip_path": false,
    "protocols": ["http", "https"],
    "service": { "name": "otp" }
  }'
```

Do not attach these authentication plugins to the whitelist route:

```text
dynamic-jwt
keycloak-rbac
token-revocation
```

Kong will route matching `POST /otp/_send` requests to the whitelist route. Other
methods or other `/otp` paths continue to match the protected `otp-route`.

### 4. Add enrichment plugins only if the upstream needs gateway headers

If the upstream service expects Kong request headers, attach `header-enrichment` to the
whitelist route.

```bash
curl -X POST "$KONG_ADMIN_URL/routes/whitelist-post-otp-send/plugins" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "header-enrichment",
    "config": {
      "enable_api_headers": true,
      "enable_jwt_headers": true,
      "tenant_header_sources": [
        "X-JWT-tenant",
        "X-JWT-organization",
        "X-Tenant-ID"
      ],
      "default_tenant": "default"
    }
  }'
```

For canonical APIs that require request body metadata enrichment, also attach
`metadata-enrichment` with the same config used by the protected routes.

### 5. Verify the endpoint

Call the endpoint without an `Authorization` header.

```bash
curl -i -X POST "http://localhost/otp/_send"
```

The request should not fail at Kong with `JWT.NoToken`, `RBAC.NoJWT`, or
`RBAC.AccessDenied`.

Also verify that a non-whitelisted method is still protected:

```bash
curl -i -X GET "http://localhost/otp/_send"
```

### 6. Remove a whitelist

Delete the dedicated whitelist route.

```bash
curl -X DELETE "$KONG_ADMIN_URL/routes/whitelist-post-otp-send"
```

No Kong restart is required for route or plugin changes made through the Admin API.
