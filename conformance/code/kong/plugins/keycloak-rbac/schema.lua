return {
  name = "keycloak-rbac",
  fields = {
    { config = {
        type = "record",
        fields = {
          {
            enable_plugin = {
              type = "boolean",
              default = true,
              description = "Enable or disable the Keycloak RBAC plugin"
            }
          },
          {
            keycloak_base_url = {
              type = "string",
              required = true,
              default = "http://keycloak.keycloak.svc.cluster.local:8080/keycloak",
              description = "Base URL of Keycloak, including any relative path (KC_HTTP_RELATIVE_PATH)"
            }
          },
          {
            client_id = {
              type = "string",
              required = true,
              default = "auth-server",
              description = "Client whose Authorization Services hold the resources and permissions; sent as the UMA audience"
            }
          },
          {
            timeout = {
              type = "number",
              required = true,
              default = 5000,
              between = { 1, 60000 },
              description = "Timeout for the Keycloak authorization call, in milliseconds"
            }
          },
          {
            cache_ttl = {
              type = "number",
              required = true,
              default = 300,
              between = { 0, 86400 },
              description = "Seconds to cache a decision per worker. 0 disables caching and calls Keycloak on every request."
            }
          },
          {
            max_cache_entries = {
              type = "number",
              required = true,
              default = 10000,
              between = { 128, 1000000 },
              description = "Upper bound on cached decisions per worker before the cache is flushed"
            }
          },
          {
            collapse_uuid_in_cache_key = {
              type = "boolean",
              default = true,
              description = "Collapse strict-UUID path segments when building the cache key, so /x/<uuid-a> and /x/<uuid-b> share an entry. Safe because both resolve to the same Keycloak {id} template."
            }
          },
        },
      },
    },
  },
}
