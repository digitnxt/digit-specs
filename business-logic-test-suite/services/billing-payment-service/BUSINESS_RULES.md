# Business Rules — Billing Service

## Cross-Field Rules

### BR-CF-001: Effective date range must be ordered

**Entities involved:** BusinessServiceCreate (`effectiveFrom`, `effectiveTo`)  
**Rule:** When both `effectiveFrom` and `effectiveTo` are provided, `effectiveFrom` must be strictly less than `effectiveTo` (`gtfield=EffectiveFrom` binding tag).  
**Violation response:** 400 — `INVALID_REQUEST`

---

### BR-CF-002: Minimum payable requires partial payment enabled

**Entities involved:** PaymentDetailCreate, BusinessService (`partialPaymentAllowed`, `minPayableAmount`)  
**Rule:** `minPayableAmount` is only meaningful when `partialPaymentAllowed = true`. When `partialPaymentAllowed = false` the consumer must pay the full bill amount, making any `minPayableAmount` threshold redundant. When `partialPaymentAllowed = true` and `minPayableAmount` is set, payment amount must be ≥ `minPayableAmount`.  
**Violation response:** 400 — `INVALID_PAYMENTDETAIL`

---

### BR-CF-003: Demand period must not overlap backward

**Entities involved:** DemandCreate (`periodFrom`, `periodTo`)  
**Rule:** `periodTo` must be greater than or equal to `periodFrom`. Equal values are allowed (zero-duration demand). Only `periodTo < periodFrom` is rejected.  
**Violation response:** 400 — `INVALID_PERIOD` (returned inside `BulkResponse.Failures`)

---

### BR-CF-004: Over-collection on demand is guarded

**Entities involved:** Demand (`totalCollectedAmount`, `totalAmount`), LineItem (`collectedAmount`, `amount`)  
**Rule:** When applying a payment, if the delta applied to a demand would cause `totalCollectedAmount > totalAmount`, the entire payment transaction is aborted. The same check applies per line item. The error is a plain error string, not a structured `*models.Error`.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR` (error string contains "OVER_COLLECTION_DETECTED")

---

### BR-CF-005: Line item amounts are individually range-checked

**Entities involved:** LineItemCreate (`amount`, `collectedAmount`)  
**Rule:** `amount` must be in [−1,000,000,000, 1,000,000,000]. For positive amounts, `collectedAmount` must be in [0, amount]. For negative amounts (rebates), `collectedAmount` must be 0 or equal to `amount`. The demand `totalAmount` is derived by summing line items — it is not user-supplied and cannot be validated independently.  
**Violation response:** 400 — `INVALID_AMOUNT` / `INVALID_COLLECTION` (returned inside `BulkResponse.Failures`)

---

### BR-CF-006: Payer array maximum ten entries

**Entities involved:** DemandCreate (`payer`)  
**Rule:** A demand may include 0–10 payer records. Each entry must be 2–64 characters. Supplying more than 10 entries is rejected by Gin binding.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-007: Payment mode determines payment status at creation

**Entities involved:** PaymentCreate (`paymentMode`), Payment (`paymentStatus`, `instrumentStatus`)  
**Rule:** Initial `paymentStatus` and `instrumentStatus` are determined by payment mode: online modes (`ONLINE`, `UPI`, `CARD`, `NETBANKING`, `WALLET`, `ONLINE_NEFT`, `ONLINE_RTGS`) start as `DEPOSITED` / `REMITTED`; all others start as `NEW` / `APPROVED`. There is no runtime guard preventing a bill's `amountPaid` from exceeding `totalAmount` — advance payment checks have been removed.  
**Violation response:** N/A (status assignment is automatic)

---

### BR-CF-008: Payment instrument validated by mode

**Entities involved:** PaymentCreate (`paymentMode`, `instrumentNumber`, `instrumentDate`, `transactionNumber`)  
**Rule:** Instrument requirements vary by payment mode:
- `CASH`: no instrument validation required
- `CHEQUE`, `DD`: require `instrumentNumber` and `instrumentDate`; `instrumentDate` must not be in the future and must not be more than `MAX_INSTRUMENT_DATE_AGE_DAYS` (default 90) days before the receipt date
- `OFFLINE_NEFT`, `OFFLINE_RTGS`, `POSTAL_ORDER`: require `instrumentNumber` and `instrumentDate`; only future-date is rejected, no age limit
- `ONLINE`, `UPI`, `CARD`, `NETBANKING`, `WALLET`, `ONLINE_NEFT`, `ONLINE_RTGS`: require `transactionNumber` and `instrumentNumber`

**Violation response:** 400 — e.g. `INVALID_INST_NUMBER`, `INVALID_INST_DATE`, `INVALID_TXN_NUMBER`

---

### BR-CF-009: CHEQUE/DD instrument date age constraint

**Entities involved:** PaymentCreate (`paymentMode`, `instrumentDate`, `transactionDate`)  
**Rule:** For `CHEQUE` and `DD` only: `instrumentDate` must not be more than `MAX_INSTRUMENT_DATE_AGE_DAYS` (default 90) days before the receipt date (uses `transactionDate` if supplied, otherwise `time.Now()`). This age constraint does NOT apply to OFFLINE_NEFT, OFFLINE_RTGS, or POSTAL_ORDER.  
**Violation response:** 400 — `CHEQUE_DD_DATE_EXCEEDS_RECEIPT` / `CHEQUE_DD_DATE_EXCEEDS_MANUAL_RECEIPT`

---

### BR-CF-010: Payment amount must be non-negative and non-fractional

**Entities involved:** PaymentDetailCreate (`totalAmountPaid`)  
**Rule:** The amount paid per bill must be ≥ 0. Fractional amounts (non-integer values) are rejected. Zero payment is only allowed when the bill `totalAmount` is also zero. There is no validation that the root `Payment.totalAmountPaid` equals `Σ(paymentDetail.totalAmountPaid)`.  
**Violation response:** 400 — `INVALID_PAYMENTDETAIL`

---

### BR-CF-011: BusinessService code must match pattern

**Entities involved:** BusinessServiceCreate (`code`)  
**Rule:** `code` must match `^[A-Z][A-Z0-9_]{1,31}$` — starts with an uppercase letter, followed by 1–31 uppercase letters, digits, or underscores. Hyphens, lowercase letters, and spaces are not allowed.  
**Violation response:** 400 — `INVALID_REQUEST`

---

### BR-CF-012: TaxHead code must match pattern

**Entities involved:** TaxHeadCreate (`code`)  
**Rule:** `code` must match `^[A-Z][A-Z0-9_]{1,63}$` — starts with an uppercase letter, followed by 1–63 uppercase letters, digits, or underscores. Hyphens, lowercase letters, and spaces are not allowed.  
**Violation response:** 400 — `INVALID_REQUEST`

---

## Cross-Schema Rules

### BR-CS-001: TaxHead requires active BusinessService

**Entities involved:** TaxHead, BusinessService  
**Rule:** A `TaxHead` cannot be created unless its `businessServiceCode` references an existing, active `BusinessService`. Deleting a `BusinessService` is restricted if any `TaxHead` still references it.  
**Violation response:** 404 — `NOT_FOUND` (on create); 422 — `UNPROCESSABLE_ENTITY` (on delete with dependents)

---

### BR-CS-002: TaxHead order number unique per service

**Entities involved:** TaxHead (`orderNumber`, `businessServiceCode`)  
**Rule:** No two tax heads within the same `(tenantId, businessServiceCode)` may share an `orderNumber`. This order governs apportionment priority (lower order = paid first).  
**Violation response:** 409 — `CONFLICT`

---

### BR-CS-003: LineItem tax head belongs to demand's service

**Entities involved:** LineItemCreate (`taxHeadCode`), DemandCreate (`businessServiceCode`), TaxHead  
**Rule:** Each line item in a demand must reference a `TaxHead.code` that belongs to the same `businessServiceCode` as the demand. A tax head from a different service is rejected with error code `INVALID_TAX_HEAD`.  
**Violation response:** 400 — `INVALID_TAX_HEAD` (returned inside `BulkResponse.Failures`)

---

### BR-CS-004: No overlapping demand periods for same consumer and service

**Entities involved:** Demand (`periodFrom`, `periodTo`, `consumerCode`, `businessServiceCode`)  
**Rule:** Two demands for the same `(tenantId, businessServiceCode, consumerCode)` cannot have overlapping `(periodFrom, periodTo)` ranges. The database enforces this with a GiST exclusion constraint.  
**Violation response:** 400 — `DEMAND_CONFLICT` (returned inside `BulkResponse.Failures`)

---

### BR-CS-005: Only one active bill per consumer service

**Entities involved:** Bill (`status`, `consumerCode`, `businessServiceCode`)  
**Rule:** At most one bill with `status = ACTIVE` may exist for a given `(tenantId, businessServiceCode, consumerCode)` at any time. Generating a new bill while an unexpired active bill exists returns the existing bill (idempotent).  
**Violation response:** N/A (existing bill returned; no error)

---

### BR-CS-006: Only ACTIVE bills can receive payments

**Entities involved:** Bill (`status`), PaymentCreate  
**Rule:** Payment can only be applied to a bill with `status = ACTIVE`. Bills with any other status — including `PARTIALLY_PAID`, `CANCELLED`, `EXPIRED`, `PAID`, or `PAYMENT_CANCELLED` — are rejected with error code `BILL_NOT_ACTIVE`.  
**Violation response:** 422 — `BILL_NOT_ACTIVE`

---

### BR-CS-007: Duplicate payment on a bill is prevented

**Entities involved:** Payment, PaymentDetail (`billId`)  
**Rule:** A bill that already has an active payment (instrument status `APPROVED`, `APPROVAL_PENDING`, or `REMITTED`) cannot be included in a new payment. This is checked by querying existing payment instrument statuses, not by a DB unique constraint.  
**Violation response:** 422 — `BILL_ALREADY_PAID`

---

### BR-CS-008: Demand requires active BusinessService

**Entities involved:** Demand, BusinessService  
**Rule:** A demand cannot be created if its `businessServiceCode` references a non-existent or inactive (`isActive = false`) `BusinessService`.  
**Violation response:** 400 — `UNKNOWN_BUSINESS_SERVICE` (returned inside `BulkResponse.Failures`)

---

### BR-CS-009: Bill generation requires qualifying demands

**Entities involved:** Bill, Demand (`status`)  
**Rule:** Bill generation requires at least one demand for the consumer with status `ACTIVE`, `FROZEN`, or `PARTIALLY_PAID`. If no qualifying demands exist, bill generation is rejected with error code `NO_ELIGIBLE_DEMANDS`.  
**Violation response:** 422 — `NO_ELIGIBLE_DEMANDS`

---

## Lifecycle Rules

### BR-LC-001: Demand cancellation only from DRAFT or ACTIVE

**Entities involved:** Demand (`status`)  
**Rule:** `POST /v3/demands/:id/cancel` is only permitted when the demand's current status is `DRAFT` or `ACTIVE`. Demands in any other status cannot be cancelled.  
**Violation response:** 422 — `CANCEL_FAILED`

---

### BR-LC-002: Only DRAFT and ACTIVE demands are editable

**Entities involved:** Demand (`status`)  
**Rule:** Demand update (`PUT /v3/demands`) and patch (`PATCH /v3/demands/:id`) are only permitted when the demand's current status is `DRAFT` or `ACTIVE`. Demands in `FROZEN`, `PARTIALLY_PAID`, `PAID`, `ROLL_FORWARDED`, or `CANCELLED` states cannot be modified.  
**Violation response:** 400 — `INVALID_STATUS_TRANSITION` (returned as `*models.Error`)

---

### BR-LC-003: Bill generation freezes ACTIVE demands

**Entities involved:** Bill, Demand  
**Rule:** When a bill is generated, all demands with status `ACTIVE` that are included in the bill are transitioned to `FROZEN` via `BulkFreezeDemands`. Only `ACTIVE` demands are frozen; already-`FROZEN` or `PARTIALLY_PAID` demands are included in the bill but not re-frozen.  
**Violation response:** N/A (side effect of bill generation)

---

### BR-LC-004: Bill expiry uses demand-level over business-service-level

**Entities involved:** Bill, Demand (`billExpiryDays`), BusinessService (`billExpiryDays`)  
**Rule:** Expiry is determined by priority: if the demand has `billExpiryDays` set, it is used; otherwise the business service's `billExpiryDays` is used; if neither is set, the bill has no expiry. A `billExpiryDays = 0` on either entity means no expiry.  
**Violation response:** N/A (expiry is a computed field)

---

### BR-LC-005: Arrear roll-forward creates new demand and marks source

**Entities involved:** Demand (`status`), ArrearDemandIds  
**Rule:** When `DEMAND_ENABLE_ARREARS=true` and a consumer has an outstanding open demand, creating a new demand triggers arrear roll-forward: the outstanding balance is prepended as an `ARREAR` line item in the new demand, and the source demand's status transitions to `ROLL_FORWARDED`. The arrear tax head code is `{businessServiceCode}_ARREAR` and must be pre-seeded.  
**Violation response:** 400 / 500 — if `ARREAR` tax head not found: plain error returned

---

### BR-LC-006: Demand status set by payment application

**Entities involved:** Demand (`status`, `totalAmount`, `totalCollectedAmount`)  
**Rule:** After a payment is applied: if `totalCollectedAmount < totalAmount` → `PARTIALLY_PAID`; if `totalCollectedAmount = totalAmount` → `PAID`. These transitions are applied via `determineDemandStatus` in the payment service.  
**Violation response:** N/A (automatic transition)

---

## Cross-Module Rules

### BR-CM-001: IDGen required for bill number

**Entities involved:** Bill, IDGen service  
**Rule:** Bill generation calls IDGen `POST /v3/generate` with `templateCode = ${IDGEN_BILL_NUMBER_TEMPLATE_CODE}` and variable `BSCODE = businessServiceCode`. If IDGen returns 404 (template not found), billing propagates it as `404 NOT_FOUND`. If IDGen is unreachable or returns a non-404 error, billing returns 500.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

> **Implementation note:** The current service incorrectly propagates IDGen's `404` as billing's own `404`. This is a service bug — the missing template is an infrastructure misconfiguration, not a client error, so `500` is the correct response. The test asserts `500` to enforce the correct contract.

---

### BR-CM-002: IDGen required for receipt number

**Entities involved:** PaymentDetail, IDGen service  
**Rule:** Payment creation calls IDGen `POST /v3/generate/bulk` grouped by `businessServiceCode` (one bulk call per unique BSCODE) with `templateCode = ${IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE}`. If IDGen is unreachable or returns an error, the entire payment is rolled back.  
**Violation response:** 500 — `RECEIPT_NUMBER_GENERATION_ERROR`

---

### BR-CM-003: IDGen required for cash transaction number

**Entities involved:** Payment (`transactionNumber`), IDGen service  
**Rule:** For `CASH` payments where `transactionNumber` is not supplied by the caller, the service auto-generates one via IDGen using `templateCode = ${IDGEN_TRANSACTION_NUMBER_TEMPLATE_CODE}`. If IDGen fails, the payment is aborted.  
**Violation response:** 500 — `TXN_NUMBER_GENERATION_ERROR`

---

### BR-CM-004: Apportion service required for payment distribution

**Entities involved:** Payment, BillAccountDetail, Apportion service  
**Rule:** Payment creation calls the Apportion service with the bill objects after building the payment aggregate. The Apportion service returns how the amount is distributed across `BillAccountDetail` entries by `orderNumber`. If Apportion is unreachable or returns an error, the entire payment transaction is rolled back.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

### BR-CM-005: Bulk bill generation publishes to PubSub

**Entities involved:** Bill, PubSub  
**Rule:** `POST /v3/bills/bulk-generate` publishes batch jobs to the `${BULK_BILL_GENERATION_PUBSUB_TOPIC}` topic in batches of `BULK_BILL_CONSUMER_BATCH_SIZE` consumer codes per message. If PubSub publish fails for any batch, the entire request fails immediately (no partial success). Failed bulk jobs are retried via a DLQ topic `${BULK_BILL_GENERATION_DLQ_PUBSUB_TOPIC}`.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Field validation failure (binding) | `INVALID_REQUEST` / `VALIDATION_ERROR` |
| 400 | Missing required header (`X-Tenant-ID` or `X-User-ID`) | `MISSING_HEADER` |
| 400 | `periodTo < periodFrom` | `INVALID_PERIOD` (in BulkResponse) |
| 400 | `instrumentDate` in future (all modes) or > `MAX_INSTRUMENT_DATE_AGE_DAYS` (CHEQUE/DD only) | `ChequeDDDateWithFutureDate` / `CHEQUE_DD_DATE_EXCEEDS_RECEIPT` |
| 400 | Missing instrument number or transaction number for payment mode | `INVALID_INST_NUMBER` / `INVALID_TXN_NUMBER` |
| 400 | Invalid line item amount or collected amount | `INVALID_AMOUNT` / `INVALID_COLLECTION` (in BulkResponse) |
| 400 | TaxHead does not belong to the demand's business service | `INVALID_TAX_HEAD` (in BulkResponse) |
| 400 | BusinessService not found or inactive | `UNKNOWN_BUSINESS_SERVICE` (in BulkResponse) |
| 400 | Demand cannot be updated (non-DRAFT/ACTIVE status) | `INVALID_STATUS_TRANSITION` |
| 400 | Fractional or negative payment amount | `INVALID_PAYMENTDETAIL` |
| 404 | BusinessService / TaxHead / Demand / Bill not found | `NOT_FOUND` |
| 409 | TaxHead with same `orderNumber` already exists for service | `CONFLICT` |
| 409 | Overlapping demand period for same consumer/service | `DEMAND_CONFLICT` (in BulkResponse) |
| 422 | Bill is not in ACTIVE status when payment attempted | `BILL_NOT_ACTIVE` |
| 422 | Bill already has an active payment | `BILL_ALREADY_PAID` |
| 422 | No eligible demands found for bill generation | `NO_ELIGIBLE_DEMANDS` |
| 422 | Demand cancellation from non-DRAFT/ACTIVE status | `CANCEL_FAILED` |
| 500 | Over-collection would occur on demand or line item | contains "OVER_COLLECTION_DETECTED" |
| 500 | IDGen unreachable during bill/receipt/transaction number generation | `RECEIPT_NUMBER_GENERATION_ERROR` / `TXN_NUMBER_GENERATION_ERROR` |
| 500 | Apportion service unreachable during payment | `INTERNAL_SERVER_ERROR` |
| 500 | PubSub unavailable for bulk bill generation | `INTERNAL_SERVER_ERROR` |
