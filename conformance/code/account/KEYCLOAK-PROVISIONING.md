# Keycloak configuration created by Account Service on tenant creation

This document is the complete inventory of the Keycloak configuration that the DIGIT Account Service provisions when a tenant is created via `POST /account/v3/tenants`, and what it removes when a tenant is deleted.

**Keycloak version targeted:** 25.0.1

**At a glance, per tenant:** 1 realm · 15 realm roles · 9 clients · 3 users · 11 client scopes · 22 authentication flows · 11 required actions · 13 components · 1 identity provider · 179 authorization resources · 5 authorization scopes · 2 policies · 310 permissions.

**Where this comes from in the codebase**

| File | Role |
|---|---|
| `src/services/account/src/main/java/com/digit/account/clients/keycloak/KeycloakClient.java` | Issues the Keycloak Admin API calls |
| `src/services/account/src/main/resources/realm_config.json` | The 8,886-line realm template that is imported |
| `src/services/account/src/main/java/com/digit/account/config/AccountProperties.java` | Configurable values — Keycloak base URL, admin credentials, client secrets |

---

## 1. Provisioning sequence

`TenantService.create()` → `KeycloakClient.createRealmWithFullConfig(...)`:

| # | Step | Keycloak API call |
|---|---|---|
| 1 | Get admin token | `POST /realms/master/protocol/openid-connect/token` (client `admin-cli`, password grant) |
| 2 | Render `realm_config.json` template and import the **whole realm** | `POST /admin/realms` |
| 3 | Create the `citizen` identity provider | `POST /admin/realms/{tenant}/identity-provider/instances` |
| 4 | Enable fine-grained permissions on that IdP (creates the `token-exchange.permission.idp.*` permission) | `PUT /admin/realms/{tenant}/identity-provider/instances/citizen/management/permissions` |
| 5 | Create a client policy allowing `auth-server` to token-exchange | `POST /admin/realms/{tenant}/clients/{realm-management}/authz/resource-server/policy/client` |
| 6 | Attach that policy to the IdP token-exchange permission | `PUT .../authz/resource-server/permission/scope/{permissionId}` |

Accepted status codes: realm create → `201` or `409` (idempotent on re-create). Any other failure rolls back the tenant row in the DB.

Deletion (`DELETE /account/v3/tenants/{id}`) removes the entire realm: `DELETE /admin/realms/{tenant}`.

### Template placeholders substituted into the realm JSON

| Placeholder | Source |
|---|---|
| `{{.TenantCode}}` | tenant code (becomes the realm name) |
| `{{.TenantCodeLowerCase}}` | lowercased tenant code (used in `default-roles-<realm>`) |
| `{{.TenantEmail}}` | tenant admin email — becomes the admin **username** |
| `{{.TenantName}}` | tenant display name |
| `{{.TenantPassword}}` | admin password (auto-generated if not supplied) |
| `{{.MobileNumber}}` |  in `TenantService` |
| `{{.AuthBaseUrl}}` / `{{.AuthAdminUrl}}` | `account.keycloak.base-url` |
| `{{.AuthServerClientSecret}}` | `account.keycloak.auth-server-client-secret` |
| `{{.EmployeeIamClientSecret}}` | `account.keycloak.employee-iam-client-secret` |

---

## 2. Realm

One realm per tenant, named exactly the tenant code.

| Setting | Value |
|---|---|
| `enabled` | true |
| `sslRequired` | external |
| `registrationAllowed` | true |
| `loginWithEmailAllowed` | true |
| `duplicateEmailsAllowed` | false |
| `resetPasswordAllowed` | false |
| `editUsernameAllowed` | false |
| `verifyEmail` | false |
| `bruteForceProtected` | false |
| `defaultSignatureAlgorithm` | RS256 |
| `accessTokenLifespan` | 7200 s (2 h) |
| `accessTokenLifespanForImplicitFlow` | 900 s |
| `ssoSessionIdleTimeout` / `ssoSessionMaxLifespan` | 1800 s / 36000 s |
| `offlineSessionIdleTimeout` / `MaxLifespan` | 2592000 s / 5184000 s (max not enforced) |
| `accessCodeLifespan` / `UserAction` / `Login` | 60 / 300 / 1800 s |
| `actionTokenGeneratedByAdmin` / `ByUser` | 43200 / 300 s |
| OTP policy | `totp`, HmacSHA1, 6 digits, 30 s period, not reusable |
| `userManagedAccessAllowed` | false |
| `organizationsEnabled` | false |

**Bound flows:** browser → `Copy of browser` (custom), registration → `registration`, direct grant → `direct grant`, reset credentials → `reset credentials`, client auth → `clients`, docker → `docker auth`, first broker login → `first broker login`.

**Events:** `eventsEnabled=false`, `adminEventsEnabled=false`; listeners registered: `jboss-logging`, `register-citizen-listener` (custom SPI).

---

## 3. Realm roles

Keycloak built-ins:

- `default-roles-<tenantcode-lowercase>` (composite, the realm default role)
- `offline_access`
- `uma_authorization`

DIGIT roles created by the template:

| Role | Description |
|---|---|
| `SUPERUSER` | Superuser |
| `ADMIN` | Admin |
| `COMPLAINTS_ADMIN` | Complaints Admin |
| `RESOLVER` | Resolver |
| `ASSIGNER` | Assigner |
| `EMPLOYEE` | Employee |
| `CITIZEN` | Citizen |
| `USER` | User |
| `document_verifier` | Document Verifier |
| `approver` | Approver |
| `field_inspector` | Field Inspector |
| `counter_employee` | Counter Employee |

Client roles are the standard Keycloak sets for `realm-management` (19 roles incl. `realm-admin`), `account` (8 roles), `broker` (`read-token`), plus `auth-server` → `uma_protection` (created because authorization services are enabled on it).

---

## 4. Clients (9)

| Client ID | Type | Flows | Notes |
|---|---|---|---|
| `account` | public | standard | Keycloak built-in; redirect `/realms/{tenant}/account/*` |
| `account-console` | public | standard | built-in; mapper `audience resolve` |
| `admin-cli` | public | direct access only | built-in |
| `broker` | bearer-only | — | built-in |
| `realm-management` | bearer-only | — | built-in; carries the token-exchange permission created in step 4–6 |
| `security-admin-console` | public | standard | built-in; redirect `/admin/{tenant}/console/*`, web origin `+`, mapper `locale` |
| **`auth-server`** | confidential, **secret set** | standard + direct access + **service account** + **authorization services** | The policy-enforcement point. Redirect `/*`, web origins `/*`. Mappers: Client IP Address, Client ID, Client Host. Holds all 179 resources / 310 permissions. |
| **`employee-iam-client`** | confidential, **secret set** | service account only | Used by the employee service to read realm/users |
| **`sandbox-ui-client`** | public | standard + direct access | Redirects to `https://digit-lts.digit.org/sandbox-ui/{tenant}/employee/user/success` and `.../citizen/success`; web origin `https://digit-lts.digit.org` |

---

## 5. Users (3)

| Username | Realm roles | Client roles | Credentials |
|---|---|---|---|
| `service-account-auth-server` | `default-roles-*` | `auth-server`: `uma_protection` | — (service account) |
| `service-account-employee-iam-client` | `default-roles-*` | `realm-management`: `view-realm`, `view-users` | — (service account) |
| `{{.TenantEmail}}` (tenant admin) | `default-roles-*`, **`SUPERUSER`** | `realm-management`: `realm-admin`, `manage-realm`, `view-realm`, `manage-clients`, `create-client`, `query-clients`, `manage-users`, `view-users`, `query-users` | password = `{{.TenantPassword}}` |

---

## 6. Authorization services on `auth-server`

This is the bulk of the config: `policyEnforcementMode=ENFORCING`, `allowRemoteResourceManagement=true`, `decisionStrategy` per-permission `UNANIMOUS`.

- **Authorization scopes (5):** `post`, `get`, `put`, `patch`, `delete`
- **Resources: 179** — one per DIGIT API endpoint (both the tenant-scoped path and its `/canonical/` twin)
- **Policies: 1** — `keycloak-demo`, a **role policy** (`logic=POSITIVE`, `fetchRoles=false`) granting: `ADMIN`, `SUPERUSER`, `EMPLOYEE`, `CITIZEN`, `USER`, `document_verifier`, `approver`, `field_inspector`, `counter_employee` (all `required=false`, i.e. any one suffices)
- **Scope permissions: 309** — mechanically one per (resource × scope), named `<resource>-<scope>-permission`, each applying the single `keycloak-demo` policy

Every one of the 309 permissions applies the same `keycloak-demo` policy, so the effective access rule is uniform across the API surface: a user holding any one of those nine roles is authorized for all 179 resources. Per-endpoint differentiation is expressed by changing `applyPolicies` on individual permissions.

### Resources by service

| Service prefix | Resources |
|---|---|
| `billing` | 30 |
| `filestore` | 20 |
| `workflow` | 18 |
| `registry` | 18 |
| `employee` | 14 |
| `account` | 14 |
| `boundary` | 12 |
| `otp` | 10 |
| `localization` | 8 |
| `individuals` | 8 |
| `url-shortener` | 6 |
| `idgen` | 6 |
| `template-config` | 4 |
| `pg-service` | 4 |
| `notification` | 4 |
| `mdms-v2` | 3 |

Full listing:

#### `account` (14 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `account-v3` | `/account/v3` | post,get,delete |
| `account-v3-canonical-config` | `/account/v3/canonical/config` | post,get |
| `account-v3-canonical-config-id` | `/account/v3/canonical/config/{id}` | put |
| `account-v3-canonical-tenants` | `/account/v3/canonical/tenants` | post,get |
| `account-v3-canonical-tenants-id` | `/account/v3/canonical/tenants/{id}` | put,delete |
| `account-v3-canonical-tenants-registrations` | `/account/v3/canonical/tenants/registrations` | post |
| `account-v3-canonical-tenants-registrations-resend` | `/account/v3/canonical/tenants/registrations/resend` | post |
| `account-v3-canonical-tenants-registrations-verify` | `/account/v3/canonical/tenants/registrations/verify` | post |
| `account-v3-config` | `/account/v3/config` | post,get |
| `account-v3-config-id` | `/account/v3/config/{id}` | put |
| `account-v3-id` | `/account/v3/{id}` | put |
| `account-v3-signup` | `/account/v3/signup` | post |
| `account-v3-signup-resend` | `/account/v3/signup/resend` | post |
| `account-v3-signup-verify` | `/account/v3/signup/verify` | post |

#### `billing` (30 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `billing-v3-bills` | `/billing/v3/bills` | get |
| `billing-v3-bills-bulk-generate` | `/billing/v3/bills/bulk-generate` | post |
| `billing-v3-bills-cancel` | `/billing/v3/bills/cancel` | post |
| `billing-v3-bills-generate` | `/billing/v3/bills/generate` | post |
| `billing-v3-business-services` | `/billing/v3/business-services` | post,get |
| `billing-v3-business-services-code` | `/billing/v3/business-services/{code}` | get,put,patch,delete |
| `billing-v3-canonical-bills` | `/billing/v3/canonical/bills` | get |
| `billing-v3-canonical-bills-bulk-generate` | `/billing/v3/canonical/bills/bulk-generate` | post |
| `billing-v3-canonical-bills-cancel` | `/billing/v3/canonical/bills/cancel` | post |
| `billing-v3-canonical-bills-generate` | `/billing/v3/canonical/bills/generate` | post |
| `billing-v3-canonical-business-services` | `/billing/v3/canonical/business-services` | post,get |
| `billing-v3-canonical-business-services-code` | `/billing/v3/canonical/business-services/{code}` | get,put,patch,delete |
| `billing-v3-canonical-demands` | `/billing/v3/canonical/demands` | post,get,put |
| `billing-v3-canonical-demands-id` | `/billing/v3/canonical/demands/{id}` | get,patch |
| `billing-v3-canonical-demands-id-cancel` | `/billing/v3/canonical/demands/{id}/cancel` | post |
| `billing-v3-canonical-demands-id-freeze` | `/billing/v3/canonical/demands/{id}/freeze` | post |
| `billing-v3-canonical-payments` | `/billing/v3/canonical/payments` | post,get |
| `billing-v3-canonical-payments-id` | `/billing/v3/canonical/payments/{id}` | get |
| `billing-v3-canonical-payments-validate` | `/billing/v3/canonical/payments/validate` | post |
| `billing-v3-canonical-tax-heads` | `/billing/v3/canonical/tax-heads` | post,get |
| `billing-v3-canonical-tax-heads-code` | `/billing/v3/canonical/tax-heads/{code}` | get,put,patch,delete |
| `billing-v3-demands` | `/billing/v3/demands` | post,get,put |
| `billing-v3-demands-id` | `/billing/v3/demands/{id}` | get,patch |
| `billing-v3-demands-id-cancel` | `/billing/v3/demands/{id}/cancel` | post |
| `billing-v3-demands-id-freeze` | `/billing/v3/demands/{id}/freeze` | post |
| `billing-v3-payments` | `/billing/v3/payments` | post,get |
| `billing-v3-payments-id` | `/billing/v3/payments/{id}` | get |
| `billing-v3-payments-validate` | `/billing/v3/payments/validate` | post |
| `billing-v3-tax-heads` | `/billing/v3/tax-heads` | post,get |
| `billing-v3-tax-heads-code` | `/billing/v3/tax-heads/{code}` | get,put,patch,delete |

#### `boundary` (12 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `boundary-v3-boundaries` | `/boundary/v3/boundaries` | post,get |
| `boundary-v3-boundaries-id` | `/boundary/v3/boundaries/{id}` | put |
| `boundary-v3-canonical-boundaries` | `/boundary/v3/canonical/boundaries` | post,get |
| `boundary-v3-canonical-boundaries-id` | `/boundary/v3/canonical/boundaries/{id}` | put |
| `boundary-v3-canonical-hierarchy` | `/boundary/v3/canonical/hierarchy` | post,get |
| `boundary-v3-canonical-hierarchy-id` | `/boundary/v3/canonical/hierarchy/{id}` | put |
| `boundary-v3-canonical-relationship` | `/boundary/v3/canonical/relationship` | post,get |
| `boundary-v3-canonical-relationship-id` | `/boundary/v3/canonical/relationship/{id}` | put |
| `boundary-v3-hierarchy` | `/boundary/v3/hierarchy` | post,get |
| `boundary-v3-hierarchy-id` | `/boundary/v3/hierarchy/{id}` | put |
| `boundary-v3-relationship` | `/boundary/v3/relationship` | post,get |
| `boundary-v3-relationship-id` | `/boundary/v3/relationship/{id}` | put |

#### `employee` (14 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `employee-v3-canonical-employees` | `/employee/v3/canonical/employees` | post,get |
| `employee-v3-canonical-employees-id-deactivate` | `/employee/v3/canonical/employees/{id}/deactivate` | post |
| `employee-v3-canonical-employees-id` | `/employee/v3/canonical/employees/{id}` | get,put,patch,delete |
| `employee-v3-canonical-employees-id-jurisdictions` | `/employee/v3/canonical/employees/{id}/jurisdictions` | post,get |
| `employee-v3-canonical-employees-id-jurisdictions-id` | `/employee/v3/canonical/employees/{id}/jurisdictions/{id}` | get,put |
| `employee-v3-canonical-employees-id-reactivate` | `/employee/v3/canonical/employees/{id}/reactivate` | post |
| `employee-v3-canonical-employees-onboard` | `/employee/v3/canonical/employees/onboard` | post |
| `employee-v3-employees` | `/employee/v3/employees` | post,get |
| `employee-v3-employees-id-deactivate` | `/employee/v3/employees/{id}/deactivate` | post |
| `employee-v3-employees-id` | `/employee/v3/employees/{id}` | get,put,patch,delete |
| `employee-v3-employees-id-jurisdictions` | `/employee/v3/employees/{id}/jurisdictions` | post,get |
| `employee-v3-employees-id-jurisdictions-id` | `/employee/v3/employees/{id}/jurisdictions/{id}` | get,put |
| `employee-v3-employees-id-reactivate` | `/employee/v3/employees/{id}/reactivate` | post |
| `employee-v3-employees-onboard` | `/employee/v3/employees/onboard` | post |

#### `filestore` (20 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `filestore-v3-canonical-document-categories-code` | `/filestore/v3/canonical/document-categories/{code}` | get,put,delete |
| `filestore-v3-canonical-document-categories` | `/filestore/v3/canonical/document-categories` | post,get |
| `filestore-v3-canonical-files-confirm-upload` | `/filestore/v3/canonical/files/confirm-upload` | post |
| `filestore-v3-canonical-files-download-urls` | `/filestore/v3/canonical/files/download-urls` | get |
| `filestore-v3-canonical-files` | `/filestore/v3/canonical/files` | get |
| `filestore-v3-canonical-files-id` | `/filestore/v3/canonical/files/{id}` | get,delete |
| `filestore-v3-canonical-files-upload` | `/filestore/v3/canonical/files/upload` | post |
| `filestore-v3-canonical-files-upload-url` | `/filestore/v3/canonical/files/upload-url` | post |
| `filestore-v3-document-categories-code` | `/filestore/v3/document-categories/{code}` | get,put,delete |
| `filestore-v3-document-categories` | `/filestore/v3/document-categories` | post,get |
| `filestore-v3-files-confirm-upload` | `/filestore/v3/files/confirm-upload` | post |
| `filestore-v3-files-document-categories-code` | `/filestore/v3/files/document-categories/{code}` | get,put,delete |
| `filestore-v3-files-document-categories` | `/filestore/v3/files/document-categories` | post,get |
| `filestore-v3-files-download-urls` | `/filestore/v3/files/download-urls` | get |
| `filestore-v3-files` | `/filestore/v3/files` | get |
| `filestore-v3-files-id` | `/filestore/v3/files/{id}` | get,delete |
| `filestore-v3-files-metadata` | `/filestore/v3/files/metadata` | get |
| `filestore-v3-files-tag` | `/filestore/v3/files/tag` | get |
| `filestore-v3-files-upload` | `/filestore/v3/files/upload` | post |
| `filestore-v3-files-upload-url` | `/filestore/v3/files/upload-url` | post |

#### `idgen` (6 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `idgen-v3-canonical-generate-bulk` | `/idgen/v3/canonical/generate/bulk` | post |
| `idgen-v3-canonical-generate` | `/idgen/v3/canonical/generate` | post |
| `idgen-v3-canonical-template` | `/idgen/v3/canonical/template` | post,get,put,delete |
| `idgen-v3-generate-bulk` | `/idgen/v3/generate/bulk` | post |
| `idgen-v3-generate` | `/idgen/v3/generate` | post |
| `idgen-v3-template` | `/idgen/v3/template` | post,get,put,delete |

#### `individuals` (8 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `individuals-v3-canonical-configs` | `/individuals/v3/canonical/configs` | post,get |
| `individuals-v3-canonical-individuals-exists` | `/individuals/v3/canonical/individuals/exists` | get |
| `individuals-v3-canonical-individuals-id` | `/individuals/v3/canonical/individuals/{id}` | get,put,delete |
| `individuals-v3-canonical-individuals` | `/individuals/v3/canonical/individuals` | post,get |
| `individuals-v3-configs` | `/individuals/v3/configs` | post,get |
| `individuals-v3-individuals-exists` | `/individuals/v3/individuals/exists` | get |
| `individuals-v3-individuals-id` | `/individuals/v3/individuals/{id}` | get,put,delete |
| `individuals-v3-individuals` | `/individuals/v3/individuals` | post,get |

#### `localization` (8 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `localization-v3-cache` | `/localization/v3/cache` | delete |
| `localization-v3-canonical-cache` | `/localization/v3/canonical/cache` | delete |
| `localization-v3-canonical-messages` | `/localization/v3/canonical/messages` | post,get,put,delete |
| `localization-v3-canonical-messages-missing` | `/localization/v3/canonical/messages/missing` | post |
| `localization-v3-canonical-messages-upsert` | `/localization/v3/canonical/messages/upsert` | put |
| `localization-v3-messages` | `/localization/v3/messages` | post,get,put,delete |
| `localization-v3-messages-missing` | `/localization/v3/messages/missing` | post |
| `localization-v3-messages-upsert` | `/localization/v3/messages/upsert` | put |

#### `mdms-v2` (3 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `mdms-v2-v1-schema` | `/mdms-v2/v1/schema` | post,get |
| `mdms-v2-v2` | `/mdms-v2/v2` | post,get,put |
| `mdms-v2-v3-mdms` | `/mdms-v2/v3/mdms` | get |

#### `notification` (4 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `notification-v3-email-send` | `/notification/v3/email/send` | post |
| `notification-v3-sms-send` | `/notification/v3/sms/send` | post |
| `notification-v3-template` | `/notification/v3/template` | post,get,put,delete |
| `notification-v3-template-preview` | `/notification/v3/template/preview` | post |

#### `otp` (10 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `otp-v3-canonical-config` | `/otp/v3/canonical/config` | post,get,put,delete |
| `otp-v3-canonical-generate` | `/otp/v3/canonical/generate` | post |
| `otp-v3-canonical-invalidate` | `/otp/v3/canonical/invalidate` | post |
| `otp-v3-canonical-resend` | `/otp/v3/canonical/resend` | post |
| `otp-v3-canonical-verify` | `/otp/v3/canonical/verify` | post |
| `otp-v3-config` | `/otp/v3/config/` | post,get,put,delete |
| `otp-v3-generate` | `/otp/v3/generate` | post |
| `otp-v3-invalidate` | `/otp/v3/invalidate` | post |
| `otp-v3-resend` | `/otp/v3/resend` | post |
| `otp-v3-verify` | `/otp/v3/verify` | post |

#### `pg-service` (4 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `pg-service-gateway-v3-search` | `/pg-service/gateway/v3/_search` | get |
| `pg-service-transaction-v3-create` | `/pg-service/transaction/v3/_create` | post |
| `pg-service-transaction-v3-search` | `/pg-service/transaction/v3/_search` | get |
| `pg-service-transaction-v3-update` | `/pg-service/transaction/v3/_update` | put |

#### `registry` (18 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `registry-v3-canonical-code-data-exists` | `/registry/v3/canonical/{code}/data/_exists` | get |
| `registry-v3-canonical-code-data-id` | `/registry/v3/canonical/{code}/data/{id}` | delete |
| `registry-v3-canonical-code-data-registry` | `/registry/v3/canonical/{code}/data/_registry` | get |
| `registry-v3-canonical-code-data` | `/registry/v3/canonical/{code}/data` | post,get,put |
| `registry-v3-canonical-code-data-search` | `/registry/v3/canonical/{code}/data/_search` | post |
| `registry-v3-canonical-code-data-verify` | `/registry/v3/canonical/{code}/data/_verify` | get |
| `registry-v3-canonical-schema-code-isexist` | `/registry/v3/canonical/schema/{code}/_isExist` | post |
| `registry-v3-canonical-schema-code` | `/registry/v3/canonical/schema/{code}` | get,put,delete |
| `registry-v3-canonical-schema` | `/registry/v3/canonical/schema` | post,get |
| `registry-v3-code-data-exists` | `/registry/v3/{code}/data/_exists` | get |
| `registry-v3-code-data-id` | `/registry/v3/{code}/data/{id}` | delete |
| `registry-v3-code-data-registry` | `/registry/v3/{code}/data/_registry` | get |
| `registry-v3-code-data` | `/registry/v3/{code}/data` | post,get,put |
| `registry-v3-code-data-search` | `/registry/v3/{code}/data/_search` | post |
| `registry-v3-code-data-verify` | `/registry/v3/{code}/data/_verify` | get |
| `registry-v3-schema-code-isexist` | `/registry/v3/schema/{code}/_isExist` | post |
| `registry-v3-schema-code` | `/registry/v3/schema/{code}` | get,put,delete |
| `registry-v3-schema` | `/registry/v3/schema` | post,get |

#### `template-config` (4 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `template-config-v3-canonical-config` | `/template-config/v3/canonical/config` | post,get,put,delete |
| `template-config-v3-canonical-render` | `/template-config/v3/canonical/render` | post |
| `template-config-v3-config` | `/template-config/v3/config` | post,get,put,delete |
| `template-config-v3-render` | `/template-config/v3/render` | post |

#### `url-shortener` (6 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `url-shortener-canonical-tenantid-key` | `/url-shortener/canonical/{tenantId}/{key}` | get |
| `url-shortener-code` | `/url-shortener/{code}` | get |
| `url-shortener-v3-canonical-config` | `/url-shortener/v3/canonical/config` | post,get,put,delete |
| `url-shortener-v3-canonical-short-url` | `/url-shortener/v3/canonical/short-url` | post |
| `url-shortener-v3-config` | `/url-shortener/v3/config` | post,get,put,delete |
| `url-shortener-v3-short-url` | `/url-shortener/v3/short-url` | post |

#### `workflow` (18 resources)

| Resource name | URI | Scopes |
|---|---|---|
| `workflow-v3-auto-code-escalate` | `/workflow/v3/auto/{code}/_escalate` | post |
| `workflow-v3-auto-search` | `/workflow/v3/auto/_search` | get |
| `workflow-v3-canonical-system-transition` | `/workflow/v3/canonical/system/transition` | post |
| `workflow-v3-canonical-transition-count` | `/workflow/v3/canonical/transition/count` | get |
| `workflow-v3-canonical-transition` | `/workflow/v3/canonical/transition` | post,get |
| `workflow-v3-process-code-code` | `/workflow/v3/process/code/{code}` | get,put,delete |
| `workflow-v3-process-code-escalation-code` | `/workflow/v3/process/{code}/escalation/{code}` | get,put,delete |
| `workflow-v3-process-code-escalation` | `/workflow/v3/process/{code}/escalation` | post,get |
| `workflow-v3-process-code-state-code-action-code` | `/workflow/v3/process/{code}/state/{code}/action/{code}` | get,put,delete |
| `workflow-v3-process-code-state-code-action` | `/workflow/v3/process/{code}/state/{code}/action` | post,get |
| `workflow-v3-process-code-state-code` | `/workflow/v3/process/{code}/state/{code}` | get,put,delete |
| `workflow-v3-process-code-state` | `/workflow/v3/process/{code}/state` | post,get |
| `workflow-v3-process-definition-code` | `/workflow/v3/process/definition/{code}` | get,put,delete |
| `workflow-v3-process-definition` | `/workflow/v3/process/definition` | post,get |
| `workflow-v3-process` | `/workflow/v3/process` | post,get |
| `workflow-v3-system-transition` | `/workflow/v3/system/transition` | post |
| `workflow-v3-transition-count` | `/workflow/v3/transition/count` | get |
| `workflow-v3-transition` | `/workflow/v3/transition` | post,get |

---

## 7. Client scopes (11)

| Scope | Protocol | In token scope | Mappers |
|---|---|---|---|
| `email` | openid-connect | yes | email, email verified |
| `phone` | openid-connect | yes | phone number, phone number verified |
| `profile` | openid-connect | yes | 14 standard profile mappers (username, given/family/middle name, birthdate, gender, locale, zoneinfo, picture, website, nickname, profile, updated at, full name) |
| `basic` | openid-connect | no | sub, auth_time |
| `roles` | openid-connect | no | realm roles, client roles, audience resolve |
| `web-origins` | openid-connect | no | allowed web origins |
| `acr` | openid-connect | no | acr loa level |
| `address` | openid-connect | yes | address |
| `microprofile-jwt` | openid-connect | yes | upn, groups |
| `offline_access` | openid-connect | — | — |
| `role_list` | saml | — | role list |

- **Default client scopes:** `role_list`, `profile`, `email`, `roles`, `web-origins`, `acr`, `basic`
- **Optional client scopes:** `offline_access`, `address`, `phone`, `microprofile-jwt`
- **Scope mappings:** `offline_access` scope → `offline_access` role; `account` client → `account-console` gets `manage-account`, `view-groups`

---

## 8. Authentication flows (22)

19 Keycloak built-ins, plus 4 custom ones (`builtIn=false`):

| Flow | Type | Executions |
|---|---|---|
| `Copy of browser` | **top-level, bound as the browser flow** | `auth-cookie` (ALTERNATIVE), `auth-spnego` (DISABLED), `identity-provider-redirector` (ALTERNATIVE), `Copy of browser forms` (ALTERNATIVE) |
| `Copy of browser forms` | sub-flow | `auth-username-form` (REQUIRED), `Employee Flow` (CONDITIONAL), `Citizen Flow` (CONDITIONAL) |
| `Employee Flow` | sub-flow | `conditional-user-role` (config `Employee Login`) + `email-authenticator` |
| `Citizen Flow` | sub-flow | `conditional-user-role` (config `Citizen Login`) + `sms-authenticator` |

Net effect: username first, then **email OTP for employees** (users *without* the `CITIZEN` role) and **SMS OTP for citizens** (users *with* the `CITIZEN` role). Both `email-authenticator` and `sms-authenticator` are custom SPI providers that must exist on the Keycloak server.

**Authenticator configs (4):**

| Alias | Config |
|---|---|
| `Citizen Login` | `condUserRole = CITIZEN` |
| `Employee Login` | `condUserRole = CITIZEN`, `negate = true` |
| `create unique user config` | `require.password.update.after.registration = false` |
| `review profile config` | `update.profile.on.first.login = missing` |

---

## 9. Required actions (11)

Enabled: `CONFIGURE_TOTP`, `UPDATE_PASSWORD`, `UPDATE_PROFILE`, `VERIFY_EMAIL`, `VERIFY_PROFILE`, `webauthn-register`, `webauthn-register-passwordless`, `delete_credential`, `update_user_locale`.
Disabled: `TERMS_AND_CONDITIONS`, `delete_account`.
None are set as default actions.

---

## 10. Components

**Key providers (4):** `rsa-generated`, `rsa-enc-generated`, `hmac-generated-hs512`, `aes-generated` — keys are generated per realm at import.

**Client registration policies (8):** Trusted Hosts, Allowed Protocol Mapper Types (×2 — anonymous + authenticated), Allowed Client Scopes (×2), Full Scope Disabled, Consent Required, Max Clients Limit.

**User profile (`declarative-user-profile`)** — 5 attributes, none required, all viewable/editable by admin and user:
`username`, `email`, `firstName`, `lastName`, **`mobileNumber`** (the DIGIT-specific addition).

---

## 11. Identity provider — created after the realm import

| Field | Value |
|---|---|
| alias | `citizen` |
| displayName | Citizen Identity Provider |
| providerId | `oidc` |
| enabled | true |
| clientId / clientSecret | `account.keycloak.citizen-broker-client-id` / `...-secret` (default `citizen-broker`) |
| discoveryEndpoint | `{baseUrl}/realms/CITIZEN/.well-known/openid-configuration` |
| authorizationUrl / tokenUrl / userInfoUrl / jwksUrl / issuer | all under `{baseUrl}/realms/CITIZEN` |
| clientAuthMethod | `client_secret_post` |
| syncMode | `IMPORT` |
| defaultScope | `openid profile email` |
| validateSignature / useJwksUrl | true / true |
| pkceEnabled | false |
| trustEmail / storeToken / linkOnly / authenticateByDefault | false |
| updateProfileFirstLoginMode | `on` |
| firstBrokerLoginFlowAlias | `first broker login` |

All tenant realms federate to a single shared **`CITIZEN`** realm for citizen login.

### Token-exchange permission & policy (steps 4–6)

1. Enabling IdP permissions makes Keycloak auto-create the permission `token-exchange.permission.idp.<id>` on the `realm-management` client's resource server.
2. A **client policy** is created there:
   - name: `auth-server-token-exchange-policy`
   - description: "Policy allowing auth-server to perform token exchange"
   - type: `client`, logic `POSITIVE`, decisionStrategy `UNANIMOUS`
   - clients: `[auth-server]`
3. That policy is **appended** to the existing `policies` of the token-exchange permission (existing entries are preserved).

Result: `auth-server` may exchange a token issued by the `citizen` IdP for a tenant-realm token.

---

## 12. Left empty by the template

The following realm sections are created empty and carry no entries: `groups`, `identityProviderMappers` (the `citizen` IdP is added after import and has no mappers), `supportedLocales`, `localizationTexts`, `smtpServer`, `clientProfiles`, `clientPolicies`.
