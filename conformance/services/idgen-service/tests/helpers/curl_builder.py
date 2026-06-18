"""
Converts an HTTP request into a complete cURL command string.

Supports:
  - requests.PreparedRequest  (from the `requests` library)
  - requests.Request          (pre-prepared)
  - Plain dict with keys: method, url, headers, body (fallback)

Usage in a test:
    import requests
    from tests.helpers.curl_builder import build_curl, attach_curl

    def test_something(request, base_url, auth_headers):
        req = requests.Request(
            "POST",
            f"{base_url}/template",
            headers=auth_headers,
            json={"templateCode": "receipt-id", "config": {"template": "{SEQ}"}},
        )
        prepared = req.prepare()
        attach_curl(request.node, prepared)   # stores for conftest hook

        session = requests.Session()
        response = session.send(prepared)
        assert response.status_code == 201
"""

import json
import shlex
from typing import Union

import requests

_SKIP_HEADERS = {
    "content-length",
    "transfer-encoding",
    "connection",
    "accept-encoding",
    "user-agent",
}


def build_curl(
    req: Union[requests.PreparedRequest, requests.Request, dict],
    *,
    indent: bool = True,
) -> str:
    """
    Build a complete cURL command from an HTTP request.

    Returns a string like:
        curl -X POST \\
          'https://api.example.com/idgen/v3/template' \\
          -H 'Authorization: Bearer abc' \\
          -H 'Content-Type: application/json' \\
          --data-raw '{"templateCode": "receipt-id", "config": {"template": "{SEQ}"}}'
    """
    if isinstance(req, requests.Request):
        req = req.prepare()

    if isinstance(req, requests.PreparedRequest):
        method  = (req.method or "GET").upper()
        url     = req.url or ""
        headers = dict(req.headers or {})
        body    = req.body
    elif isinstance(req, dict):
        method  = req.get("method", "GET").upper()
        url     = req.get("url", "")
        headers = req.get("headers", {})
        body    = req.get("body")
    else:
        raise TypeError(f"Unsupported request type: {type(req)}")

    parts = [f"curl -X {method}"]
    sep = " \\\n  " if indent else " "

    parts.append(f"{sep}{shlex.quote(url)}")

    for key, value in headers.items():
        if key.lower() in _SKIP_HEADERS:
            continue
        parts.append(f"{sep}-H {shlex.quote(f'{key}: {value}')}")

    if body:
        if isinstance(body, bytes):
            try:
                body = body.decode("utf-8")
            except UnicodeDecodeError:
                body = body.hex()

        content_type = headers.get("Content-Type", headers.get("content-type", ""))
        if "application/json" in content_type:
            try:
                body = json.dumps(json.loads(body), indent=2)
            except (json.JSONDecodeError, TypeError):
                pass

        parts.append(f"{sep}--data-raw {shlex.quote(body)}")

    return "".join(parts)


def attach_curl(node, req: Union[requests.PreparedRequest, requests.Request, dict]) -> None:
    """
    Store a request on the pytest node so conftest.pytest_runtest_makereport
    can pick it up and inject the cURL into the HTML report on failure.

    Call this immediately after preparing the request, before sending it.
    In multi-step tests this should be called before each request — the hook
    will use whichever request was stored last when the test fails.
    """
    node._curl_request = req
