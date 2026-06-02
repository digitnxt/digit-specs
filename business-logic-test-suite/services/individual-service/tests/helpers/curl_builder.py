import json
import shlex
from typing import Union
import requests

_SKIP_HEADERS = {
    "content-length", "transfer-encoding", "connection",
    "accept-encoding", "user-agent",
}


def build_curl(req: Union[requests.PreparedRequest, requests.Request, dict],
               *, indent: bool = True) -> str:
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
        raise TypeError(f"Unsupported type: {type(req)}")

    sep   = " \\\n  " if indent else " "
    parts = [f"curl -X {method}", f"{sep}{shlex.quote(url)}"]
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
        ct = headers.get("Content-Type", headers.get("content-type", ""))
        if "application/json" in ct:
            try:
                body = json.dumps(json.loads(body), indent=2)
            except (json.JSONDecodeError, TypeError):
                pass
        parts.append(f"{sep}--data-raw {shlex.quote(body)}")
    return "".join(parts)


def attach_curl(node, req) -> None:
    """Store a PreparedRequest on the pytest node so the conftest hook renders
    it as cURL in the HTML report on failure. Always call BEFORE session.send()."""
    node._curl_request = req
