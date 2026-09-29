local http = require "resty.http"
local cjson = require "cjson"

local KeycloakRbacHandler = {}

-- Runs after dynamic-jwt (1010), which publishes the verified payload and realm, and after
-- header-enrichment (800), which resolves the tenant.
KeycloakRbacHandler.PRIORITY = 700
KeycloakRbacHandler.VERSION = "3.0.0"

----------------------------------------------------------------
-- Per-worker decision cache
----------------------------------------------------------------
-- Keycloak owns the rules and will only answer one path#scope question at a time, so there
-- is nothing to evaluate locally: the only cacheable thing is the answer it already gave.
-- Hence a flat memo of decisions, populated lazily — a miss costs one Keycloak round trip.
--
-- Deliberately a plain Lua table rather than an ngx.shared dict, since kong.conf declares
-- no lua_shared_dict and a plugin cannot add one. The cost is that each nginx worker keeps
-- its own copy and pays its own cold misses.
local DECISION_CACHE = {}
local DECISION_CACHE_COUNT = 0

local UUID_PATTERN =
  "^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"

local function is_uuid(str)
  return type(str) == "string" and #str == 36 and str:match(UUID_PATTERN) ~= nil
end

-- Collapse strict-UUID segments so every id under the same route shares one entry.
--
-- Safe because Keycloak's {id}/{code} templates match exactly one segment: two paths
-- differing only in UUID-shaped segments have identical static segments in identical
-- positions, so they resolve to the same resource, carry the same scopes, and therefore
-- yield the same decision. This is only ever a cache-bucket choice — the value stored was
-- a real Keycloak answer for a path in that bucket — never an input to the authorization
-- request itself, which always sends the untouched path.
--
-- Note this only handles UUIDs. An ALNUM code segment is indistinguishable from a static
-- one without Keycloak's template list, so those paths stay keyed verbatim and simply get
-- a lower hit rate.
local function cache_path(path, collapse)
  if not collapse then
    return path
  end
  local out = {}
  for segment in path:gmatch("[^/]+") do
    out[#out + 1] = is_uuid(segment) and "{id}" or segment
  end
  return "/" .. table.concat(out, "/")
end

-- Order-independent fingerprint of the caller's roles. Including it means a role change
-- lands on a different key, so new tokens are re-evaluated instead of reusing a stale
-- decision; only realm resource/permission changes wait out the TTL.
local function roles_fingerprint(roles)
  local sorted = {}
  for _, r in ipairs(roles) do
    sorted[#sorted + 1] = r
  end
  table.sort(sorted)
  return table.concat(sorted, ",")
end

local function cache_get(key)
  local hit = DECISION_CACHE[key]
  if not hit then
    return nil
  end
  if hit.exp <= ngx.now() then
    DECISION_CACHE[key] = nil
    DECISION_CACHE_COUNT = DECISION_CACHE_COUNT - 1
    return nil
  end
  return hit
end

local function cache_put(key, allow, conf)
  if conf.cache_ttl <= 0 then
    return
  end
  -- Flush wholesale rather than evict: entries are cheap to rebuild and a true LRU would
  -- need bookkeeping on every read.
  if DECISION_CACHE_COUNT >= conf.max_cache_entries then
    kong.log.notice("keycloak-rbac: decision cache hit ", conf.max_cache_entries,
      " entries, flushing")
    DECISION_CACHE = {}
    DECISION_CACHE_COUNT = 0
  end
  if DECISION_CACHE[key] == nil then
    DECISION_CACHE_COUNT = DECISION_CACHE_COUNT + 1
  end
  DECISION_CACHE[key] = { allow = allow, exp = ngx.now() + conf.cache_ttl }
end

----------------------------------------------------------------
-- Keycloak authorization call
----------------------------------------------------------------

local function urlencode_form(tbl)
  local parts = {}
  for k, v in pairs(tbl) do
    parts[#parts + 1] = k .. "=" .. ngx.escape_uri(tostring(v))
  end
  return table.concat(parts, "&")
end

-- Asks Keycloak whether `token` may perform `method` on `path`.
--
-- Returns (allowed, reason) where reason is one of "allowed", "denied", "unknown_resource"
-- or an error string. `path` is the untouched request path: Keycloak resolves it against
-- the resource URIs itself.
local function check_authorization(conf, token, realm, path, method)
  local httpc = http.new()
  httpc:set_timeout(conf.timeout)

  local url = conf.keycloak_base_url .. "/realms/" .. realm
    .. "/protocol/openid-connect/token"

  local body = urlencode_form({
    grant_type = "urn:ietf:params:oauth:grant-type:uma-ticket",
    audience = conf.client_id,
    permission = path .. "#" .. method:lower(),
    permission_resource_format = "uri",
    -- Without this Keycloak matches resource URIs by exact string equality, so every
    -- templated resource ({id}/{code}) would miss and only literal paths would resolve.
    -- It is what makes the realm's URI templates actually apply. Defaults to false.
    permission_resource_matching_uri = "true",
    response_mode = "decision",
  })

  local res, err = httpc:request_uri(url, {
    method = "POST",
    headers = {
      ["Content-Type"] = "application/x-www-form-urlencoded",
      ["Authorization"] = "Bearer " .. token,
      ["Accept"] = "application/json",
    },
    body = body,
  })

  if not res then
    return false, "keycloak call failed: " .. tostring(err)
  end

  if res.status == 200 then
    return true, "allowed"
  end

  if res.status == 403 then
    return false, "denied"
  end

  -- Keycloak answers 400 invalid_resource when no resource URI matches the request path,
  -- i.e. the endpoint is not declared in the realm at all. That is a different condition
  -- from "a permission matched and refused you", so it is surfaced as 404 rather than 403.
  if res.status == 400 then
    local ok, parsed = pcall(cjson.decode, res.body or "")
    if ok and type(parsed) == "table" and parsed.error == "invalid_resource" then
      return false, "unknown_resource"
    end
    return false, "keycloak rejected the request: " .. (res.body or "")
  end

  if res.status == 401 then
    return false, "invalid_token"
  end

  return false, "keycloak returned status " .. res.status
end

----------------------------------------------------------------
-- Access phase
----------------------------------------------------------------

-- Single-element array: the envelope clients already parse, kept identical so error
-- handling does not have to change.
local function exit_with_error(status, code, message, description)
  local err = { code = code, message = message }
  if description then
    err.description = description
  end
  return kong.response.exit(status, { err })
end

function KeycloakRbacHandler:access(conf)
  if conf.enable_plugin == false then
    return
  end

  local jwt = (ngx.ctx.authenticated_jwt_token or {}).payload
  if not jwt then
    kong.log.warn("keycloak-rbac: no verified JWT payload on the request")
    return exit_with_error(401, "RBAC.NoJWT", "Unauthorized: No valid JWT payload found")
  end

  -- dynamic-jwt resolves this while verifying the signature; the issuer claim and the
  -- enriched tenant are fallbacks for the same value (realm == tenant code).
  local realm = ngx.ctx.authenticated_realm
  if not realm and jwt.iss then
    realm = jwt.iss:match("/realms/([^/]+)")
  end
  realm = realm or ngx.ctx.tenant_id
  if not realm then
    kong.log.err("keycloak-rbac: could not determine the realm for this request")
    return exit_with_error(401, "RBAC.TenantNotIdentified",
      "Unauthorized: Tenant not identified")
  end

  local auth_header = kong.request.get_header("Authorization")
  local token = auth_header and auth_header:match("[Bb]earer%s+(.+)")
  if not token then
    return exit_with_error(401, "RBAC.NoJWT", "Unauthorized: No bearer token provided")
  end

  local roles = {}
  if jwt.realm_access and jwt.realm_access.roles then
    for _, r in ipairs(jwt.realm_access.roles) do
      roles[#roles + 1] = r
    end
  end
  if #roles == 0 then
    kong.log.warn("keycloak-rbac: no realm roles in token for user ", tostring(jwt.sub))
    return exit_with_error(403, "RBAC.NoRoles", "Access Denied: No roles found in token")
  end

  local path = kong.request.get_path()
  local method = string.upper(kong.request.get_method())

  local bypass = kong.request.get_header("X-Rbac-Cache") == "bypass"
  local key = realm .. "|" .. roles_fingerprint(roles) .. "|" .. method .. "|"
    .. cache_path(path, conf.collapse_uuid_in_cache_key)

  if conf.cache_ttl > 0 and not bypass then
    local cached = cache_get(key)
    if cached then
      if cached.allow then
        kong.log.debug("keycloak-rbac: cache hit allow for ", method, " ", path)
        return
      end
      kong.log.debug("keycloak-rbac: cache hit deny for ", method, " ", path)
      return exit_with_error(403, "RBAC.AccessDenied", "Access Denied")
    end
  end

  local allowed, reason = check_authorization(conf, token, realm, path, method)

  if allowed then
    cache_put(key, true, conf)
    kong.log.debug("keycloak-rbac: granted ", method, " ", path, " in realm ", realm)
    return
  end

  if reason == "denied" then
    cache_put(key, false, conf)
    kong.log.notice("keycloak-rbac: denied ", method, " ", path, " in realm ", realm)
    return exit_with_error(403, "RBAC.AccessDenied", "Access Denied")
  end

  if reason == "unknown_resource" then
    -- Not cached: the realm gains resources on reimport, and a 404 is cheap to re-derive.
    kong.log.notice("keycloak-rbac: no resource matches ", method, " ", path,
      " in realm ", realm)
    return exit_with_error(404, "RBAC.ResourceNotFound", "Not Found")
  end

  if reason == "invalid_token" then
    return exit_with_error(401, "RBAC.NoJWT", "Unauthorized: Token rejected by Keycloak")
  end

  -- Never cached, and deliberately fail-closed: an unreachable or erroring Keycloak must
  -- not become an open gateway.
  kong.log.err("keycloak-rbac: authorization check failed: ", tostring(reason))
  return exit_with_error(500, "RBAC.AuthorizationUnavailable",
    "Authorization service unavailable", tostring(reason))
end

return KeycloakRbacHandler
