local typedefs = require "kong.db.schema.typedefs"

return {
  name = "metadata-enrichment",
  fields = {
    { config = {
        type = "record",
        fields = {
          {
            canonical_path_segments = {
              type = "array",
              elements = { type = "string" },
              default = { "/v3/canonical/" },
              description = "Path fragments that identify a canonical (envelope-style) route. " ..
                            "A request whose path contains none of these is passed through without " ..
                            "its body being read. Must match the services' CANONICAL_API_PREFIX: " ..
                            "a service that overrides that env var needs its fragment added here."
            }
          },
          {
            metadata_keys = {
              type = "array",
              elements = { type = "string" },
              default = { "RequestMetadata", "requestMetadata" },
              description = "Body keys that may carry the metadata envelope, in precedence order. " ..
                            "The first one present wins, matching what the services read."
            }
          },
          {
            max_body_bytes = {
              type = "integer",
              default = 1048576,
              description = "Largest request body that will be read to enrich the envelope, passed " ..
                            "to kong.request.get_raw_body(). Bodies above client_body_buffer_size " ..
                            "(8k on the proxy listener by default) are spooled to a temp file by " ..
                            "nginx; this is the ceiling on reading one back. A body above it is " ..
                            "forwarded unenriched, so set it above the largest legitimate canonical " ..
                            "payload - bulk creates that take a list are the ones to size for."
            }
          },
          {
            enrich_user_info = {
              type = "boolean",
              default = true,
              description = "Populate RequestMetadata.userInfo from the verified token claims."
            }
          },
          {
            enrich_tenant_id = {
              type = "boolean",
              default = true,
              description = "Overwrite RequestMetadata.tenantId with the value that became " ..
                            "X-Tenant-ID, so the schema a request is routed to and the tenantId it " ..
                            "persists cannot disagree."
            }
          },
          {
            enrich_timestamp = {
              type = "boolean",
              default = true,
              description = "Overwrite RequestMetadata.ts with the gateway's request time. " ..
                            "Required by the RequestMetadata contract and server-side trusted."
            }
          },
          {
            enrich_tracing_ids = {
              type = "boolean",
              default = true,
              description = "Fill RequestMetadata.requestId and correlationId when absent. Never " ..
                            "overwritten: correlationId propagates unchanged, and requestId is a " ..
                            "caller-owned value some services persist."
            }
          },
        },
      },
    },
  },
}
