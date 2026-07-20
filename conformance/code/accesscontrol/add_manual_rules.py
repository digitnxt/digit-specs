#!/usr/bin/env python3
import json
import os
import sys
import requests
from typing import Dict, List

def delete_all_rules(rbac_url: str):
    """Deletes all rules from the RBAC service."""
    try:
        resp = requests.get(f"{rbac_url}/rbac/rules", headers={"X-Tenant-ID": "RJ"}, timeout=5)
        resp.raise_for_status()
        rules = resp.json().get("rules") or []

        for rule in rules:
            rule_id = rule.get("id")
            if rule_id:
                del_resp = requests.delete(f"{rbac_url}/rbac/rules/{rule_id}", headers={"X-Tenant-ID": "RJ"}, timeout=5)
                del_resp.raise_for_status()
                print(f"Deleted rule: {rule_id}")
    except requests.exceptions.RequestException as e:
        print(f"Error deleting rules: {e}", file=sys.stderr)

def add_rule(rbac_url: str, rule: Dict):
    """Adds a rule to the RBAC service."""
    try:
        resp = requests.post(f"{rbac_url}/rbac/rules", json=rule, headers={"X-Tenant-ID": "RJ"}, timeout=5)
        resp.raise_for_status()
        print(f"Added rule for {rule.get('description')}")
    except requests.exceptions.RequestException as e:
        print(f"Error adding rule for {rule.get('description')}: {e}", file=sys.stderr)

def main() -> int:
    rbac_url = os.environ.get("RBAC_URL", "http://localhost:8900")

    # Manually collected rules from all Postman collections
    rules_to_add = [
        # HRMS
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/hrms/employees/v3", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/hrms/employees/v3", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/hrms/employees/v3/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/hrms/employees/v3/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/hrms/employees/v3/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PATCH", "enabled": True, "path": "/hrms/employees/v3/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/hrms/employees/v3/{uuid}/deactivate", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/hrms/employees/v3/{uuid}/reactivate", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/hrms/employees/v3/jurisdictions/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/hrms/employees/v3/jurisdictions", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/hrms/employees/v3/jurisdictions", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/hrms/employees/v3/jurisdictions/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/health", "effect": "ALLOW"},

        # # OTP
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/otp/health", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/otp/v3/generate", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/otp/v3/resend", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/otp/v3/verify", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/otp/v3/invalidate", "effect": "ALLOW"},

        # # Registry Service
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/schema", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/schema/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/schema", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/schema/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/schema/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/data", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/data", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/data/_exists", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/data/_search", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/data/_registry", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/data", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/data/{uuid}", "effect": "ALLOW"},

        # # account
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/account/v1", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/account/v1", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/account/v1/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/account/v1/config", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/account/v1/config", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/account/v1/config/{uuid}", "effect": "ALLOW"},

        # # boundary
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/boundary/v1", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/boundary/v1", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/boundary/v1/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/boundary/v1/boundary-hierarchy-definition", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/boundary/v1/boundary-hierarchy-definition", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/boundary/v1/boundary-relationships", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/boundary/v1/boundary-relationships", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/boundary/v1/boundary-relationships/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/boundary/v1/shapefile/boundary/create", "effect": "ALLOW"},

        # # filestore
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/filestore/v1/files/upload", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/filestore/v1/files/confirm-upload", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/filestore/v1/files/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/filestore/v1/files/metadata", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/filestore/v1/files/tag", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/filestore/v1/files/upload-url", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/filestore/v1/files/download-urls", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/filestore/v1/files/document-categories", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/filestore/v1/files/document-categories/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/filestore/v1/files/document-categories", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/filestore/v1/files/document-categories/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/filestore/v1/files/document-categories/{uuid}", "effect": "ALLOW"},

        # Priority Sorting Test Rules for 'filestore' service
        # With the new logic, Priority (ASC) is the primary sort key.
        # Rule A (Priority 50): A general ALLOW for SUPERUSER on all GET requests under /filestore.
        # {"roleNames": ["SUPERUSER"], "httpMethod": "GET","enabled": True, "path": "/filestore/*", "effect": "ALLOW", "priority": 50, "description": "Priority Test: General filestore GET allowed"},
        # # Rule B (Priority 60): A specific DENY for the upload path. This should LOSE to the rule above because its priority is lower (higher number).
        # {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/filestore/v1/files/upload", "effect": "DENY", "priority": 60, "description": "Priority Test: Specific filestore GET denied"},

        # idgen
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/idgen/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/idgen/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/idgen/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/idgen/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/idgen/v1/generate", "effect": "ALLOW"},

        # # individual
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/individual/v1/configs", "effect": "ALLOW", "priority": 40},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/individual/v1", "effect": "ALLOW", "priority": 40},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/individual/v1/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/individual/v1", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/individual/v1/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/individual/v1/{uuid}", "effect": "ALLOW"},

        # Wildcard and Priority Test Rules for 'individual' service
        # With ascending priority, lower number = higher priority.
        # Rule 1 (Priority 100): Deny GET access for default users to everything under /individual/.
        # {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True,"path": "/individual/*", "effect": "DENY", "priority": 100, "description": "DEFAULT_USER cannot GET any individual data"},
        # # Rule 2 (Priority 90): Allow GET access for default users specifically to /individual/v1. This should override the broader deny.
        # {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True,"path": "/individual/v1", "effect": "ALLOW", "priority": 90, "description": "DEFAULT_USER CAN GET the list of individuals"},
        # # Rule 3 (Priority 1): Allow GET access for SUPERUSER to everything under /individual/.
        # {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True,"path": "/individual/*", "effect": "ALLOW", "priority": 1, "description": "SUPERUSER can get anything under individual"},
        # # Rule 4 (Priority 50): Deny POST access for SUPERUSER to everything under /individual/. This should override the broader allow.
        # {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True,"path": "/individual/*", "effect": "DENY", "priority": 50, "description": "SUPERUSER cannot create any individual data"},


        # localization
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/localization/v1/messages", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/localization/v1/messages", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/localization/v1/messages", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/localization/v1/messages/_upsert", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/localization/v1/messages", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/localization/v1/messages/_missing", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/localization/v1/cache/_bust", "effect": "ALLOW"},

        # # notification
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/notification/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/notification/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/notification/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/notification/v1/template", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/notification/v1/template/preview", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/notification/v1/email/send", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/notification/v1/sms/send", "effect": "ALLOW"},

        # # template-config
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/template-config/v1/config", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/template-config/v1/config", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/template-config/v1/config", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "DELETE", "enabled": True, "path": "/template-config/v1/config", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/template-config/v1/render", "effect": "ALLOW"},

        # # urlshortener
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/url-shortener/v1/shortener", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/url-shortener/{uuid}", "effect": "ALLOW"},

        # # workflow
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/workflow/v1/process/", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/workflow/v1/process/definition", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/workflow/v1/process", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/workflow/v1/process/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/workflow/v1/process/{uuid}/state", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/workflow/v1/state/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/workflow/v1/process/{uuid}/state", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/workflow/v1/state/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/workflow/v1/state/{uuid}/action", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/workflow/v1/state/{uuid}/action", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "PUT", "enabled": True, "path": "/workflow/v1/action/{uuid}", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "POST", "enabled": True, "path": "/workflow/v1/transition", "effect": "ALLOW"},
        {"roleNames": ["SUPERUSER"], "httpMethod": "GET", "enabled": True, "path": "/workflow/v1/transition", "effect": "ALLOW"},
    ]

    print("--- Deleting all existing rules ---")
    delete_all_rules(rbac_url)

    print("\n--- Adding all generated rules ---")
    for rule in rules_to_add:
        rule_with_priority = rule.copy()
        
        # Set default priorities only if not already specified in the rule definition
        if "priority" not in rule_with_priority:
            if rule_with_priority.get("effect") == "ALLOW":
                rule_with_priority["priority"] = 100
            else:
                rule_with_priority["priority"] = 10
        
        # Add description if missing
        if "description" not in rule_with_priority:
            rule_with_priority["description"] = f"Auto-generated for {rule_with_priority['path']}"
            
        add_rule(rbac_url, rule_with_priority)

    print("\n--- Finished generating and adding rules ---")
    return 0

if __name__ == "__main__":
    sys.exit(main())
