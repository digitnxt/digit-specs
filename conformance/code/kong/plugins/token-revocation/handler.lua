local redis = require "resty.redis"

local TokenRevocationHandler = {}

-- Runs after dynamic-jwt (1010), because the denylist must be keyed off a *verified* payload; reading
-- claims from an unverified token would let a forged one choose which key to look up. Runs before
-- header-enrichment (800), so a revoked token never reaches enrichment, authorization or upstream.
TokenRevocationHandler.PRIORITY = 1000
TokenRevocationHandler.VERSION = "1.0.0"

-- Only POST may revoke, and the key is always the session. Neither is configurable on purpose: the
-- alternatives are known-wrong rather than merely different. Allowing GET would let a prefetched link
-- log a user out, and keying on jti would leave tokens from the same session working -- including ones
-- the request never saw, which therefore can never be revoked.
local LOGOUT_METHOD = "POST"
local KEY_PREFIX = "revoked"

-- Function to check whether a value is missing or blank
local function is_blank(value)
  return value == nil or value == "" or (type(value) == "string" and value:match("^%s*$") ~= nil)
end

-- Function to build the DIGIT error envelope: a bare array, the same shape the other plugins return
local function exit_with_error(status, code, message, description)
  local err = { code = code, message = message }
  if description then
    err.description = description
  end
  return kong.response.exit(status, { err })
end

-- Function to get the JWT payload verified by the dynamic-jwt plugin.
-- Absent means no verified token on this request, which is dynamic-jwt's call to make, not ours: on a
-- protected route it has already returned 401, and on an unprotected one there is nothing to revoke.
local function get_verified_payload()
  local token = ngx.ctx.authenticated_jwt_token
  if type(token) == "table" and type(token.payload) == "table" then
    return token.payload
  end
  return nil
end

-- Function to extract the realm from the issuer, as a fallback when dynamic-jwt did not publish it
local function realm_from_issuer(issuer)
  if type(issuer) ~= "string" then
    return nil
  end
  return issuer:match("/realms/([^/]+)")
end

-- Function to decide whether this request is a logout.
-- The final path segment must match exactly, never a substring: /audit/v3/logout-events and
-- /config/v3/logout-redirect both contain "logout" and must not log anybody out.
local function is_logout_request(conf)
  if kong.request.get_method():upper() ~= LOGOUT_METHOD then
    return false
  end
  local path = kong.request.get_path()
  if not path then
    return false
  end
  local last = path:match("([^/]+)/?$")
  return last ~= nil and last == conf.logout_path_segment
end

-- Function to build the denylist key for this token.
-- sid identifies the login session and survives refresh, so one key covers every token the session ever
-- mints. A client_credentials token has no session at all, so it falls back to its own id.
local function revocation_key(realm, payload)
  local value, kind = payload.sid, "sid"
  if is_blank(value) then
    value, kind = payload.jti, "jti"
    kong.log.debug("token-revocation: no sid on this token, falling back to jti")
  end
  if is_blank(value) then
    return nil, nil
  end
  return KEY_PREFIX .. ":" .. realm .. ":" .. kind .. ":" .. value, kind
end

-- Function to open a Redis connection with the configured auth and database
local function redis_connect(conf)
  local red = redis:new()
  red:set_timeouts(conf.redis_timeout, conf.redis_timeout, conf.redis_timeout)
  local ok, err = red:connect(conf.redis_host, conf.redis_port)
  if not ok then
    return nil, "connect failed: " .. tostring(err)
  end
  if not is_blank(conf.redis_password) then
    local aok, aerr = red:auth(conf.redis_password)
    if not aok then
      red:close()
      return nil, "auth failed: " .. tostring(aerr)
    end
  end
  if conf.redis_database and conf.redis_database > 0 then
    local sok, serr = red:select(conf.redis_database)
    if not sok then
      red:close()
      return nil, "select failed: " .. tostring(serr)
    end
  end
  return red, nil
end

-- Function to return a healthy connection to the pool. A connection that errored is closed instead,
-- because keeping a broken socket would hand the fault to whoever borrows it next.
local function redis_release(red, healthy)
  if not red then
    return
  end
  if healthy then
    red:set_keepalive(10000, 100)
  else
    red:close()
  end
end

-- Function to record a revocation
local function redis_revoke(conf, key)
  local red, err = redis_connect(conf)
  if not red then
    return false, err
  end
  local ok, serr = red:setex(key, conf.revocation_ttl, "1")
  if not ok then
    redis_release(red, false)
    return false, "setex failed: " .. tostring(serr)
  end
  redis_release(red, true)
  return true, nil
end

-- Function to test whether a token has been revoked
local function redis_is_revoked(conf, key)
  local red, err = redis_connect(conf)
  if not red then
    return nil, err
  end
  local res, eerr = red:exists(key)
  if res == nil then
    redis_release(red, false)
    return nil, "exists failed: " .. tostring(eerr)
  end
  redis_release(red, true)
  return res == 1, nil
end

function TokenRevocationHandler:access(conf)
  if conf.enable_plugin == false then
    return
  end

  local payload = get_verified_payload()
  if not payload then
    return
  end

  local realm = ngx.ctx.authenticated_realm or realm_from_issuer(payload.iss)
  if is_blank(realm) then
    kong.log.warn("token-revocation: could not determine the realm, skipping")
    return
  end

  local key, kind = revocation_key(realm, payload)
  if not key then
    kong.log.warn("token-revocation: token carries neither sid nor jti, nothing to key on")
    return
  end

  -- ---------- revoke branch: only on a logout request ----------
  if is_logout_request(conf) then
    local ok, err = redis_revoke(conf, key)
    if not ok then
      -- Deliberately not subject to fail_open: if the revocation was not recorded, the logout did not
      -- happen, and saying otherwise would be a false promise.
      kong.log.err("token-revocation: could not record the revocation: " .. tostring(err))
      return exit_with_error(503, "Logout.RevocationFailed",
        "Could not record the revocation", tostring(err))
    end

    kong.log.notice("token-revocation: revoked " .. kind .. " for realm " .. realm ..
                    " (ttl " .. tostring(conf.revocation_ttl) .. "s)")

    -- The check below is deliberately skipped, so logging out twice is idempotent rather than a 401.
    if conf.terminate_on_logout then
      return kong.response.exit(204)
    end
    return
  end

  -- ---------- check branch: every request, no path gate ----------
  -- Not path-gated on purpose. metadata-enrichment can skip paths it does not act on; a revocation
  -- check cannot, because a revoked token has to fail everywhere. A path gate here would be a bypass.
  local revoked, err = redis_is_revoked(conf, key)
  if err then
    if conf.fail_open then
      kong.log.err("token-revocation: denylist unreachable, allowing request (fail_open): " .. tostring(err))
      return
    end
    kong.log.err("token-revocation: denylist unreachable, denying request: " .. tostring(err))
    return exit_with_error(503, "Token.RevocationCheckUnavailable",
      "Cannot verify whether this token was revoked", tostring(err))
  end

  if revoked then
    kong.log.notice("token-revocation: rejected a revoked " .. kind .. " on realm " .. realm)
    return exit_with_error(401, "Token.Revoked", "This token has been revoked by logout")
  end
end

return TokenRevocationHandler
