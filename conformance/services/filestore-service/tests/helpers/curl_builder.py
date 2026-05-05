"""
Converts HTTP requests into complete cURL command strings.

Supports:
  - requests.PreparedRequest  (regular JSON/form requests)
  - Multipart file uploads    (via build_multipart_curl + attach_multipart_curl)
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


def build_multipart_curl(url: str, headers: dict, *, fields=None, files=None, indent: bool = True) -> str:
    """Build a cURL command for multipart/form-data file uploads."""
    parts = ["curl -X POST"]
    sep = " \\\n  " if indent else " "

    parts.append(f"{sep}{shlex.quote(url)}")

    _skip = _SKIP_HEADERS | {"content-type"}
    for key, value in (headers or {}).items():
        if key.lower() in _skip:
            continue
        parts.append(f"{sep}-H {shlex.quote(f'{key}: {value}')}")

    for key, value in (fields or {}).items():
        parts.append(f"{sep}-F {shlex.quote(f'{key}={value}')}")

    for key, file_info in (files or {}).items():
        if isinstance(file_info, tuple) and len(file_info) >= 2:
            filename = file_info[0]
            content_type = file_info[2] if len(file_info) > 2 else "application/octet-stream"
            parts.append(f"{sep}-F {shlex.quote(f'{key}=@{filename};type={content_type}')}")
        else:
            parts.append(f"{sep}-F {shlex.quote(f'{key}=@<file>')}")

    return "".join(parts)


def attach_curl(node, req: Union[requests.PreparedRequest, requests.Request, dict]) -> None:
    """Store a PreparedRequest on the pytest node for conftest cURL injection."""
    node._curl_request = req


def attach_multipart_curl(node, url: str, headers: dict, *, fields=None, files=None) -> None:
    """Store multipart upload info on the pytest node for conftest cURL injection."""
    node._curl_multipart = (url, headers, fields, files)
