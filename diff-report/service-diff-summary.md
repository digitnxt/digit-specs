# DIGIT 3.0.0 vs 2.9 service diff summary

Generated from OpenAPI YAMLs. Full raw diffs are in `diff-report/*.diff`.

## account.yaml
- Compared against: user.yaml
- v3 endpoints: 4; v3 schemas: 10; v3 schema properties: 35
- 2.9 aggregate endpoints: 10; schemas: 16; schema properties: 89
- v3 endpoints with no normalized 2.9 match: 4
  - /account/v1
  - /account/v1/{id}
  - /account/v1/config
  - /account/v1/config/{id}

## billing-payment.yaml
- Compared against: billing.yaml, collection-management.yaml, payment-gateway.yaml
- v3 endpoints: 12; v3 schemas: 23; v3 schema properties: 195
- 2.9 aggregate endpoints: 31; schemas: 65; schema properties: 446
- v3 endpoints with no normalized 2.9 match: 12
  - /business-services
  - /business-services/{code}
  - /tax-heads
  - /tax-heads/{code}
  - /demands
  - /demands/{id}
  - /demands/{id}/freeze
  - /demands/{id}/cancel
  - ... (4 more)

## boundary.yaml
- Compared against: Boundary-V2.yaml
- v3 endpoints: 9; v3 schemas: 19; v3 schema properties: 54
- 2.9 aggregate endpoints: 8; schemas: 18; schema properties: 54
- v3 endpoints with no normalized 2.9 match: 9
  - /_create
  - /shapefile/boundary/create
  - /_search
  - /_update
  - /hierarchy-definition/_create
  - /hierarchy-definition/_search
  - /boundary-relationships/_create
  - /boundary-relationships/_search
  - ... (1 more)

## common.yaml
- Compared against: common-contract.yaml
- v3 endpoints: 0; v3 schemas: 5; v3 schema properties: 24
- 2.9 aggregate endpoints: 0; schemas: 8; schema properties: 81
- v3 endpoints with no normalized 2.9 match: 0

## filestore.yaml
- Compared against: filestore.yaml
- v3 endpoints: 7; v3 schemas: 8; v3 schema properties: 16
- 2.9 aggregate endpoints: 5; schemas: 9; schema properties: 24
- v3 endpoints with no normalized 2.9 match: 7
  - /document-categories
  - /document-categories/{docCode}
  - /upload
  - /upload-url
  - /confirm-upload
  - /download-urls
  - /{fileStoreId}

## idgen.yaml
- Compared against: id-generation.yaml
- v3 endpoints: 2; v3 schemas: 10; v3 schema properties: 24
- 2.9 aggregate endpoints: 1; schemas: 12; schema properties: 43
- v3 endpoints with no normalized 2.9 match: 2
  - /v1/template
  - /v1/generate

## individual.yaml
- Compared against: user.yaml
- v3 endpoints: 4; v3 schemas: 15; v3 schema properties: 77
- 2.9 aggregate endpoints: 10; schemas: 16; schema properties: 89
- v3 endpoints with no normalized 2.9 match: 4
  - /health
  - /v1
  - /v1/{id}
  - /v1/bulk

## localization.yaml
- Compared against: master-service.yaml
- v3 endpoints: 4; v3 schemas: 14; v3 schema properties: 22
- 2.9 aggregate endpoints: 63; schemas: 74; schema properties: 369
- v3 endpoints with no normalized 2.9 match: 4
  - /messages
  - /messages/_upsert
  - /messages/_missing
  - /cache/_bust

## mdms.yaml
- Compared against: mdms-v2.yaml, mdms.yaml
- v3 endpoints: 8; v3 schemas: 20; v3 schema properties: 57
- 2.9 aggregate endpoints: 7; schemas: 32; schema properties: 107
- v3 endpoints with no normalized 2.9 match: 4
  - /schemas
  - /schemas/{schemaCode}
  - /data/{schemaCode}
  - /data/{schemaCode}/{id}

## notification.yaml
- Compared against: user-event.yaml
- v3 endpoints: 4; v3 schemas: 7; v3 schema properties: 32
- 2.9 aggregate endpoints: 5; schemas: 18; schema properties: 80
- v3 endpoints with no normalized 2.9 match: 4
  - /template
  - /template/preview
  - /email/send
  - /sms/send

## otp.yaml
- Compared against: otp.yaml
- v3 endpoints: 4; v3 schemas: 8; v3 schema properties: 28
- 2.9 aggregate endpoints: 3; schemas: 10; schema properties: 43
- v3 endpoints with no normalized 2.9 match: 4
  - /v3/otp/generate
  - /v3/otp/resend
  - /v3/otp/verify
  - /v3/otp/invalidate

## workflow.yaml
- Compared against: workflow.yaml
- v3 endpoints: 12; v3 schemas: 21; v3 schema properties: 119
- 2.9 aggregate endpoints: 13; schemas: 26; schema properties: 172
- v3 endpoints with no normalized 2.9 match: 11
  - /v1/process
  - /v1/process/definition
  - /v1/process/{id}
  - /v1/process/{processId}/state
  - /v1/state/{id}
  - /v1/state/{stateId}/action
  - /v1/action/{id}
  - /v1/process/{processId}/escalation
  - ... (3 more)
