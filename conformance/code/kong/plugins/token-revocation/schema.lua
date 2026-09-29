local typedefs = require "kong.db.schema.typedefs"

return {
  name = "token-revocation",
  fields = {
    { config = {
        type = "record",
        fields = {
          {
            enable_plugin = {
              type = "boolean",
              default = true,
              description = "Enable or disable token revocation"
            }
          },
          {
            logout_path_segment = {
              type = "string",
              default = "logout",
              description = "Final path segment that marks a request as a logout. Matched on the last " ..
                            "segment only, never as a substring: /audit/v3/logout-events must not log a " ..
                            "user out for reading a page. Only POST is ever accepted - revocation " ..
                            "changes state, and browsers prefetch links."
            }
          },
          {
            terminate_on_logout = {
              type = "boolean",
              default = true,
              description = "Answer the logout request at the gateway with 204. Set false to revoke and " ..
                            "then forward upstream, when a service has its own logout handling."
            }
          },
          {
            revocation_ttl = {
              type = "integer",
              default = 43200,
              description = "How long the gateway remembers a logout, in seconds. Must outlast the " ..
                            "session AND the last token it could mint: ssoSessionMaxLifespan (36000) + " ..
                            "accessTokenLifespan (7200). A shorter value lets a client that keeps " ..
                            "refreshing outlive the key and be accepted again, so keep this in step " ..
                            "with the realm's settings."
            }
          },
          {
            fail_open = {
              type = "boolean",
              default = false,
              description = "What an unreachable Redis means. false: deny (503), so a revoked token can " ..
                            "never slip through, at the cost of making Redis tier-0 for all " ..
                            "authenticated traffic. true: allow, which silently re-enables every " ..
                            "revoked token for the remainder of its life - only sane with alerting."
            }
          },
          {
            redis_host = {
              type = "string",
              default = "redis.backbone.svc.cluster.local",
              description = "Redis host holding the denylist. Verified reachable from the Kong pod; " ..
                            "note Redis lives in the backbone namespace, not egov. A wrong host here " ..
                            "means every authenticated request fails the check, and with fail_open " ..
                            "false that is a gateway-wide outage."
            }
          },
          {
            redis_port = {
              type = "integer",
              default = 6379,
              description = "Redis port"
            }
          },
          {
            redis_password = {
              type = "string",
              required = false,
              -- No default: Kong rejects an empty-string default on a string field ("length must be at
              -- least 1"). Left unset it is nil, and the handler skips AUTH.
              description = "Redis password. Omit entirely when Redis needs no AUTH."
            }
          },
          {
            redis_database = {
              type = "integer",
              default = 0,
              description = "Redis database index"
            }
          },
          {
            redis_timeout = {
              type = "integer",
              default = 1000,
              description = "Redis connect/read/write timeout in ms. Kept short because this sits on " ..
                            "every authenticated request."
            }
          },
        },
      },
    },
  },
}
