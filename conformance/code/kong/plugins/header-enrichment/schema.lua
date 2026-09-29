local typedefs = require "kong.db.schema.typedefs"

return {
  name = "header-enrichment",
  fields = {
    { config = {
        type = "record",
        fields = {
          {
            enable_jwt_headers = {
              type = "boolean",
              default = true,
              description = "Enable JWT claim extraction and header enrichment"
            }
          },
          {
            enable_api_headers = {
              type = "boolean", 
              default = true,
              description = "Enable standardized API headers (request/response tracking)"
            }
          },
          {
            default_tenant = {
              type = "string",
              default = "default",
              description = "Default tenant ID when none can be determined"
            }
          },
          {
            tenant_header_sources = {
              type = "array",
              elements = { type = "string" },
              default = { "X-JWT-tenant", "X-JWT-organization", "X-Tenant-ID" },
              description = "Header names to check for tenant ID (in order of preference)"
            }
          }
        },
      },
    },
  },
} 