"""
Reads seed_manifest.yaml. For each prerequisite:
  1. Issues the CHECK request. If it returns expect_status, entity exists — skip.
  2. Otherwise issues the CREATE request.
  3. 200, 201, or 409 on CREATE = success (409 means already exists).
  4. Any other status raises RuntimeError — the suite cannot safely proceed.

Seeds are never deleted. They represent long-lived platform state.

${VAR_NAME} tokens in values are resolved from env_map.yaml, then OS env.
When base_url_arg for a seed is not in service_urls (URL not provided),
the seed is skipped with a warning.
"""
import os
import yaml
import requests

_SERVICE_ROOT = os.path.join(os.path.dirname(__file__), "../..")
_MANIFEST     = os.path.join(_SERVICE_ROOT, "seed_manifest.yaml")
_ENV_MAP      = os.path.join(_SERVICE_ROOT, "env_map.yaml")


def _load_env_map() -> dict:
    try:
        with open(_ENV_MAP) as f:
            return yaml.safe_load(f) or {}
    except FileNotFoundError:
        return {}


def _resolve(value, env_map):
    if isinstance(value, str) and value.startswith("${") and value.endswith("}"):
        key = value[2:-1]
        return env_map.get(key, os.environ.get(key, value))
    return value


def _resolve_deep(obj, env_map):
    if isinstance(obj, dict):
        return {k: _resolve_deep(v, env_map) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_resolve_deep(i, env_map) for i in obj]
    return _resolve(obj, env_map)


def provision(headers: dict, service_urls: dict) -> None:
    try:
        with open(_MANIFEST) as f:
            manifest = yaml.safe_load(f)
    except FileNotFoundError:
        return

    if not manifest:
        return

    env_map = _load_env_map()

    for seed in manifest.get("prerequisites", []):
        seed = _resolve_deep(seed, env_map)

        base_url_arg = seed.get("base_url_arg", "--base-url")
        base_url     = service_urls.get(base_url_arg)

        if not base_url:
            print(
                f"[seed] SKIPPED {seed['id']}: "
                f"{base_url_arg} not provided — "
                f"tests that depend on this seed may fail."
            )
            continue

        check = seed["check"]
        resp  = requests.request(
            check["method"],
            f"{base_url}{check['path']}",
            headers=headers,
            params=check.get("params", {}),
            timeout=10,
        )

        if resp.status_code == check.get("expect_status", 200):
            continue

        create      = seed["create"]
        create_resp = requests.request(
            create["method"],
            f"{base_url}{create['path']}",
            headers=headers,
            json=create.get("body"),
            timeout=10,
        )

        if create_resp.status_code not in (200, 201, 409):
            raise RuntimeError(
                f"Seed {seed['id']} failed — cannot run tests without this prerequisite.\n"
                f"  Service  : {seed.get('service', 'self')} ({base_url})\n"
                f"  CHECK    : {check['method']} {check['path']}"
                f" → {resp.status_code}\n"
                f"  CREATE   : {create['method']} {create['path']}"
                f" → {create_resp.status_code}\n"
                f"  Response : {create_resp.text[:400]}"
            )
