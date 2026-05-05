import json
import shlex
from typing import Union
import requests

_SKIP_HEADERS = {
    "content-length", "transfer-encoding", "connection",
    "accept-encoding", "user-agent",
}


def build_curl(req, *, indent=True) -> str:
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


def attach_curl(node, req) -> None:
    node._curl_request = req
