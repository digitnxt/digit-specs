"""
Idempotent seed of the prerequisite data the employee / individual conformance
suites need in a tenant (default P2 on uat-saas). Every call goes through Kong
with X-Tenant-ID set to the target tenant — nothing is written elsewhere, and no
service config / deployment is touched.

Creates (only if missing):
  - idgen templates  EmployeeCode, individual   (names from the deployments' IDGEN_NAME /
                                                 IDGEN_INDIVIDUAL_ID_FORMAT)
  - boundary hierarchy CONF_HIER (state -> district), boundaries CONF_STATE_1 /
    CONF_DIST_1 and their relationships
  - one seed individual (givenName "Conformance Seed")

Run:
  CONFORMANCE_TOKEN=<bearer> python3 seed_p2.py [--base-url https://uat-saas.digit.org] [--tenant P2]

Prints the CONFORMANCE_BOUNDARY_* exports for the employee suite.
"""
import argparse
import json
import os
import sys

import requests

HIERARCHY = "CONF_HIER"
STATE, DISTRICT = "CONF_STATE_1", "CONF_DIST_1"

IDGEN_TEMPLATES = {
    "EmployeeCode": "EMP-{DATE:yyyy}-{SEQ}",  # employee client sends no variables
    "individual": "IND-{ORG}-{DATE:yyyymmdd}-{SEQ}",
}


class Client:
    def __init__(self, base, tenant, token):
        self.base = base.rstrip("/")
        self.s = requests.Session()
        self.s.headers.update({
            "Authorization": f"Bearer {token}",
            "X-Tenant-ID": tenant,
            "Content-Type": "application/json",
        })

    def call(self, method, path, **kw):
        r = self.s.request(method, self.base + path, timeout=30, **kw)
        print(f"  {method} {path} -> {r.status_code}")
        return r


def _fail(r, what):
    print(f"FAILED: {what}: HTTP {r.status_code} {r.text[:500]}")
    sys.exit(1)


def seed_idgen(c):
    print("idgen templates")
    for code, template in IDGEN_TEMPLATES.items():
        r = c.call("GET", "/idgen/v3/template", params={"templateCode": code})
        if r.status_code == 200 and r.json():
            print(f"    {code}: exists")
            continue
        body = {"templateCode": code,
                "config": {"template": template,
                           "sequence": {"start": 1, "padding": {"length": 5, "char": "0"}}}}
        r = c.call("POST", "/idgen/v3/template", json=body)
        if r.status_code not in (200, 201, 409):
            _fail(r, f"create idgen template {code}")
        r = c.call("POST", "/idgen/v3/generate", json={"templateCode": code, "variables": {"ORG": "P2"}})
        print(f"    {code}: sample id {r.text[:120]}")


def seed_boundary(c):
    print("boundary")
    r = c.call("GET", "/boundary/v3/hierarchy", params={"hierarchyType": HIERARCHY})
    if not (r.status_code == 200 and r.json().get("hierarchy")):
        body = {"hierarchy": {"hierarchyType": HIERARCHY, "boundaryHierarchy": [
            {"boundaryType": "state", "parentBoundaryType": None, "active": True},
            {"boundaryType": "district", "parentBoundaryType": "state", "active": True},
        ]}}
        r = c.call("POST", "/boundary/v3/hierarchy", json=body)
        if r.status_code not in (200, 201, 409):
            _fail(r, "create hierarchy")

    r = c.call("GET", "/boundary/v3/boundaries", params={"codes": f"{STATE},{DISTRICT}"})
    have = {b.get("code") for b in (r.json() if r.status_code == 200 and isinstance(r.json(), list)
                                    else (r.json().get("boundary") or [] if r.status_code == 200 else []))}
    missing = [{"code": x, "geometry": {"type": "Point", "coordinates": [77.59, 12.97]}}
               for x in (STATE, DISTRICT) if x not in have]
    if missing:
        r = c.call("POST", "/boundary/v3/boundaries", json={"boundary": missing})
        if r.status_code not in (200, 201, 409):
            _fail(r, "create boundaries")

    for code, btype, parent in ((STATE, "state", None), (DISTRICT, "district", STATE)):
        r = c.call("GET", "/boundary/v3/relationship",
                   params={"hierarchyType": HIERARCHY, "codes": code})
        if r.status_code == 200 and code in r.text:
            continue
        rel = {"code": code, "hierarchyType": HIERARCHY, "boundaryType": btype}
        if parent:
            rel["parent"] = parent
        r = c.call("POST", "/boundary/v3/relationship", json={"relationship": rel})
        if r.status_code not in (200, 201, 409):
            _fail(r, f"create relationship {code}")


def seed_individual(c):
    print("individual")
    r = c.call("GET", "/individuals/v3/individuals", params={"givenName": "Conformance Seed", "limit": 1})
    if r.status_code == 200 and r.json().get("individuals"):
        ind = r.json()["individuals"][0]
    else:
        r = c.call("POST", "/individuals/v3/individuals",
                   json={"givenName": "Conformance Seed", "gender": "OTHER", "mobileNumber": "9000000001"})
        if r.status_code not in (200, 201):
            _fail(r, "create seed individual")
        ind = r.json()
        ind = ind[0] if isinstance(ind, list) else ind
    print(f"    seed individual id={ind.get('id')} individualId={ind.get('individualId')}")
    return ind


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="https://uat-saas.digit.org")
    ap.add_argument("--tenant", default="P2")
    a = ap.parse_args()
    token = os.environ.get("CONFORMANCE_TOKEN")
    if not token:
        sys.exit("CONFORMANCE_TOKEN not set")
    c = Client(a.base_url, a.tenant, token)
    seed_idgen(c)
    try:
        seed_boundary(c)
    except SystemExit:
        print("WARNING: boundary seed failed — employee jurisdiction tests will skip/fail")
    seed_individual(c)
    print("\nexport CONFORMANCE_BOUNDARY_CODE=%s CONFORMANCE_BOUNDARY_TYPE=state "
          "CONFORMANCE_BOUNDARY_HIERARCHY=%s" % (STATE, HIERARCHY))


if __name__ == "__main__":
    main()
