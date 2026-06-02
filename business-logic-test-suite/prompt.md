# Input file generation prompts

These three prompts produce the input files that `CLAUDE.md` needs to generate
the business logic test suite. Run them in order — the output of each feeds
the next.

```
Prompt 1: CLAUDE.md → BUSINESS_RULES.md
Prompt 2: BUSINESS_RULES.md → env_map.yaml
Prompt 3: BUSINESS_RULES.md + schema.yaml → seed_manifest.yaml
```

---

## Prompt 1 — Extract `BUSINESS_RULES.md` from service CLAUDE.md

**What to attach:** the service's `CLAUDE.md` file

**Paste this prompt:**

```
I am giving you the CLAUDE.md documentation file for a microservice.

Extract a BUSINESS_RULES.md file from it containing ONLY the following:

1. Every rule that describes a constraint between two or more fields in the
   same request body (cross-field rules). Include: the fields involved, the
   constraint, and what the service must return when it is violated.

2. Every rule that describes a dependency between two entities — where entity A
   must exist before entity B can be created, or where deleting A has a
   specified effect on B (cross-schema rules). Include: which entities are
   involved, the direction of dependency, and the error returned when the
   prerequisite is missing.

3. Every rule that governs how an entity changes state over its lifetime —
   version increments, fields that must be preserved across updates, fields
   that become immutable after creation, and what must happen when the last
   version of an entity is deleted (lifecycle rules).

4. Every rule that describes a call from this service to another service during
   a request — what is called, when, and what must happen in this service if
   the other service fails or returns an error (cross-module rules).

5. The complete error reference table — every HTTP status code the service can
   return, the condition that triggers it, and the error code or message shape.

Exclude everything else. Specifically, do NOT include:

- Database schema details (column types, index definitions, constraint names)
- PostgreSQL sequence naming or implementation details
- Kafka topic names, PubSub configuration, event envelope structure
- OpenTelemetry, Prometheus, or logging configuration
- Environment variable names or default values
- Flyway migration behaviour or tenant schema separation logic
- Retry counts, backoff durations, or connection pool settings
- Any detail that describes HOW the rule is implemented rather than WHAT the
  rule requires

For each rule you extract, write it in this format:

### <Category>: <short title>

**Entities involved:** <list>
**Rule:** <one or two sentences stating the constraint precisely>
**Violation response:** <HTTP status> — <error code or message shape>

Where category is one of: Cross-field, Cross-schema, Lifecycle, Cross-module.

Do not paraphrase rules in a way that loses precision. If the original states
an exact value (e.g. "padding.length must be >= the number of digits in
sequence.start"), preserve that exact condition.
```

---

## Prompt 2 — Generate `env_map.yaml` from `BUSINESS_RULES.md`

**What to attach:** the `BUSINESS_RULES.md` produced by Prompt 1

**Paste this prompt:**

```
I am giving you a BUSINESS_RULES.md file for a microservice.

Scan the entire document for any value written in the form ${VARIABLE_NAME} —
these are environment variable references used in rule descriptions and seed
data (for example: ${IDGEN_BILL_NUMBER_TEMPLATE_CODE}).

Produce an env_map.yaml file with the following structure:

# services/<svc-name>/env_map.yaml
# Maps ${VAR_NAME} tokens to their runtime values.
# Used by seed.py to resolve seed_manifest.yaml entries before making HTTP calls.
VARIABLE_NAME_ONE: "value"
VARIABLE_NAME_TWO: "value"

Rules:
- Include every ${VAR_NAME} token found in the document, even if it appears
  more than once — list each variable exactly once.
- For each variable, infer the most likely runtime value from context. For
  example, ${IDGEN_BILL_NUMBER_TEMPLATE_CODE} most likely has the value
  "BILL-NUMBER" based on the naming convention and surrounding documentation.
- If you cannot infer the value with confidence, set it to "FILL_IN" and add
  an inline comment explaining what it represents.
- If no ${VAR_NAME} tokens exist in the document, output an empty file with
  a comment: "# No environment variable references found in BUSINESS_RULES.md"
- Do not include variables that are not referenced in BUSINESS_RULES.md, even
  if you know the service has other environment variables.
```

---

## Prompt 3 — Generate `seed_manifest.yaml` from `BUSINESS_RULES.md` + `schema.yaml`

**What to attach:** the `BUSINESS_RULES.md` from Prompt 1 AND the service's
`schema.yaml`

**Also tell Claude:**
- The `--base-url` arg name and what service it points to
- The name and `--<dep>-url` arg for each dependency service that the
  cross-module rules reference (e.g. "IDGen is at --idgen-url")

**Paste this prompt:**

```
I am giving you a BUSINESS_RULES.md file and a schema.yaml file for a
microservice.

I also tell you:
- This service's base URL is provided via the CLI arg: --base-url
- [List any dependency services and their CLI args here, e.g.:
    "The IDGen service URL is provided via: --idgen-url"
    "The notification service URL is provided via: --notif-url"
  If there are no dependency services, say "No dependency services."]

Produce a seed_manifest.yaml file that lists every entity that must exist
before the business logic tests can run.

An entity must be seeded if ANY of the following is true:
1. A cross-schema rule in BUSINESS_RULES.md states that entity A must exist
   before entity B can be created — entity A must be seeded.
2. A cross-module rule states that this service expects a specific resource to
   already be configured in a dependency service — that resource must be seeded.
3. The schema.yaml has an endpoint that would return 404 or fail for all test
   requests unless a prerequisite config or resource exists for the tenant.

Use this exact format for each entry:

prerequisites:
  - id: SEED-<NNN>
    description: <one sentence — what this entity is and why it must exist>
    service: <"self" if owned by this service, or the logical name of the
               dependency service (e.g. "idgen", "billing")>
    base_url_arg: <"--base-url" if service is "self", otherwise the dep URL
                   arg name, e.g. "--idgen-url">
    check:
      method: GET
      path: <path to verify the entity exists — use query params not path params
              where possible so the same path works for all tenants>
      params: <key: value map of query params, or omit if none>
      expect_status: 200
    create:
      method: POST
      path: <path to create the entity>
      body:
        <minimal valid request body to create this entity>

Rules:
- Use ${VAR_NAME} tokens for any value that is an environment variable
  reference (e.g. templateCode: "${IDGEN_BILL_NUMBER_TEMPLATE_CODE}").
  These will be resolved at runtime from env_map.yaml.
- Set base_url_arg to "--base-url" for entities that belong to the service
  being tested. Set it to the appropriate "--<dep>-url" arg for entities that
  live in a dependency service.
- The check.path and create.path must be derivable from schema.yaml or from
  the cross-module rules. Do not invent paths.
- The create.body must contain only fields required by the spec. Do not add
  optional fields unless they are required by a business rule.
- If no seeds are required (all test state can be created within individual
  tests), output an empty file with the comment:
  "# No session-scoped prerequisites required for this service."
- Do not seed test-specific ephemeral entities — only long-lived platform
  configuration that must exist before any test can run.
```

---

## Sequence summary

```
Service CLAUDE.md
      │
      ▼ Prompt 1
BUSINESS_RULES.md
      │
      ├──▶ Prompt 2 ──▶ env_map.yaml
      │
      └──▶ Prompt 3 (+ schema.yaml) ──▶ seed_manifest.yaml
```

Once all three files exist alongside `schema.yaml`, the suite is ready to
generate using `CLAUDE.md`.