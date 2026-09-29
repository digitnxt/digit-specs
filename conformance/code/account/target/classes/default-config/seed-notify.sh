#!/usr/bin/env bash
# Creates the notification configs and provider mappings that account and otp need
# once they are pointed at notify, plus the EMAIL/SMS provider mappings.
#
#   ./seed-notify.sh [base-url] [tenant]
#
# base-url must already include notify's context path, which differs by
# environment: the chart sets SERVER_SERVLET_CONTEXT_PATH=/notify, while
# docker-compose leaves it at the default "/".
#   local compose : ./seed-notify.sh http://localhost:8080 default
#   cluster       : ./seed-notify.sh http://notify.egov:8080/notify default
#
# Re-running is safe to attempt but not idempotent: notify answers 409 for a
# config that already exists, which this reports and skips.
set -uo pipefail

BASE="${1:-http://localhost:8080}"
TENANT="${2:-default}"
CFG="$BASE/v3/notification-configs"
MAP="$BASE/v3/provider-mappings"

post() {
  local label="$1" url="$2" body="$3"
  local code
  code=$(curl -s -o /tmp/seed-notify.out -w '%{http_code}' -X POST "$url" \
    -H 'Content-Type: application/json' -H "X-Tenant-ID: $TENANT" -d "$body")
  case "$code" in
    2*) printf '  %-34s created (%s)\n' "$label" "$code" ;;
    409) printf '  %-34s already exists, skipped\n' "$label" ;;
    *)  printf '  %-34s FAILED (%s): %s\n' "$label" "$code" "$(cat /tmp/seed-notify.out)" ;;
  esac
}

# The SMS body is not free text. Indian operators enforce TRAI's DLT regime: the delivered message
# must match a template registered against the sender, character for character, or the operator
# drops it silently -- the aggregator still accepts the submission and returns OK with a job id, so
# nothing upstream looks wrong. The text below is the body registered for this account, taken from
# what test-lts delivers with (notification_template, tenant "global", sms-otp-login). The trailing
# EGOVS is the registered entity footer and is part of the match, as is the blank line before it.
# Change either and delivery stops without an error anywhere.
#
# Email has no such constraint, so it keeps its own wording and a subject. notify renders flat
# {{placeholder}} only -- every placeholder needs a payloadBindings entry naming a JsonPath.
otp_config() {
  local code="$1" subject="$2"
  local sms_body="Dear Citizen, Your Login OTP is {{otp}}\n\nEGOVS"
  local email_body="Your one-time code is {{otp}}. Do not share it with anyone."
  post "$code" "$CFG" '{
    "templateCode": "'"$code"'",
    "channels": {
      "email": {
        "enabled": true,
        "subject": { "default": "'"$subject"'" },
        "body": { "default": "'"$email_body"'" },
        "payloadBindings": { "otp": "$.otp" }
      },
      "sms": {
        "enabled": true,
        "body": { "default": "'"$sms_body"'" },
        "payloadBindings": { "otp": "$.otp" }
      }
    }
  }'
}

echo "Seeding notify at $BASE for tenant \"$TENANT\""

echo "OTP configs (email + sms per purpose):"
otp_config otp-login          "Your login code"
otp_config otp-mfa            "Your verification code"
otp_config otp-password-reset "Reset your password"
otp_config otp-transaction    "Confirm your transaction"
otp_config otp-generic        "Your one-time code"

# Reproduces the wording the notification service sends today. account supplies
# loginUrls as a label->url map, which a notify template cannot address, so each
# link is flattened to its own variable by a JsonPath binding.
#
# All three links are bound because the template prints all three. A binding whose
# path does not resolve fails the whole email rather than leaving a gap, so every
# label named here has to be present in account.notification.first-login-urls --
# drop the matching line from the body as well if an environment omits one.
echo "account config:"
post "account-tenant-temp-password" "$CFG" '{
  "templateCode": "account-tenant-temp-password",
  "channels": {
    "email": {
      "enabled": true,
      "subject": { "default": "Your {{tenantName}} administrator account" },
      "body": { "default": "Dear Administrator,\n\nAn administrator account has been created for {{tenantName}} ({{tenantCode}}).\n\nUsername: {{email}}\nTemporary password: {{password}}\n\nSign in here:\nAdmin : {{adminUrl}}\nEmployee : {{employeeUrl}}\nCitizen : {{citizenUrl}}\n\nYou will be asked to choose a new password the first time you sign in. This temporary password cannot be recovered or resent, so please sign in soon.\n\nIf you did not expect this email, please contact your platform administrator.\n\nRegards,\nDIGIT Platform\n" },
      "payloadBindings": {
        "tenantCode": "$.tenantCode",
        "tenantName": "$.tenantName",
        "email": "$.email",
        "password": "$.password",
        "adminUrl": "$.loginUrls.admin",
        "employeeUrl": "$.loginUrls.employee",
        "citizenUrl": "$.loginUrls.citizen"
      }
    }
  }
}'

# Without a mapping the channel fails with "No provider mapping found" even when
# the jar is loaded. country is omitted, so these are the catch-all rows.
echo "provider mappings:"
post "EMAIL -> gmail" "$MAP" '{ "channel": "EMAIL", "providers": ["gmail"] }'
post "SMS -> smscountry" "$MAP" '{ "channel": "SMS", "providers": ["smscountry"] }'

echo
echo "Loaded providers:"
curl -s "$BASE/v3/providers" -H "X-Tenant-ID: $TENANT"; echo
