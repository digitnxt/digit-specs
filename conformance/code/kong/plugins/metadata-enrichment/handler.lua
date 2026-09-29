local cjson = require "cjson"

local MetadataEnrichmentHandler = {}

MetadataEnrichmentHandler.PRIORITY = 690  -- Runs after RBAC (700), so only authorized requests are rewritten
MetadataEnrichmentHandler.VERSION = "1.0.0"

-- Function to check whether a value is missing or blank
local function is_blank(value)
  return value == nil or value == "" or (type(value) == "string" and value:match("^%s*$") ~= nil)
end

-- Function to check whether the request targets a canonical (envelope-style) route.
-- Checked before anything else so a legacy request never has its body read.
local function is_canonical_path(conf)
  local path = kong.request.get_path()
  if not path then
    return false
  end
  for _, fragment in ipairs(conf.canonical_path_segments or {}) do
    if fragment ~= "" and path:find(fragment, 1, true) then
      return true
    end
  end
  return false
end

-- Function to get the JWT payload verified by the dynamic-jwt plugin.
-- Only ngx.ctx.authenticated_jwt_token is read: that key exists solely because a signature was
-- checked. Decoding the token here instead would put unverified claims into a block that downstream
-- services treat as gateway-attested.
local function get_verified_payload()
  local token = ngx.ctx.authenticated_jwt_token
  if type(token) == "table" and type(token.payload) == "table" then
    return token.payload
  end
  return nil
end

-- Function to find the metadata envelope in the decoded body.
-- Config order is the services' read order, so the gateway enriches the block they will read.
local function find_metadata(body, conf)
  for _, key in ipairs(conf.metadata_keys or {}) do
    local block = body[key]
    if type(block) == "table" then
      return block, key
    end
  end
  return nil, nil
end

-- Function to build the display name from the token claims.
-- The IdP's own `name` first, because it respects the realm's name ordering; given + family is the
-- fallback for users with no name configured.
local function get_display_name(payload)
  if not is_blank(payload.name) then
    return payload.name
  end
  if not is_blank(payload.given_name) and not is_blank(payload.family_name) then
    return payload.given_name .. " " .. payload.family_name
  end
  if not is_blank(payload.given_name) then
    return payload.given_name
  end
  if not is_blank(payload.family_name) then
    return payload.family_name
  end
  return nil
end

-- Function to collect realm roles as a flat array of strings.
-- Flat strings because the services read each element as text: an object element reads as "" and is
-- dropped. Realm roles only - resource_access holds Keycloak's own admin-API permissions, which carry
-- no business meaning to a service.
local function get_realm_roles(payload)
  local roles = {}
  local realm_access = payload.realm_access
  if type(realm_access) == "table" and type(realm_access.roles) == "table" then
    for _, role in ipairs(realm_access.roles) do
      if type(role) == "string" and not is_blank(role) then
        table.insert(roles, role)
      end
    end
  end
  -- Tagged as an array so an empty list encodes as [] and not {}. A JSON object fails the services'
  -- isArray() check, which sends them to userInfo.roleCodes - a key the client controls.
  return setmetatable(roles, cjson.empty_array_mt)
end

-- Function to populate userInfo from the verified token claims.
-- Fields the token asserts are overwritten; fields it says nothing about are left as the client sent
-- them. userInfo is created when absent, because the services require userInfo.uuid on writes and
-- withholding it would fail a request the gateway had the answer for.
local function enrich_user_info(metadata, payload)
  local user_info = metadata.userInfo
  if type(user_info) ~= "table" then
    user_info = {}
    metadata.userInfo = user_info
  end

  if not is_blank(payload.sub) then
    user_info.uuid = payload.sub
  end
  if not is_blank(payload.preferred_username) then
    user_info.userName = payload.preferred_username
  end
  if not is_blank(payload.email) then
    user_info.email = payload.email
  end

  local display_name = get_display_name(payload)
  if display_name then
    user_info.name = display_name
  end

  -- Always written, even when empty, so a client-supplied roleCodes can never become the effective
  -- role list.
  user_info.roles = get_realm_roles(payload)
end

function MetadataEnrichmentHandler:access(conf)
  if not is_canonical_path(conf) then
    return
  end

  kong.log.debug("Metadata enrichment plugin access phase started")

  local payload = get_verified_payload()
  if not payload then
    kong.log.debug("No verified JWT payload found, passing request through")
    return
  end

  -- JSON only, so multipart and binary uploads are never buffered to look for an envelope
  local content_type = kong.request.get_header("Content-Type")
  if content_type == nil or not content_type:lower():find("json", 1, true) then
    kong.log.debug("Content-Type is not JSON, passing request through")
    return
  end

  -- The limit is handed to the PDK rather than checked afterwards. Only client_body_buffer_size (8k on
  -- the proxy listener by default) is held in memory; anything larger is spooled to a temp file, and
  -- without a limit argument the PDK refuses to read that file at all. So a size check after the read
  -- could never fire, while passing the limit both lets a larger canonical body be read and rejects an
  -- oversized one by its file size, before any of it is loaded.
  local raw_body, err = kong.request.get_raw_body(conf.max_body_bytes)
  if err then
    -- Logged rather than swallowed: the PDK's message names the nginx setting to raise, which is
    -- otherwise invisible to whoever is wondering why enrichment stopped happening.
    kong.log.warn("Could not read request body, passing request through: " .. tostring(err))
    return
  end
  if raw_body == nil or #raw_body == 0 then
    kong.log.debug("No request body to enrich")
    return
  end

  -- Malformed JSON is left alone: reporting it is the service's job, which names the offending field
  -- with its own error code
  local ok, body = pcall(cjson.decode, raw_body)
  if not ok or type(body) ~= "table" then
    kong.log.debug("Request body is not a JSON object, passing request through")
    return
  end

  local metadata, metadata_key = find_metadata(body, conf)
  if not metadata then
    kong.log.debug("No metadata envelope in request body, passing request through")
    return
  end

  if conf.enrich_tenant_id then
    -- The value that became X-Tenant-ID, not a fresh derivation from the issuer. X-Tenant-ID selects
    -- the database schema while this field is persisted as data, so deriving them separately is how
    -- rows land in one tenant's schema carrying another tenant's id.
    if not is_blank(ngx.ctx.tenant_id) then
      metadata.tenantId = ngx.ctx.tenant_id
    end
  end

  if conf.enrich_timestamp then
    -- ngx.now() is cached per request, so this is the same instant stamped into X-Time-Stamp
    metadata.ts = math.floor(ngx.now() * 1000)
  end

  if conf.enrich_tracing_ids then
    -- Filled only when absent: correlationId is specified as propagating unchanged, and requestId is
    -- the caller's own tracking value, which some services persist
    if is_blank(metadata.requestId) and not is_blank(ngx.ctx.kong_request_id) then
      metadata.requestId = ngx.ctx.kong_request_id
    end
    if is_blank(metadata.correlationId) and not is_blank(ngx.ctx.correlation_id) then
      metadata.correlationId = ngx.ctx.correlation_id
    end
  end

  if conf.enrich_user_info then
    enrich_user_info(metadata, payload)
  end

  local encoded_ok, encoded = pcall(cjson.encode, body)
  if not encoded_ok then
    -- Fail open: an unenriched canonical write earns a clean 400 from the service, whereas a 500 from
    -- the gateway earns a support ticket
    kong.log.warn("Failed to re-encode request body, passing request through: " .. tostring(encoded))
    return
  end

  kong.service.request.set_raw_body(encoded)
  kong.log.debug("Enriched " .. metadata_key .. " for user " .. (payload.sub or "unknown"))
  kong.log.debug("Metadata enrichment plugin access phase completed")
end

return MetadataEnrichmentHandler
