while checking logs i got suspicious 
REQUEST 1 : 

'{
  "givenName":    "skhjhjnjbjk",
  "familyName":   "Khan",
  "gender":       "FEMALE",

  "email":        "aisha@example.com",
  "dateOfBirth":  "1990-01-15",
  "locale":       "en-IN",
  "additionalAttributes": { "occupation": "teacher" },

  "identifiers": [
    { "identifierType": "AADHAAR", "identifierId": "ABCDE1234F" }
  ]

}'

logs : 

2026/07/16 17:58:44 /go/pkg/mod/github.com/digitnxt/digit3/src/libraries/tenant-migration@v0.0.7/tenantdb/tenantdb.go:129
[0.246ms] [rows:0] SET search_path TO "public"

2026/07/16 17:58:44 /app/internal/repository/config_repository.go:44
[2.172ms] [rows:1] SELECT * FROM "individual_config_v3" WHERE tenantid = 'PGGG' ORDER BY "individual_config_v3"."id" LIMIT 1
{"level":"debug","tenantID":"PGGG","userID":"329cdb2e-29f2-4980-911a-26b5e91fb437","time":"2026-07-16T17:58:44Z","message":"create individual: start"}
{"level":"error","status":404,"body":"[{\"code\":\"NOT_FOUND\",\"message\":\"template not found\",\"description\":\"\",\"params\":[]}]","tenantID":"PGGG","time":"2026-07-16T17:58:44Z","message":"IDGen non-200, using fallback ID"}

2026/07/16 17:58:44 /app/internal/repository/individual_repository.go:53
[0.383ms] [rows:0] SAVEPOINT sp407334082438206433

2026/07/16 17:58:44 /app/internal/repository/individual_repository.go:58
[2.590ms] [rows:1] INSERT INTO "individual_v3" ("id","individualid","tenantid","givenname","familyname","othernames","dateofbirth","gender","age","mobilenumber","hashedmobilenumber","mobilenumberverified","altcontactnumber","email","emailverified","locale","active","fathername","husbandname","photo","userid","additionaldetails","createdBy","modifiedBy","createdTime","modifiedTime","rowversion","requestid") VALUES ('b0072758-7d13-4612-982d-485ff36e7b7d','IND-8e41f964','PGGG','skhjhjnjbjk','Khan','','1990-01-15 00:00:00','FEMALE',NULL,'','',false,'','aisha@example.com',false,'en-IN',true,'','','','','{"occupation":"teacher"}','329cdb2e-29f2-4980-911a-26b5e91fb437','329cdb2e-29f2-4980-911a-26b5e91fb437',1784224724723,1784224724723,1,'5307904313285090ef1e99139b33288e')

2026/07/16 17:58:44 /app/internal/repository/individual_repository.go:89
[1.045ms] [rows:1] INSERT INTO "individual_identifier_v3" ("id","individualid","identifiertype","identifierid","verified","documenttype","filestoreid","active","createdBy","modifiedBy","createdTime","modifiedTime","requestid") VALUES ('7ca2e240-5b10-4310-af89-289439954b5e','b0072758-7d13-4612-982d-485ff36e7b7d','AADHAAR','vault:v1:uEMB2qehHzlBRt5rbQ2utl6iEuMuOqV8hXYQQICEpxQKwpbAjtM=',false,'','',true,'329cdb2e-29f2-4980-911a-26b5e91fb437','329cdb2e-29f2-4980-911a-26b5e91fb437',1784224724723,1784224724723,'5307904313285090ef1e99139b33288e')
2026/07/16 17:58:44 Published message to stream: stream:individual-create-individual
{"timestamp":"2026-07-16T17:58:44Z","level":"info","message":"Published individual-create-individual event","tenantId":"PGGG","topic":"individual-create-individual","count":"1"}
{"level":"info","individualID":"b0072758-7d13-4612-982d-485ff36e7b7d","individualExternalID":"IND-8e41f964","tenantID":"PGGG","time":"2026-07-16T17:58:44Z","message":"individual created"}

ANALYSIS : 
1.here first it set search_path(ignore this) , 
2.then it check for config to find out any regex or unique criteria , found unique criteria for mobilenumber but request doesn’t have mobilenumber so no check for uniquenes

3.then comes SAVEPOINT what the hell is this 
4.then it insert data into data into individual_v3(without any encrypted PII data)

5. we gave AADHAAR so it encypt it
6 .then insert into indentifier_v3

REQUEST 2:
'{
  "givenName":    "skhjhjnjbjk",
  "familyName":   "Khan",
  "gender":       "FEMALE",
  "mobileNumber": "{{mobileNumber}}",
  "altcontactnumber":"6391841076",
  "email":        "aisha@example.com",
  "dateOfBirth":  "1990-01-15",
  "locale":       "en-IN",
  "additionalAttributes": { "occupation": "teacher" },

  "identifiers": [
    { "identifierType": "PAN", "identifierId": "ABCDE1234F" }
  ]

}'

logs : 

2026/07/16 18:12:38 /go/pkg/mod/github.com/digitnxt/digit3/src/libraries/tenant-migration@v0.0.7/tenantdb/tenantdb.go:129
[0.195ms] [rows:0] SET search_path TO "public"

2026/07/16 18:12:38 /app/internal/repository/individual_repository.go:503 record not found
[3.405ms] [rows:0] SELECT * FROM "individual_v3" WHERE hashedmobilenumber = 'b62e947273e2ed4bc3e592bc6ce14fb97590cdc506f814ee01036222c0e7f0c0' AND tenantid = 'PGGG' AND active = true ORDER BY "individual_v3"."id" LIMIT 1

2026/07/16 18:12:38 /app/internal/repository/individual_repository.go:565 record not found
[1.187ms] [rows:0] SELECT * FROM "individual_v3" WHERE mobilenumber = '9195314726' AND tenantid = 'PGGG' AND active = true ORDER BY "individual_v3"."id" LIMIT 1

2026/07/16 18:12:38 /app/internal/repository/config_repository.go:44
[0.582ms] [rows:1] SELECT * FROM "individual_config_v3" WHERE tenantid = 'PGGG' ORDER BY "individual_config_v3"."id" LIMIT 1

2026/07/16 18:12:38 /app/internal/repository/individual_repository.go:565 record not found
[0.545ms] [rows:0] SELECT * FROM "individual_v3" WHERE mobilenumber = '9195314726' AND tenantid = 'PGGG' AND active = true ORDER BY "individual_v3"."id" LIMIT 1
{"level":"debug","tenantID":"PGGG","userID":"329cdb2e-29f2-4980-911a-26b5e91fb437","time":"2026-07-16T18:12:38Z","message":"create individual: start"}
{"level":"error","status":404,"body":"[{\"code\":\"NOT_FOUND\",\"message\":\"template not found\",\"description\":\"\",\"params\":[]}]","tenantID":"PGGG","time":"2026-07-16T18:12:38Z","message":"IDGen non-200, using fallback ID"}

2026/07/16 18:12:38 /app/internal/repository/individual_repository.go:53
[0.374ms] [rows:0] SAVEPOINT sp15867378562414506156

2026/07/16 18:12:38 /app/internal/repository/individual_repository.go:58
[1.344ms] [rows:1] INSERT INTO "individual_v3" ("id","individualid","tenantid","givenname","familyname","othernames","dateofbirth","gender","age","mobilenumber","hashedmobilenumber","mobilenumberverified","altcontactnumber","email","emailverified","locale","active","fathername","husbandname","photo","userid","additionaldetails","createdBy","modifiedBy","createdTime","modifiedTime","rowversion","requestid") VALUES ('1fe0e508-10f0-4f60-b80a-147a7a4bf269','IND-1c692e6f','PGGG','skhjhjnjbjk','Khan','','1990-01-15 00:00:00','FEMALE',NULL,'vault:v1:wCDxv0aNhLq8eEtJR/2wyXZ+jMZ1XWiKHUCNdA4pRibWmgd/sMU=','b62e947273e2ed4bc3e592bc6ce14fb97590cdc506f814ee01036222c0e7f0c0',false,'vault:v1:p2UH82lAoWwgsOigjR4rxqUZLz/zmTnUG0ZV5aEtUQptQ+tEKLM=','aisha@example.com',false,'en-IN',true,'','','','','{"occupation":"teacher"}','329cdb2e-29f2-4980-911a-26b5e91fb437','329cdb2e-29f2-4980-911a-26b5e91fb437',1784225558821,1784225558821,1,'b9d9fa2c726d80975224cb78aaddf932')

2026/07/16 18:12:38 /app/internal/repository/individual_repository.go:89
[1.450ms] [rows:1] INSERT INTO "individual_identifier_v3" ("id","individualid","identifiertype","identifierid","verified","documenttype","filestoreid","active","createdBy","modifiedBy","createdTime","modifiedTime","requestid") VALUES ('31ad28db-5114-4500-a0f6-201f799876aa','1fe0e508-10f0-4f60-b80a-147a7a4bf269','PAN','ABCDE1234F',false,'','',true,'329cdb2e-29f2-4980-911a-26b5e91fb437','329cdb2e-29f2-4980-911a-26b5e91fb437',1784225558821,1784225558821,'b9d9fa2c726d80975224cb78aaddf932')
2026/07/16 18:12:38 Published message to stream: stream:individual-create-individual
{"timestamp":"2026-07-16T18:12:38Z","level":"info","message":"Published individual-create-individual event","tenantId":"PGGG","topic":"individual-create-individual","count":"1"}
{"level":"info","individualID":"1fe0e508-10f0-4f60-b80a-147a7a4bf269","individualExternalID":"IND-1c692e6f","tenantID":"PGGG","time":"2026-07-16T18:12:38Z","message":"individual created"}

ANALYSIS:
1.here first it set search_path(ignore this) , 

2.then it search for hashmobilenumber (i don’t know why)

1. then it search for mobilenumber (i don’t know why)
2. then it check for config to find out any regex or unique criteria , found unique criteria for mobilenumber 
3. search for mobilenumber because we have uniqueness criteria for mobilenumber
4. then comes SAVEPOINT what the hell is this 
5. then it encrypt mobilenumber (but code say it will encrypt both mobile and altmobile)
6. .then it insert data into data into individual_v3
7.  we gave PAN so it don’t encrypt it
8. .then insert into indentifier_v3