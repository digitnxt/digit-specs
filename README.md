# DIGIT 3.0 OpenAPI Specifications

Centralized repository of OpenAPI 3.0.3 contracts for the [DIGIT](https://digit.org) platform by eGovernments Foundation.

## Repository Structure

```
v3.0.0/
├── common.yaml            # Shared schemas, headers & security definitions
├── account.yaml           # Account onboarding & configuration
├── individual.yaml        # Citizen/individual registry with PII encryption
├── otp.yaml               # OTP generation, verification & management
├── idgen.yaml             # Template-based unique ID generation
├── mdms.yaml              # Master Data Management
├── boundary.yaml          # Hierarchical geographic/administrative boundaries
├── billing-payment.yaml   # Billing, demands, bills & payment processing
├── workflow.yaml          # Business process workflows & state management
├── notification.yaml      # Multi-channel notification templates & delivery
├── localization.yaml      # Multi-language message management
└── filestore.yaml         # File upload/download via signed URLs
```

## Services

| Service | Description |
|---------|-------------|
| **Common** | Shared headers (`X-Tenant-ID`, `X-Request-ID`, `X-Correlation-ID`), security schemes (JWT Bearer), and reusable schemas (`AuditDetail`, `Address`, `Error`, `Document`) |
| **Account** | Account lifecycle management, global configuration settings, authentication methods, feature toggles, and login policies |
| **Individual** | Individual/citizen registry with HashiCorp Vault-based PII encryption, bulk operations, soft deletion, and optimistic locking |
| **OTP** | OTP generation and verification across phone, email, and push channels with hashed storage, rate limiting, and configurable length/charset |
| **ID Generation** | Human-readable ID generation from user-defined templates with formatted dates, scoped sequences, random segments, and Postgres-backed concurrency safety |
| **MDMS** | Schema definition and master data CRUD with tenant-scoped data management and validation |
| **Boundary** | N-level hierarchical boundary management with relationship tracking and shapefile import support |
| **Billing & Payment** | Business service management, tax head configuration, demand accrual, bill lifecycle, and payment collection |
| **Workflow** | Process and state management with configurable transitions, escalation rules, auto-escalation triggers, and audit trails |
| **Notification** | Template management with dynamic placeholder rendering and multi-channel (email/SMS) delivery |
| **Localization** | Multi-language message management with module-based grouping and locale-specific retrieval |
| **Filestore** | Signed URL generation for uploads/downloads, upload confirmation, and document type metadata management |

## Usage

These specs can be used to:

- **Generate client SDKs** using tools like [OpenAPI Generator](https://openapi-generator.tech/)
- **Generate server stubs** for service implementations
- **Import into API platforms** like Swagger UI, Postman, or Stoplight
- **Validate requests/responses** in integration tests
- **Generate documentation** for API consumers

## License

MIT
