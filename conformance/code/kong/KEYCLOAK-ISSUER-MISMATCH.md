# `RBAC.NoJWT` on uat-saas: Keycloak issuer mismatch

Status: diagnosed, not fixed. Recorded 2026-09-17 for a later decision.

## Symptom

Any authenticated request through the gateway on **uat-saas** is rejected:

```json
[ { "message": "Unauthorized: Token rejected by Keycloak", "code": "RBAC.NoJWT" } ]
```

The same user's permission, asked of Keycloak directly over the public URL, is granted:

```bash
curl -X POST 'https://uat-saas.digit.org/keycloak/realms/COL5/protocol/openid-connect/token' \
  -H 'Authorization: Bearer <token>' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:uma-ticket' \
  --data-urlencode 'audience=auth-server' \
  --data-urlencode 'permission=/individuals/v3/individuals#get' \
  --data-urlencode 'permission_resource_format=uri' \
  --data-urlencode 'response_mode=decision'
# => {"result": true}
```

## Root cause

Keycloak on uat-saas runs with `KC_PROXY=edge` and **no `KC_HOSTNAME`**, so it derives its
issuer from the incoming `Host` header.

1. A token obtained through the public URL carries
   `iss = https://uat-saas.digit.org/keycloak/realms/<realm>`.
2. `keycloak-rbac` asks Keycloak over the in-cluster address —
   `keycloak_base_url` defaults to `http://keycloak.keycloak.svc.cluster.local:8080/keycloak`
   (`plugins/keycloak-rbac/schema.lua`, passed through at `setup.py`, the `keycloak-rbac`
   entry in the per-route plugin list).
3. Reached at that address, Keycloak expects
   `iss = http://keycloak.keycloak.svc.cluster.local:8080/keycloak/realms/<realm>`.
4. The issuers disagree, so the UMA exchange answers **401**.
5. `check_authorization` maps 401 to `invalid_token`, and the access phase turns that into
   `RBAC.NoJWT` (`plugins/keycloak-rbac/handler.lua`, the `invalid_token` branch).

## Evidence

Same request, same token, same parameters — only the base URL differs. Run from inside each
cluster:

| cluster | `KC_HOSTNAME` | internal URL | public URL |
|---|---|---|---|
| uat-saas | not set | **401** | 200 `{"result":true}` |
| test-lts | `https://test-lts.digit.org/keycloak` (+ `KC_HOSTNAME_STRICT=true`) | **200** | 200 `{"result":true}`|

test-lts is the natural control: identical plugin code and parameters, and the internal call
succeeds there because the issuer is pinned.

## Ruled out

The rejected request reaches the authorization call, so everything before it worked:

- **Signature verification** passed — `dynamic-jwt` populated the JWT payload, otherwise the
  failure would have been the "No valid JWT payload found" branch instead.
- **Realm resolution** worked — taken from the `iss` claim, giving the right realm.
- **Roles** were present — `SUPERUSER` and `ADMIN`; an empty list would have produced
  `RBAC.NoRoles` / 403.
- **Audience** matches: the plugin sends `audience=auth-server`, the same value the working
  manual call used.
- **Token expiry** is not involved — reproduced with more than 3 hours of validity left.
- **The RBAC model is fine.** Resources and permissions resolve correctly; the direct call
  returns `{"result": true}` for the same user, path and method.

## Options

1. **Set `KC_HOSTNAME` on uat-saas Keycloak** to `https://uat-saas.digit.org/keycloak`,
   matching test-lts. Pins one issuer whichever address a caller uses, and fixes this for
   every in-cluster token consumer rather than just the gateway. Needs a Keycloak restart,
   and tokens minted beforehand keep the old `iss`, so sessions issued before the change
   have to be re-established.
2. **Point `keycloak_base_url` at the public URL.** Smaller diff, but then every
   authorization decision leaves the cluster and returns through the ingress, adding latency
   to each request (partly absorbed by the 300s decision cache) and making internal
   authorization depend on external DNS and TLS.

Option 1 is the configuration test-lts already runs.

## To discuss

- Whether anything else on uat-saas validates tokens against the internal issuer today and
  would flip to the public one.
- Whether `KC_HOSTNAME` belongs in the keycloak chart or per-environment values, given
  test-lts also sets `KC_HOSTNAME_STRICT=true`.
- Why the two environments diverged, and whether other settings differ the same way.