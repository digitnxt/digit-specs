local HeaderEnrichmentHandler = {}

HeaderEnrichmentHandler.PRIORITY = 800  -- Runs after JWT (1010) but before RBAC (700)
HeaderEnrichmentHandler.VERSION = "1.0.0"

-- Function to convert base64url to base64
local function base64url_to_base64(str)
  -- Replace URL-safe characters
  str = str:gsub('-', '+'):gsub('_', '/')
  -- Add padding if needed
  local padding = 4 - (#str % 4)
  if padding ~= 4 then
    str = str .. string.rep('=', padding)
  end
  return str
end

-- Function to extract trace ID from OpenTelemetry traceparent header
-- Traceparent format: 00-<trace_id>-<span_id>-<flags>
local function extract_trace_id_from_traceparent()
  local traceparent = kong.request.get_header("traceparent")
  if traceparent then
    -- Extract trace_id from traceparent (second field)
    local trace_id = traceparent:match("^%w%w%-([%w]+)%-[%w]+%-[%w]+$")
    if trace_id and #trace_id == 32 then
      kong.log.debug("Extracted trace ID from traceparent: " .. trace_id)
      return trace_id
    end
  end
  return nil
end

-- Function to extract trace ID from Kong's tracing context
local function get_opentelemetry_trace_id()
  -- First try to get from Kong's tracing context
  local tracer = kong.tracing
  if tracer then
    local span = tracer.active_span()
    if span then
      local span_context = span:get_context()
      if span_context and span_context.trace_id then
        kong.log.debug("Got trace ID from Kong tracing context: " .. span_context.trace_id)
        return span_context.trace_id
      end
    end
  end
  
  -- Fallback: try to extract from traceparent header
  local trace_id = extract_trace_id_from_traceparent()
  if trace_id then
    return trace_id
  end
  
  kong.log.debug("No OpenTelemetry trace ID found")
  return nil
end

-- Function to generate UUID v4 as fallback
local function generate_uuid()
  local template = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'
  return string.gsub(template, '[xy]', function(c)
    local v = (c == 'x') and math.random(0, 0xf) or math.random(8, 0xb)
    return string.format('%x', v)
  end)
end

-- Function to get correlation ID (preferring OpenTelemetry trace ID)
local function get_correlation_id()
  -- Try to use OpenTelemetry trace ID as correlation ID
  local trace_id = get_opentelemetry_trace_id()
  if trace_id then
    return trace_id
  end
  
  -- Fallback to generating UUID if no trace ID available
  kong.log.debug("No trace ID available, generating fallback correlation ID")
  return generate_uuid()
end

-- Function to get Kong request ID (compatible with Kong 3.9)
local function get_kong_request_id()
  -- Use Nginx's request_id variable which Kong uses internally
  local request_id = ngx.var.request_id
  if request_id and request_id ~= "" then
    return request_id
  end
  
  -- Fallback to using a generated UUID if request_id is not available
  kong.log.debug("No request_id available, generating fallback")
  return generate_uuid()
end

-- Function to get or generate tenant ID
local function get_tenant_id(conf, jwt_payload)
  -- First priority: Extract from JWT payload (common Keycloak patterns)
  if jwt_payload then
    -- Check for explicit tenant/realm claims
    if jwt_payload.tenant then
      return jwt_payload.tenant
    end
    if jwt_payload.realm then
      return jwt_payload.realm
    end
    -- Extract realm from issuer (e.g., "http://keycloak:8080/realms/my-realm")
    if jwt_payload.iss then
      local realm = jwt_payload.iss:match("/realms/([^/]+)")
      if realm then
        return realm
      end
    end
    -- Check for organization or client_id
    if jwt_payload.organization then
      return jwt_payload.organization
    end
    if jwt_payload.azp then  -- authorized party (client ID)
      return jwt_payload.azp
    end
  end
  
  -- Second priority: Try to get tenant from configured header sources
  for _, header_name in ipairs(conf.tenant_header_sources or {}) do
    local tenant = kong.request.get_header(header_name)
    if tenant then
      return tenant
    end
  end
  
  -- Last resort: Fallback to subdomain
  local host = kong.request.get_header("Host")
  if host then
    local subdomain = host:match("([^%.]+)%.")
    if subdomain and subdomain ~= "www" and subdomain ~= "api" then
      return subdomain
    end
  end
  
  return conf.default_tenant or "default"
end

function HeaderEnrichmentHandler:access(conf)
  kong.log.debug("Header enrichment plugin access phase started")
  
  -- Store request start time for response time calculation
  ngx.ctx.request_start_time = ngx.now()
  
  -- Get correlation ID (preferring OpenTelemetry trace ID)
  local correlation_id = get_correlation_id()
  
  -- Get Kong's request ID (consistent with OpenTelemetry plugin)
  local kong_request_id = get_kong_request_id()
  
  -- Store for use in response headers
  ngx.ctx.correlation_id = correlation_id
  ngx.ctx.kong_request_id = kong_request_id
  
  -- We'll determine tenant_id after extracting JWT payload
  local jwt_payload = nil
  local tenant_id = nil
  
  -- JWT Header Enrichment
  if conf.enable_jwt_headers then
    -- Look for the JWT token in different possible locations first
    local jwt_token = ngx.ctx.authenticated_jwt_token or ngx.ctx.jwt_token or ngx.ctx.jwt
    
    if jwt_token then
      kong.log.debug("Found JWT token object of type: " .. type(jwt_token))
      
      if type(jwt_token) == "table" and jwt_token.payload then
        kong.log.debug("Found JWT payload in Kong context!")
        jwt_payload = jwt_token.payload
        
        for key, value in pairs(jwt_payload) do
          local value_type = type(value)
          if value_type == "string" or value_type == "number" or value_type == "boolean" then
            kong.service.request.set_header("X-JWT-" .. key, tostring(value))
            kong.log.debug("Added header X-JWT-" .. key .. ": " .. tostring(value))
          elseif value_type == "table" and key == "realm_access" and value.roles then
            kong.service.request.set_header("X-JWT-realm-roles", table.concat(value.roles, ","))
            kong.log.debug("Added header X-JWT-realm-roles: " .. table.concat(value.roles, ","))
          end
        end
        -- Don't return here, we need to add API headers too
      end
    else
      kong.log.debug("No JWT token found in Kong context, trying manual decoding")
      
      -- Fallback: manually decode the JWT token
      local auth_header = kong.request.get_header("Authorization")
      if auth_header then
        kong.log.debug("Found Authorization header")
        
        local token = auth_header:match("Bearer%s+(.+)")
        if token then
          kong.log.debug("Extracted JWT token from header")
          
          -- Parse JWT manually
          local header_b64, payload_b64, signature = token:match("([^%.]+)%.([^%.]+)%.([^%.]+)")
          if payload_b64 then
            kong.log.debug("Found JWT parts, attempting to decode payload")
            
            local json = require "cjson"
            
            -- Convert base64url to regular base64
            local payload_b64_regular = base64url_to_base64(payload_b64)
            
            local ok, payload_json = pcall(function()
              return ngx.decode_base64(payload_b64_regular)
            end)
            
            if ok and payload_json then
              kong.log.debug("Successfully decoded base64 payload")
              
              local ok2, payload = pcall(json.decode, payload_json)
              if ok2 and payload then
                kong.log.debug("Successfully decoded JWT payload manually!")
                jwt_payload = payload
                
                for key, value in pairs(payload) do
                  local value_type = type(value)
                  if value_type == "string" or value_type == "number" or value_type == "boolean" then
                    kong.service.request.set_header("X-JWT-" .. key, tostring(value))
                    kong.log.debug("Added header X-JWT-" .. key .. ": " .. tostring(value))
                  elseif value_type == "table" and key == "realm_access" and value.roles then
                    kong.service.request.set_header("X-JWT-realm-roles", table.concat(value.roles, ","))
                    kong.log.debug("Added header X-JWT-realm-roles: " .. table.concat(value.roles, ","))
                  end
                end
              else
                kong.log.debug("Failed to parse JSON payload: " .. tostring(payload_json))
              end
            else
              kong.log.debug("Failed to decode base64 payload: " .. tostring(payload_b64_regular))
            end
          else
            kong.log.debug("Failed to extract JWT parts from token")
          end
        else
          kong.log.debug("Failed to extract Bearer token from Authorization header")
        end
      else
        kong.log.debug("No Authorization header found")
      end
    end
  end
  
  -- Derive the request context unconditionally: enable_api_headers governs whether the X-* headers are
  -- emitted, not whether the context exists. The metadata-enrichment plugin reads these ngx.ctx values
  -- to populate RequestMetadata, which the 3.0 contract defines as the replacement for those same
  -- headers - so turning the headers off must not take the context with them.
  tenant_id = get_tenant_id(conf, jwt_payload)

  -- Store tenant_id for response headers
  ngx.ctx.tenant_id = tenant_id

  -- Extract user ID from JWT payload (sub field)
  local user_id = nil
  if jwt_payload and jwt_payload.sub then
    user_id = jwt_payload.sub
    ngx.ctx.user_id = user_id
    kong.log.debug("Extracted user ID from JWT: " .. user_id)
  else
    kong.log.debug("No user ID (sub) found in JWT payload")
  end

  -- Add standardized API headers (after JWT processing to get tenant from JWT)
  if conf.enable_api_headers then
    -- Use STANDARD header names following OpenTelemetry and Kong conventions:
    -- X-Kong-Request-Id: Kong's standard request ID header (used by Kong internally)
    -- traceparent: W3C standard for distributed tracing (OpenTelemetry format)
    -- X-Tenant-ID: Business-specific tenant identifier
    -- X-User-ID: User identifier from JWT sub claim
    kong.service.request.set_header("X-Kong-Request-Id", kong_request_id)     -- Kong's standard request ID header
    kong.service.request.set_header("X-Tenant-ID", tenant_id)                 -- Keep business-specific header
    if user_id then
      kong.service.request.set_header("X-User-ID", user_id)                   -- User identifier from JWT
    end

    -- Gateway request timestamp in epoch milliseconds for upstream services
    local request_timestamp = string.format("%d", ngx.now() * 1000)
    kong.service.request.set_header("X-Time-Stamp", request_timestamp)        -- Trusted server-side request time (epoch ms)
    
    -- Note: traceparent header is handled by OpenTelemetry plugin automatically
    -- We store correlation_id for response headers but don't duplicate traceparent upstream
    
    kong.log.debug("Added standard headers - Tenant: " .. tenant_id .. ", Kong-Request-Id: " .. kong_request_id .. ", User-ID: " .. (user_id or "none") .. ", Trace-ID: " .. correlation_id)
  end
  
  kong.log.debug("Header enrichment plugin access phase completed")
end

function HeaderEnrichmentHandler:header_filter(conf)
  -- Only add response headers if API headers are enabled
  if not conf.enable_api_headers then
    return
  end
  
  kong.log.debug("Header enrichment plugin header_filter phase started")
  
  -- Calculate response time
  local response_time = "0"
  if ngx.ctx.request_start_time then
    local duration = (ngx.now() - ngx.ctx.request_start_time) * 1000  -- Convert to milliseconds
    response_time = string.format("%.2f", duration)
  end
  
  -- Generate timestamp in epoch milliseconds
  local timestamp = string.format("%d", ngx.now() * 1000)
  
  -- LEVERAGE standard Kong and OpenTelemetry headers:
  -- - X-Kong-Request-Id: Kong's native request ID (used throughout Kong ecosystem)  
  -- - X-Kong-Response-Latency: Kong automatically adds this
  -- - traceparent: OpenTelemetry W3C standard trace context header
  -- - X-RateLimit-*: Rate limiting plugin headers
  
  -- Add response headers using STANDARD naming conventions
  kong.response.set_header("X-Response-Time", response_time .. "ms")          -- Custom response time
  kong.response.set_header("X-Response-Timestamp", timestamp)                 -- Custom timestamp
  kong.response.set_header("X-Kong-Request-Id", ngx.ctx.kong_request_id or "unknown")  -- Kong standard
  kong.response.set_header("X-Tenant-ID", ngx.ctx.tenant_id or "default")     -- Business header
  if ngx.ctx.user_id then
    kong.response.set_header("X-User-ID", ngx.ctx.user_id)                    -- User identifier
  end
  
  -- Don't add X-Correlation-ID in response since OpenTelemetry provides traceparent
  -- The correlation_id (trace ID) is available via traceparent header format: 00-<trace_id>-<span_id>-<flags>
  
  -- Rate limiting headers are already provided by rate-limiting plugin:
  -- X-RateLimit-Limit-Minute, X-RateLimit-Remaining-Minute, etc.
  -- Kong proxy latency headers are automatically added: X-Kong-Proxy-Latency, X-Kong-Upstream-Latency
  
  kong.log.debug("Added standard response headers - Response time: " .. response_time .. "ms, Timestamp: " .. timestamp)
  kong.log.debug("Using OpenTelemetry trace ID as correlation: " .. (ngx.ctx.correlation_id or "none"))
  kong.log.debug("Using Kong standard request ID: " .. (ngx.ctx.kong_request_id or "none"))
  kong.log.debug("Header enrichment plugin header_filter phase completed")
end

return HeaderEnrichmentHandler 