local typedefs = require "kong.db.schema.typedefs"

return {
  name = "dynamic-jwt",
  fields = {
    { config = {
        type = "record",
        fields = {
          {
            keycloak_base_url = {
              type = "string",
              required = true,
              default = "http://keycloak.keycloak.svc.cluster.local:8080/keycloak",
              description = "Base URL of Keycloak server"
            }
          },
          {
            cache_ttl = {
              type = "number",
              default = 3600,
              description = "Cache TTL for public keys in seconds"
            }
          },
          {
            claims_to_verify = {
              type = "array",
              elements = { type = "string" },
              default = { "exp" },
              description = "List of claims to verify"
            }
          },
          {
            header_names = {
              type = "array",
              elements = { type = "string" },
              default = { "authorization" },
              description = "Header names to look for JWT token"
            }
          },
          {
            uri_param_names = {
              type = "array",
              elements = { type = "string" },
              default = { "jwt" },
              description = "URI parameter names to look for JWT token"
            }
          }
        },
      },
    },
  },
} 