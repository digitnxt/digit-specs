#!/usr/bin/env bash
# Seeds everything a tenant needs before account onboarding can send anything:
# notify's templates and provider mappings, and otp's per-purpose configs.
#
#   ./seed-defaults.sh [notify-base] [tenant] [otp-base]
#
# Each base URL must already include that service's context path, which differs
# by environment -- the charts set /notify and /otp, docker-compose leaves the
# default "/".
#   local compose : ./seed-defaults.sh http://localhost:8080 default http://localhost:8110/otp
#   cluster       : ./seed-defaults.sh http://notify.egov:8080/notify default http://otp.egov:8080/otp
#
# Normally only needed for the platform tenant. A tenant created through account
# gets its otp configs automatically from the provisioning event, and inherits
# notify's templates from the platform tenant -- see ONBOARDING-DEFAULTS.md.
#
# Re-running is safe to attempt but not idempotent: both services answer 409 for
# something that already exists, which this reports and skips.
set -uo pipefail

BASE="${1:-http://localhost:8080}"
TENANT="${2:-default}"
OTP_BASE="${3:-http://localhost:8110/otp}"
CFG="$BASE/v3/notification-configs"
MAP="$BASE/v3/provider-mappings"

post() {
  local label="$1" url="$2" body="$3"
  local code
  code=$(curl -s -o /tmp/seed-defaults-notify.out -w '%{http_code}' -X POST "$url" \
    -H 'Content-Type: application/json' -H "X-Tenant-ID: $TENANT" -d "$body")
  case "$code" in
    2*) printf '  %-34s created (%s)\n' "$label" "$code" ;;
    409) printf '  %-34s already exists, skipped\n' "$label" ;;
    *)  printf '  %-34s FAILED (%s): %s\n' "$label" "$code" "$(cat /tmp/seed-defaults-notify.out)" ;;
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


# --- otp ------------------------------------------------------------------
# otp resolves its config strictly by (tenantId, purpose) with no fallback to
# the platform tenant, unlike notify's templates -- so every purpose a tenant
# will use needs a row of its own. Posting only the purpose is deliberate: the
# service fills every other field from ConfigValidation's defaults, which keeps
# the definition of a default config in one place.
echo
echo "otp configs at $OTP_BASE (one per accepted purpose):"
if ! curl -s -m 5 -o /dev/null "$OTP_BASE/health"; then
  echo "  otp not reachable at $OTP_BASE -- skipped."
  echo "  Pass its base URL as the third argument if it lives elsewhere."
else
  for purpose in forgot-password login phone-verify registration transaction; do
    code=$(curl -s -o /tmp/seed-otp.out -w '%{http_code}' -X POST "$OTP_BASE/v3/config" \
      -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -H 'X-User-Id: system' \
      -d "{\"purpose\":\"$purpose\"}")
    case "$code" in
      2*)  printf '  %-34s created (%s)\n' "$purpose" "$code" ;;
      409) printf '  %-34s already exists, skipped\n' "$purpose" ;;
      *)   printf '  %-34s FAILED (%s): %s\n' "$purpose" "$code" "$(cat /tmp/seed-otp.out)" ;;
    esac
  done
fi
