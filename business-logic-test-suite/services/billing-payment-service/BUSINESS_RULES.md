# Business Rules — Billing Service

## Cross-Field Rules

### BR-CF-001: Effective date range must be ordered

**Entities involved:** BusinessServiceRequest (`effectiveFrom`, `effectiveTo`)  
**Rule:** When both `effectiveFrom` and `effectiveTo` are provided, `effectiveFrom` must be strictly less than `effectiveTo`. A service is only usable within this date range.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-002: Minimum payable requires partial payment enabled

**Entities involved:** BusinessServiceRequest (`partialPaymentAllowed`, `minPayableAmount`)  
**Rule:** `minPayableAmount` is only enforced when `partialPaymentAllowed = true`. When `partialPaymentAllowed = false`, any `minPayableAmount` value is ignored.  
**Violation response:** N/A (ignored if partialPaymentAllowed=false; 400 if amount is invalid when enabled)

---

### BR-CF-003: Demand period must be ordered

**Entities involved:** DemandRequest (`periodFrom`, `periodTo`)  
**Rule:** `periodFrom` must be strictly less than `periodTo` for every demand record.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-004: Collected amount never exceeds total

**Entities involved:** Demand (`collectedAmount`, `totalAmount`)  
**Rule:** `collectedAmount` must never exceed `totalAmount`. Any payment or adjustment that would cause `collectedAmount > totalAmount` is rejected.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (`OVER_COLLECTION_DETECTED`)

---

### BR-CF-005: Demand total equals sum of line items

**Entities involved:** DemandRequest (`totalAmount`), LineItem (`amount`)  
**Rule:** The `totalAmount` on a demand must equal the sum of all its line item amounts: `totalAmount = Σ(lineItem.amount)`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-006: Payer array maximum ten entries

**Entities involved:** DemandRequest (`payer`)  
**Rule:** A demand may include 0–10 payer records. Supplying more than 10 payer entries is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-007: Bill paid amount never exceeds total

**Entities involved:** Bill (`amountPaid`, `totalAmount`)  
**Rule:** `amountPaid` must never exceed `totalAmount` on a bill. This invariant is maintained after every payment application.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CF-008: Payment instrument validated by mode

**Entities involved:** PaymentRequest (`paymentMode`, `instrumentNumber`, `instrumentDate`, `transactionNumber`)  
**Rule:** Instrument requirements vary by payment mode:
- `CASH`: no instrument validation required
- `CHEQUE`, `DD`, `POSTAL_ORDER`, `OFFLINE_NEFT`, `OFFLINE_RTGS`: require `instrumentNumber` and `instrumentDate`
- `ONLINE`, `UPI`, `CARD`, `NETBANKING`, `WALLET`, `ONLINE_NEFT`, `ONLINE_RTGS`: require `transactionNumber` and `instrumentNumber`

**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-009: Instrument date within age constraint

**Entities involved:** PaymentRequest (`instrumentDate`)  
**Rule:** `instrumentDate` must not be in the future and must not be more than `MAX_INSTRUMENT_DATE_AGE_DAYS` (default 90) days in the past.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-010: Payment total equals sum of details

**Entities involved:** PaymentRequest (`totalAmountPaid`), PaymentDetail (`amountPaid`)  
**Rule:** `totalAmountPaid` must equal `Σ(paymentDetail.amountPaid)` across all bills in the payment.  
**Violation response:** 400 — `BAD_REQUEST`

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

### BR-CS-003: LineItem tax head belongs to service

**Entities involved:** LineItem (`taxHeadCode`), Demand (`businessServiceCode`), TaxHead  
**Rule:** Each `LineItem` in a demand must reference a `TaxHead.code` that belongs to the same `businessServiceCode` as the demand. A tax head from a different service cannot be used.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CS-004: No overlapping demand periods same consumer

**Entities involved:** Demand (`periodFrom`, `periodTo`, `consumerCode`, `businessServiceCode`)  
**Rule:** Two active demands for the same `(tenantId, businessServiceCode, consumerCode)` cannot have overlapping `(periodFrom, periodTo)` ranges. The database enforces this with a GiST exclusion constraint.  
**Violation response:** 409 — `CONFLICT` (`DEMAND_CONFLICT`)

---

### BR-CS-005: Only one active bill per consumer service

**Entities involved:** Bill (`status`, `consumerCode`, `businessServiceCode`)  
**Rule:** At most one bill with `status = ACTIVE` may exist for a given `(tenantId, businessServiceCode, consumerCode)` at any time. Generating a new bill while an unexpired active bill exists returns the existing bill (idempotent).  
**Violation response:** 409 — `CONFLICT` (if a non-expired active bill already exists)

---

### BR-CS-006: Bill must be ACTIVE or PARTIALLY_PAID

**Entities involved:** Bill (`status`), PaymentDetail  
**Rule:** Payment can only be applied to a bill with status `ACTIVE` or `PARTIALLY_PAID`. Bills with status `CANCELLED`, `EXPIRED`, `PAID`, or `PAYMENT_CANCELLED` are rejected.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CS-007: Unique payment per bill prevented

**Entities involved:** PaymentDetail (`billId`)  
**Rule:** A single bill may appear in at most one `PaymentDetail` row. The `UNIQUE(bill_id)` constraint prevents a bill from being paid twice simultaneously; a bill already present in a `PaymentDetail` is rejected for a second payment.  
**Violation response:** 409 — `CONFLICT`

---

### BR-CS-008: Demand requires active BusinessService

**Entities involved:** Demand, BusinessService  
**Rule:** A demand cannot be created if its `businessServiceCode` references a non-existent or inactive (`isActive = false`) `BusinessService`.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-009: Bill requires qualifying demand

**Entities involved:** Bill, Demand (`status`)  
**Rule:** Bill generation (`POST /v3/bills/generate`) requires at least one demand for the consumer with status `ACTIVE`, `FROZEN`, or `PARTIALLY_PAID`. If no qualifying demands exist, bill generation is rejected.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

## Lifecycle Rules

### BR-LC-001: Demand status transitions one-way

**Entities involved:** Demand (`status`)  
**Rule:** Demand status transitions follow a one-way path: `DRAFT → ACTIVE → FROZEN → PARTIALLY_PAID or PAID → ROLL_FORWARDED or CANCELLED`. A demand can also transition `DRAFT/ACTIVE → CANCELLED`. Transitions in the reverse direction or between non-adjacent states are rejected.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-LC-002: Only DRAFT and ACTIVE editable

**Entities involved:** Demand (`status`)  
**Rule:** Demand update (`PUT /v3/demands`, `PATCH /v3/demands/:id`) is only permitted when the demand's current status is `DRAFT` or `ACTIVE`. Demands in `FROZEN`, `PARTIALLY_PAID`, `PAID`, `ROLL_FORWARDED`, or `CANCELLED` states cannot be modified.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-LC-003: Bill generation freezes ACTIVE demands

**Entities involved:** Bill, Demand  
**Rule:** When a bill is generated, all demands with status `ACTIVE` that are included in the bill are transitioned to `FROZEN`. This prevents further updates to those demands while a bill is outstanding.  
**Violation response:** N/A (side effect of bill generation)

---

### BR-LC-004: Bill status transitions by event

**Entities involved:** Bill (`status`)  
**Rule:** Bill status transitions: `ACTIVE → EXPIRED` (when `expiry_date` passes), `ACTIVE → CANCELLED` (explicit cancel), `ACTIVE → PARTIALLY_PAID` (partial payment), `ACTIVE/PARTIALLY_PAID → PAID` (full payment), `PAID → PAYMENT_CANCELLED` (payment dishonoured/cancelled).  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (invalid transition)

---

### BR-LC-005: Payment status transitions normal path

**Entities involved:** Payment (`paymentStatus`, `instrumentStatus`)  
**Rule:** `paymentStatus` transitions: `NEW → DEPOSITED → RECONCILED` (normal path), `NEW/DEPOSITED → CANCELLED` or `DISHONOURED`. Closed instrument statuses (`REJECTED`, `CANCELLED`, `DISHONOURED`) are terminal and cannot be reversed.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-LC-006: Arrear demands reference rolled forward

**Entities involved:** Demand (`status`), ArrearDemand  
**Rule:** When arrear roll-forward is enabled, a new demand is created with the outstanding amount, and the source demand transitions to `ROLL_FORWARDED`. The new demand's `arrearDemandIds` lists the IDs of the demands rolled forward. A `ROLL_FORWARDED` demand cannot be edited or paid directly.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (on attempt to edit/pay ROLL_FORWARDED demand)

---

## Cross-Module Rules

### BR-CM-001: IDGen required for bill number

**Entities involved:** Bill, IDGen service  
**Rule:** Bill generation calls IDGen `POST /v3/generate` with `templateCode = ${IDGEN_BILL_NUMBER_TEMPLATE_CODE}` to produce a unique bill number. If IDGen is unreachable or returns an error, the entire bill generation is rolled back and no bill is created.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

### BR-CM-002: IDGen required for receipt number

**Entities involved:** PaymentDetail, IDGen service  
**Rule:** Payment creation calls IDGen `POST /v3/generate/bulk` (one ID per bill in the payment) with `templateCode = ${IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE}` to produce receipt numbers. If IDGen is unreachable or returns an error, the entire payment is rolled back.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

### BR-CM-003: Apportion distributes payment amount

**Entities involved:** Payment, BillAccountDetail, Apportion service  
**Rule:** Payment creation calls the Apportion service with bill IDs and the payment amount. The Apportion service returns how the amount is distributed across `BillAccountDetail.taxHeadCode` entries ordered by `orderNumber`. If Apportion is unreachable or returns an error, the entire payment is rolled back.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

### BR-CM-004: Bulk bill generation via PubSub

**Entities involved:** Bill, PubSub  
**Rule:** `POST /v3/bills/bulk-generate` publishes a message to the `${BULK_BILL_GENERATION_PUBSUB_TOPIC}` topic rather than generating bills synchronously. If PubSub is unavailable, the bulk request fails immediately (it cannot fall back to synchronous).  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Field validation failure (missing required field, type mismatch) | `VALIDATION_ERROR` |
| 400 | Missing required header (`X-Tenant-ID` or `X-User-ID`) | `MISSING_HEADER` |
| 400 | `periodFrom` ≥ `periodTo` | `BAD_REQUEST` |
| 400 | Invalid instrument details for payment mode | `BAD_REQUEST` |
| 400 | `instrumentDate` in the future or older than `MAX_INSTRUMENT_DATE_AGE_DAYS` | `BAD_REQUEST` |
| 400 | `totalAmount` ≠ Σ(lineItem.amount) | `BAD_REQUEST` |
| 404 | BusinessService / TaxHead / Demand / Bill not found | `NOT_FOUND` |
| 409 | Overlapping demand period for same consumer/service | `CONFLICT` (`DEMAND_CONFLICT`) |
| 409 | Active bill already exists for consumer/service | `CONFLICT` |
| 409 | Bill already present in a payment (duplicate payment attempt) | `CONFLICT` |
| 409 | TaxHead with same `orderNumber` already exists for service | `CONFLICT` |
| 422 | Over-collection: `collectedAmount` would exceed `totalAmount` | `UNPROCESSABLE_ENTITY` (`OVER_COLLECTION_DETECTED`) |
| 422 | Invalid status transition for Demand / Bill / Payment | `UNPROCESSABLE_ENTITY` |
| 422 | Bill status not `ACTIVE` or `PARTIALLY_PAID` at payment time | `UNPROCESSABLE_ENTITY` |
| 422 | No qualifying demands found for bill generation | `UNPROCESSABLE_ENTITY` |
| 422 | Attempt to edit non-DRAFT/non-ACTIVE demand | `UNPROCESSABLE_ENTITY` |
| 500 | IDGen unreachable during bill/receipt number generation | `INTERNAL_SERVER_ERROR` |
| 500 | Apportion service unreachable during payment | `INTERNAL_SERVER_ERROR` |
| 500 | PubSub unavailable for bulk bill generation | `INTERNAL_SERVER_ERROR` |
