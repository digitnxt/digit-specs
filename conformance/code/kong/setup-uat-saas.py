#!/usr/bin/env python3
"""
Idempotent Kong bootstrap script — uat-saas.

There is a sibling script per environment because the upstream DNS names differ: test-lts runs the
core services under -java names, uat-saas does not. Everything else — routes, whitelists, plugins —
is meant to stay identical, so a change to one almost always belongs in the other too.

Note template-config is the exception: it is template-config-java in both.

Usage:
    export KONG_ADMIN_URL=http://127.0.0.1:8097
    python3 setup-uat-saas.py

Environment:
    KONG_ADMIN_URL   (default http://localhost:8001)
    KEYCLOAK_URL     (default http://keycloak.keycloak.svc.cluster.local:8080/keycloak)

The script (re)creates:
  - core upstream services pointing at in-cluster DNS names
  - HTTP routes that keep the request path (strip_path False), so each service must
    serve under the route's own prefix as its context path
  - Per-route plugins: dynamic-jwt, keycloak-rbac, header-enrichment, metadata-enrichment

Authorization is enforced by keycloak-rbac, which asks Keycloak's Authorization Services
about each request. The resources and permissions it consults are created when the account
service imports a tenant's realm config, so there is no separate rule registry to seed.
It is safe to run multiple times; existing objects are updated.
"""

import json
import os
import sys
from typing import Dict, Iterable, List

import requests
from requests import Response

DEFAULT_ADMIN = "http://localhost:8001"
# In-cluster Keycloak base URL. The dynamic-jwt plugin fetches JWKS from here,
# so it must be a URL Kong can reach from inside the cluster (not the public,
# browser-facing issuer host). Override per-environment with KEYCLOAK_URL.
DEFAULT_KEYCLOAK = "http://keycloak.keycloak.svc.cluster.local:8080/keycloak"
# Routes carry a host match, and it is load-bearing beyond tidiness: in Kong's traditional router a
# route matching on host outranks one that does not, so a hostless whitelist loses to the
# host-matching service route it was meant to carve out of — and loses silently, still answering 401.
DEFAULT_HOSTS = [
    host.strip()
    for host in os.environ.get("KONG_ROUTE_HOSTS", "uat-saas.digit.org").split(",")
    if host.strip()
]


SERVICES: Dict[str, str] = {
    "workflow": "http://workflow.egov.svc.cluster.local:8080",
    "localization": "http://localization.egov.svc.cluster.local:8080",
    "notification": "http://notification.egov.svc.cluster.local:8080",
    "notify": "http://notify.egov.svc.cluster.local:8080",
    "template-config": "http://template-config-java.egov.svc.cluster.local:8080",
    "boundary": "http://boundary.egov.svc.cluster.local:8080",
    "boundary-management": "http://boundary-management.egov.svc.cluster.local:8080",
    "urlshortener": "http://url-shortener.egov.svc.cluster.local:8080",
    "idgen": "http://idgen.egov.svc.cluster.local:8080",
    "account": "http://account.egov.svc.cluster.local:8080",
    "filestore": "http://filestore.egov.svc.cluster.local:8080",
    "mdms-v2": "http://mdms-v2.egov.svc.cluster.local:8080",
    "individual": "http://individual.egov.svc.cluster.local:8080",
    "otp": "http://otp.egov.svc.cluster.local:8080",
    "registry": "http://registry.egov.svc.cluster.local:8080",
    "billing": "http://billing.egov.svc.cluster.local:8080",
    "employee": "http://employee.egov.svc.cluster.local:8080",
    "keycloak": "http://keycloak.keycloak.svc.cluster.local:8080",
    "pg-service": "http://pg-service.egov.svc.cluster.local:8080",
    "apportion": "http://apportion.egov.svc.cluster.local:8080",
    "license-certificate": "http://license-certificate.egov.svc.cluster.local:8080",
    "schema-registry": "http://schema-registry.egov.svc.cluster.local:8080",
    "pdf-v3": "http://pdf-v3.egov.svc.cluster.local:8080",
    "calculator": "http://calculator.egov.svc.cluster.local:8080",
    "vc": "http://vc.egov.svc.cluster.local:8080",
}

ROUTES: List[Dict] = [
    {"name": "template-config-route", "paths": ["/template-config"], "service": "template-config"},
    {"name": "notification-route", "paths": ["/notification"], "service": "notification"},
    {"name": "notify-route", "paths": ["/notify"], "service": "notify"},
    {"name": "mdms-route", "paths": ["/mdms-v2"], "service": "mdms-v2"},
    {"name": "workflow-route", "paths": ["/workflow"], "service": "workflow"},
    {"name": "filestore-route", "paths": ["/filestore"], "service": "filestore"},
    {"name": "localization-route", "paths": ["/localization"], "service": "localization"},
    {"name": "shortener-route", "paths": ["/url-shortener"], "service": "urlshortener"},
    {"name": "boundary-route", "paths": ["/boundary"], "service": "boundary"},
    {"name": "boundary-management-route", "paths": ["/boundary-management"], "service": "boundary-management"},
    {"name": "individual-route", "paths": ["/individuals"], "service": "individual"},
    {"name": "idgen-route", "paths": ["/idgen"], "service": "idgen"},
    {"name": "employee-route", "paths": ["/employee"], "service": "employee"},
    {"name": "otp-route", "paths": ["/otp"], "service": "otp"},
    {"name": "registry-route", "paths": ["/registry"], "service": "registry"},
    {"name": "billing-route", "paths": ["/billing"], "service": "billing"},
    {"name": "pg-service-route", "paths": ["/pg-service"], "service": "pg-service"},
    {"name": "apportion-route", "paths": ["/apportion"], "service": "apportion"},
    # Account is otherwise plugin-free (see NEW_ROUTES_FOR_KONG), but its config endpoints are
    # only reached after the tenant — and therefore the tenant's realm — exists, so they can be
    # authorized like any other service. header-enrichment derives X-Tenant-Id from the token's
    # realm, so config is scoped by the token rather than by a header the caller supplies.
    # Writes only. GET is served by account-config-read-route below, which carries no plugins.
    # The two are disjoint by method rather than relying on router precedence between an
    # unqualified and a method-qualified route, so every request matches exactly one of them.
    {"name": "account-config-route",
     "paths": ["/accounts/v3/config", "/accounts/v3/canonical/config"],
     "methods": ["POST", "PUT"],
     "service": "account"},
]

# Routes deliberately left without plugins: tenant creation and the Keycloak
# endpoints have to be reachable before any realm exists to authorize against.
#
# The account *config* endpoints are the exception, and they live in ROUTES above
# with the full chain: a tenant's config is only ever touched after that tenant
# exists, so its realm exists too and there is something to authorize against.
# Their route paths are longer than /accounts, so Kong's prefix match sends those
# requests to the plugin-bearing route rather than this one.
NEW_ROUTES_FOR_KONG: List[Dict] = [
    {"name": "account-route", "paths": ["/accounts"], "service": "account"},
    {"name": "keycloak-route", "paths": ["/keycloak"], "service": "keycloak"},
    # Config search is whitelisted: a tenant's login screen has to read config before anyone can
    # hold a token for that realm, so requiring one here is circular. GET only — POST and PUT stay
    # on account-config-route with the full plugin chain. Note the consequence: header-enrichment
    # does not run here, so X-Tenant-Id is taken from the caller verbatim and any client can read
    # any tenant's config. Keep tenant config free of secrets.
    {"name": "account-config-read-route",
     "paths": ["/accounts/v3/config", "/accounts/v3/canonical/config"],
     "methods": ["GET"],
     "service": "account"},

    # Public read-only certificate metadata. A citizen-facing form has to render — fetch the
    # certificate type, its form config, checklist schema, workflow definition, PDF template and
    # fee rules — before the citizen has signed in, so requiring a token here is circular in the
    # same way account-config-read-route is.
    #
    # Regex paths, not prefixes, and anchored with $: the service-wide routes (/license, /schema,
    # /pdf-v3, /calculator) carry the plugin chain, and a bare prefix here would open every sibling
    # path under it. Kong's traditional router evaluates regex routes ahead of prefix routes, so
    # these win over the authenticated route for exactly these URLs and nothing else.
    # GET only — every write stays on the authenticated route.
    {"name": "license-certificate-types-public-route",
     "paths": [r"~/license/certificate-types$",
               r"~/license/certificate-types/[^/]+$",
               r"~/license/certificate-types/[^/]+/categories$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "license-certificate"},
    {"name": "workflow-process-definition-public-route",
     "paths": [r"~/workflow/v3/process/definition/[^/]+$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "workflow"},
    {"name": "schema-certificate-form-config-public-route",
     "paths": [r"~/schema/certificate/[^/]+\.form/config$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "schema-registry"},
    {"name": "schema-certificate-checklist-schema-public-route",
     "paths": [r"~/schema/certificate/[^/]+\.checklist/schema$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "schema-registry"},
    {"name": "schema-module-schema-public-route",
     "paths": [r"~/schema/[^/]+/schema$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "schema-registry"},
    {"name": "schema-module-config-public-route",
     "paths": [r"~/schema/[^/]+/[^/]+/config$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "schema-registry"},
    {"name": "pdf-template-public-route",
     "paths": [r"~/pdf-v3/[^/]+/template$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "pdf-v3"},
    {"name": "calculator-rules-public-route",
     "paths": [r"~/calculator/calculation/v3/[^/]+/rules$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "calculator"},

    # Public read-only credential metadata: the list of credential types, and one type by name.
    #   GET /credential/credential-types
    #   GET /credential/{credentialType}
    #
    # The second is matched by shape, not by a bare wildcard, and the distinction matters.
    # /credential/tenants and /credential/{credentialType} are both one segment under /credential,
    # so [^/]+ would make tenants public too — and since Kong's traditional router puts regex routes
    # ahead of prefix routes, it would win over the authenticated /credential route that protects it
    # today. Credential types are uppercase (BUSINESS_LICENSE, FIRE_NOC, LEARNER_LICENSE — tenant
    # data, created per tenant, so they cannot be enumerated here), while the service's own
    # endpoints are lowercase. Keying on that keeps tenants, and any lowercase endpoint added later,
    # on the authenticated route by default.
    #
    # Fails closed: a credential type that breaks the uppercase convention is not matched here and
    # simply stays behind auth. /credential/{credentialCode}/jwt is two segments and is excluded by
    # the $ anchor either way.
    {"name": "vc-credential-public-route",
     "paths": [r"~/credential/credential-types$",
               r"~/credential/[A-Z][A-Z0-9_]*$"],
     "methods": ["GET"],
     "regex_priority": 100,
     "service": "vc"},
]

PLUGIN_DEFINITIONS = [
    (
        "dynamic-jwt",
        lambda kc: {
            "keycloak_base_url": kc,
            "cache_ttl": 3600,
            "claims_to_verify": ["exp"],
            "header_names": ["authorization"],
            "uri_param_names": ["jwt"],
        },
    ),
    (
        "keycloak-rbac",
        lambda kc: {
            "keycloak_base_url": kc,
            "client_id": "auth-server",
            "timeout": 5000,
            # Decisions are memoised per nginx worker; 0 calls Keycloak on every request.
            "cache_ttl": 300,
            "max_cache_entries": 10000,
            "collapse_uuid_in_cache_key": True,
        },
    ),
    (
        "header-enrichment",
        lambda _kc: {
            "enable_api_headers": True,
            "enable_jwt_headers": True,
            "tenant_header_sources": [
                "X-JWT-tenant",
                "X-JWT-organization",
                "X-Tenant-ID",
            ],
            "default_tenant": "default",
        },
    ),
    (
        # Body-side counterpart of header-enrichment: projects the same trusted context into
        # RequestMetadata for canonical routes. A request whose path matches none of the segments
        # below is forwarded without its body being read, so legacy routes are unaffected.
        "metadata-enrichment",
        lambda _kc: {
            "canonical_path_segments": ["/v3/canonical/"],
            "metadata_keys": ["RequestMetadata", "requestMetadata"],
            "max_body_bytes": 1048576,
            "enrich_user_info": True,
            "enrich_tenant_id": True,
            "enrich_timestamp": True,
            "enrich_tracing_ids": True,
        },
    ),
    (
        # Logout / JWT revocation. Two branches, deliberately triggered differently: the CHECK runs on
        # every authenticated request and is NOT path-gated, because a revoked token has to fail
        # everywhere and a path gate would be a bypass. The REVOKE fires only on a POST whose final path
        # segment is "logout". Method and key claim are fixed in code, not config: allowing GET would let
        # a prefetched link log a user out, and keying on jti would leave sibling tokens working.
        "token-revocation",
        lambda _kc: {
            "enable_plugin": True,
            "logout_path_segment": "logout",
            "terminate_on_logout": True,
            # ssoSessionMaxLifespan (36000) + accessTokenLifespan (14400). The key must outlast the
            # session AND the last token it could mint, or a client that keeps refreshing outlives the
            # key and is accepted again. Keep in step with the realm: account sets both from
            # ACCESS_TOKEN_LIFESPAN and SSO_SESSION_MAX_LIFESPAN, so raising either means raising this.
            "revocation_ttl": 50400,
            # Redis unreachable means we cannot tell "not revoked" from "don't know", so deny.
            "fail_open": False,
            # Redis is in the backbone namespace, not egov: redis.egov does not resolve from Kong.
            # Every service uses this host, and it answers PING without AUTH, so no password is set.
            "redis_host": os.environ.get("KONG_REVOCATION_REDIS_HOST", "redis.backbone.svc.cluster.local"),
            "redis_port": 6379,
            "redis_database": 0,
            "redis_timeout": 1000,
        },
    ),
]


def request(method: str, url: str, **kwargs) -> Response:
    resp = requests.request(method, url, timeout=30, **kwargs)
    if resp.status_code >= 400:
        raise RuntimeError(f"{method} {url} failed: {resp.status_code} {resp.text}")
    return resp


def upsert_service(admin_url: str, name: str, upstream_url: str) -> None:
    url = f"{admin_url}/services/{name}"
    payload = {"name": name, "url": upstream_url}
    request("PUT", url, json=payload)


def upsert_route(admin_url: str, route_def: Dict) -> Dict:
    url = f"{admin_url}/routes/{route_def['name']}"
    payload = {
        "name": route_def["name"],
        "paths": route_def["paths"],
        "strip_path": False,
        "service": {"name": route_def["service"]},
        "protocols": ["http", "https"],
    }
    hosts = route_def.get("hosts", DEFAULT_HOSTS)
    if hosts:
        payload["hosts"] = hosts
    # Only sent when the route declares it. Omitting the key leaves Kong's default (match every
    # method); sending null would be the same thing but makes the PUT payload lie about intent.
    if route_def.get("methods"):
        payload["methods"] = route_def["methods"]
    # Only meaningful for regex paths: orders this route against other regex routes. Prefix routes
    # ignore it, so it is sent only when declared.
    if route_def.get("regex_priority") is not None:
        payload["regex_priority"] = route_def["regex_priority"]
    resp = request("PUT", url, json=payload)
    return resp.json()


def ensure_plugin(admin_url: str, route_id: str, plugin_name: str, config: Dict) -> None:
    base = f"{admin_url}/routes/{route_id}/plugins"
    resp = request("GET", base, params={"name": plugin_name})
    records = [
        plugin
        for plugin in resp.json().get("data", [])
        if plugin.get("name") == plugin_name
    ]
    if not records:
        request("POST", base, json={"name": plugin_name, "config": config})
        return

    # If multiple instances exist, keep the first and drop the rest to avoid duplicates.
    primary = records[0]
    for duplicate in records[1:]:
        request("DELETE", f"{admin_url}/plugins/{duplicate['id']}")

    plugin_id = primary["id"]
    try:
        request("PATCH", f"{admin_url}/plugins/{plugin_id}", json={"config": config})
        return
    except RuntimeError:
        # delete and recreate on schema errors or outdated config
        request("DELETE", f"{admin_url}/plugins/{plugin_id}")

    # ensure clean create (if another instance appeared, delete it then create)
    resp = request("GET", base, params={"name": plugin_name})
    for existing in [
        plugin
        for plugin in resp.json().get("data", [])
        if plugin.get("name") == plugin_name
    ]:
        request("DELETE", f"{admin_url}/plugins/{existing['id']}")
    request("POST", base, json={"name": plugin_name, "config": config})


def iter_routes(admin_url: str) -> Iterable[Dict]:
    url = f"{admin_url}/routes"
    next_url = url
    while next_url:
        resp = request("GET", next_url)
        body = resp.json()
        for item in body.get("data", []):
            yield item
        next_url = body.get("next")


def main() -> int:
    admin_url = os.environ.get("KONG_ADMIN_URL", DEFAULT_ADMIN).rstrip("/")
    keycloak_base = os.environ.get("KEYCLOAK_URL", DEFAULT_KEYCLOAK)

    try:
        request("GET", admin_url)
    except Exception as exc:  # pragma: no cover - CLI guard
        print(f"Can't reach Kong Admin API at {admin_url}: {exc}", file=sys.stderr)
        return 1

    print("Creating services...")
    for svc, upstream in SERVICES.items():
        upsert_service(admin_url, svc, upstream)
        print(f"  ✓ {svc}")

    print("\nCreating routes + plugins...")
    route_index = {}
    
    # Routes that require plugins
    for route_def in ROUTES:
        route_obj = upsert_route(admin_url, route_def)
        route_index[route_def["name"]] = route_obj["id"]
        print(f"  ✓ route {route_def['name']} -> {route_def['paths']}")

    # Routes that do not require plugins
    print("\nCreating new routes (without plugins)...")
    for route_def in NEW_ROUTES_FOR_KONG:
        route_obj = upsert_route(admin_url, route_def)
        # We don't add to route_index as these routes don't get plugins
        print(f"  ✓ new route {route_def['name']} -> {route_def['paths']}")


    print("\nApplying plugins...")
    # Apply plugins only to routes from the original ROUTES list
    for route_name, route_id in route_index.items():
        for plugin_name, factory in PLUGIN_DEFINITIONS:
            cfg = factory(keycloak_base)
            ensure_plugin(admin_url, route_id, plugin_name, cfg)
        print(f"  ✓ plugins for {route_name}")

    print("\nDone.")
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI entry
    sys.exit(main())