local http = require "resty.http"
local cjson = require "cjson"
local jwt = require "resty.jwt"

local DynamicJwtHandler = {}

DynamicJwtHandler.PRIORITY = 1010  -- Higher priority than built-in JWT plugin
DynamicJwtHandler.VERSION = "1.0.0"

-- Cache for public keys
local jwks_cache = {}

-- Function to convert base64url to base64
local function base64url_to_base64(str)
  str = str:gsub('-', '+'):gsub('_', '/')
  local padding = 4 - (#str % 4)
  if padding ~= 4 then
    str = str .. string.rep('=', padding)
  end
  return str
end

-- Function to decode JWT payload
local function decode_jwt_payload(token)
  local parts = {}
  for part in token:gmatch("[^%.]+") do
    table.insert(parts, part)
  end
  
  if #parts ~= 3 then
    return nil, "Invalid JWT format"
  end
  
  local payload_b64 = base64url_to_base64(parts[2])
  local ok, payload_json = pcall(function()
    return ngx.decode_base64(payload_b64)
  end)
  
  if not ok or not payload_json then
    return nil, "Failed to decode payload"
  end
  
  local ok2, payload = pcall(cjson.decode, payload_json)
  if not ok2 or not payload then
    return nil, "Failed to parse JSON payload"
  end
  
  return payload, nil
end

-- Function to extract realm from issuer
local function extract_realm_from_issuer(issuer)
  if not issuer then
    return nil
  end
  
  -- Extract realm from issuer like "http://localhost:8080/realms/acme-corp"
  local realm = issuer:match("/realms/([^/]+)")
  return realm
end

-- Function to fetch JWKS from Keycloak
local function fetch_jwks(issuer, base_url)
  local realm = extract_realm_from_issuer(issuer)
  if not realm then
    return nil, "Could not extract realm from issuer"
  end

  local cache_key = realm .. "_jwks"
  local cached_jwks = jwks_cache[cache_key]

  -- Check cache first
  if cached_jwks and cached_jwks.expires > ngx.time() then
    kong.log.debug("Using cached JWKS for realm: " .. realm)
    return cached_jwks.data, nil
  end

  kong.log.debug("Fetching JWKS for realm: " .. realm)

  local httpc = http.new()
  httpc:set_timeout(5000)  -- 5 second timeout

  -- Build the JWKS URL from the configured Keycloak base URL, not from the
  -- token issuer. The issuer is a public, browser-facing URL (e.g. an HTTPS
  -- host) which Kong cannot reliably reach from inside the cluster; only the
  -- realm is taken from the issuer, the host comes from configuration.
  local base = base_url:gsub("/+$", "")
  local jwks_url = base .. "/realms/" .. realm .. "/protocol/openid-connect/certs"
  kong.log.debug("JWKS URL: " .. jwks_url)
  
  local res, err = httpc:request_uri(jwks_url, {
    method = "GET",
    headers = {
      ["Accept"] = "application/json"
    }
  })
  
  if not res then
    kong.log.err("Failed to fetch JWKS: " .. tostring(err))
    return nil, "Failed to fetch JWKS: " .. tostring(err)
  end
  
  if res.status ~= 200 then
    kong.log.err("JWKS endpoint returned status: " .. res.status)
    return nil, "JWKS endpoint returned status: " .. res.status
  end
  
  local ok, jwks = pcall(cjson.decode, res.body)
  if not ok or not jwks then
    kong.log.err("Failed to parse JWKS response")
    return nil, "Failed to parse JWKS response"
  end
  
  -- Cache the JWKS
  jwks_cache[cache_key] = {
    data = jwks,
    expires = ngx.time() + 3600  -- Cache for 1 hour
  }
  
  kong.log.debug("Successfully fetched and cached JWKS for realm: " .. realm)
  return jwks, nil
end

-- Function to find signing key from JWKS
local function find_signing_key(jwks, kid)
  if not jwks or not jwks.keys then
    return nil, "No keys in JWKS"
  end
  
  for _, key in ipairs(jwks.keys) do
    if key.use == "sig" and key.kty == "RSA" then
      -- If kid is provided, match it; otherwise use first signing key
      if not kid or key.kid == kid then
        return key, nil
      end
    end
  end
  
  return nil, "No matching signing key found"
end

-- Function to convert JWK to PEM
local function jwk_to_pem(jwk)
  if not jwk.x5c or not jwk.x5c[1] then
    return nil, "No x5c certificate in JWK"
  end
  
  local cert_b64 = jwk.x5c[1]
  local cert_pem = "-----BEGIN CERTIFICATE-----\n"
  
  -- Add line breaks every 64 characters
  for i = 1, #cert_b64, 64 do
    cert_pem = cert_pem .. cert_b64:sub(i, i + 63) .. "\n"
  end
  
  cert_pem = cert_pem .. "-----END CERTIFICATE-----"
  
  return cert_pem, nil
end

-- Function to create error response with proper field order
local function create_error_response(code, message, description)
  local error_obj = {}
  error_obj.code = code
  error_obj.message = message
  if description then
    error_obj.description = description
  end
  return { error_obj }
end

-- Function to verify JWT using resty.jwt
local function verify_jwt_with_jwks(token, jwks, kid)
  local signing_key, err = find_signing_key(jwks, kid)
  if not signing_key then
    return nil, err
  end
  
  local cert_pem, err = jwk_to_pem(signing_key)
  if not cert_pem then
    return nil, err
  end
  
  -- Verify JWT using resty.jwt with proper claim verification
  local jwt_obj = jwt:verify(cert_pem, token)
  
  if not jwt_obj or not jwt_obj.valid then
    return nil, "JWT verification failed"
  end
  
  return jwt_obj.payload, nil
end

function DynamicJwtHandler:access(conf)
  kong.log.debug("Dynamic JWT plugin access phase started")
  
  -- Extract JWT token from request
  local token = nil
  
  -- Check headers
  for _, header_name in ipairs(conf.header_names) do
    local header_value = kong.request.get_header(header_name)
    if header_value then
      token = header_value:match("Bearer%s+(.+)")
      if token then
        kong.log.debug("Found JWT token in header: " .. header_name)
        break
      end
    end
  end
  
  -- Check URI parameters if no token found in headers
  if not token then
    for _, param_name in ipairs(conf.uri_param_names) do
      token = kong.request.get_query_arg(param_name)
      if token then
        kong.log.debug("Found JWT token in URI parameter: " .. param_name)
        break
      end
    end
  end
  
  if not token then
    kong.log.debug("No JWT token found in request")
    return kong.response.exit(401, create_error_response("JWT.NoToken", "No JWT token provided"))
  end
  
  -- Decode JWT payload to get issuer
  local payload, err = decode_jwt_payload(token)
  if not payload then
    kong.log.err("Failed to decode JWT payload: " .. tostring(err))
    return kong.response.exit(401, create_error_response("JWT.InvalidToken", "Invalid JWT token"))
  end
  
  -- Extract realm from issuer
  local realm = extract_realm_from_issuer(payload.iss)
  if not realm then
    kong.log.err("Could not extract realm from issuer: " .. tostring(payload.iss))
    return kong.response.exit(401, create_error_response("JWT.InvalidIssuer", "Invalid JWT issuer"))
  end
  
  kong.log.debug("Extracted realm from JWT: " .. realm)
  
  -- Fetch JWKS for this realm using the configured Keycloak base URL
  local jwks, err = fetch_jwks(payload.iss, conf.keycloak_base_url)
  if not jwks then
    kong.log.err("Failed to fetch JWKS for realm " .. realm .. ": " .. tostring(err))
    return kong.response.exit(401, create_error_response("JWT.ValidationFailed", "Failed to validate JWT token"))
  end
  
  -- Decode JWT header to get kid
  local parts = {}
  for part in token:gmatch("[^%.]+") do
    table.insert(parts, part)
  end
  
  local header_b64 = base64url_to_base64(parts[1])
  local header_json = ngx.decode_base64(header_b64)
  local header = cjson.decode(header_json)
  local kid = header.kid
  
  -- Verify JWT signature
  local verified_payload, err = verify_jwt_with_jwks(token, jwks, kid)
  if not verified_payload then
    kong.log.err("JWT verification failed: " .. tostring(err))
    return kong.response.exit(401, create_error_response("JWT.InvalidSignature", "Invalid JWT signature"))
  end
  
  -- Verify claims
  for _, claim in ipairs(conf.claims_to_verify) do
    if claim == "exp" then
      if not verified_payload.exp or verified_payload.exp <= ngx.time() then
        kong.log.err("JWT token expired")
        return kong.response.exit(401, create_error_response("JWT.TokenExpired", "JWT token expired"))
      end
    end
  end
  
  kong.log.debug("JWT token successfully verified for realm: " .. realm)
  
  -- Store verified payload in Kong context for other plugins
  ngx.ctx.authenticated_jwt_token = { payload = verified_payload }
  ngx.ctx.authenticated_realm = realm
  
  kong.log.debug("Dynamic JWT plugin access phase completed successfully")
end

return DynamicJwtHandler 