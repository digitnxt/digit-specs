import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_error_array,
    assert_gateway_headers,
    assert_json_content_type,
)
from tests.helpers.factories import (
    make_employee,
    make_employee_update,
    make_employee_patch,
    make_jurisdiction_create,
    make_jurisdiction_update,
    make_invalid_employee,
    make_invalid_jurisdiction,
)


def _send(node, method, url, headers=None, json_body=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _rand_id():
    return str(uuid.uuid4())


class TestEmployeeValidationContracts:
    def test_empty_array_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers,
                         json_body=[make_invalid_employee("missing_required")])
        assert response.status_code == 400
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    @pytest.mark.parametrize("strategy", [
        "missing_employee_type", "missing_department", "missing_designation", "wrong_type",
    ])
    def test_field_level_validation_returns_400(self, request, base_url, auth_headers, gateway_headers_spec, strategy):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers,
                         json_body=[make_invalid_employee(strategy)])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_put_missing_version_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        # Full mutable state but no version → bind rejects it before existence check.
        body = make_employee_update(version=1)
        body.pop("version")
        response = _send(request.node, "PUT", f"{base_url}/employees/{_rand_id()}",
                         headers=auth_headers, json_body=body)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_patch_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PATCH", f"{base_url}/employees/{_rand_id()}",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)


class TestEmployeeAuthContracts:
    """Auth is enforced by Kong, not the service. These only hold through the gateway."""

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees")
        assert response.status_code == 401

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/employees", headers=bad)
        assert response.status_code == 401


class TestEmployeeNotFoundContracts:
    def test_get_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees/{_rand_id()}", headers=auth_headers)
        assert response.status_code == 404
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT", f"{base_url}/employees/{_rand_id()}",
                         headers=auth_headers, json_body=make_employee_update(version=1))
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_patch_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PATCH", f"{base_url}/employees/{_rand_id()}",
                         headers=auth_headers, json_body=make_employee_patch(version=1))
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "DELETE", f"{base_url}/employees/{_rand_id()}", headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_deactivate_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees/{_rand_id()}/deactivate",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_reactivate_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees/{_rand_id()}/reactivate",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_uuid_path_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees/not-a-uuid", headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)


class TestEmployeeConflictContracts:
    def test_double_deactivate_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/employees",
                  headers=auth_headers, json_body=[make_employee()])
        if r.status_code != 201:
            pytest.skip("Could not create employee")
        emp_id = r.json()[0]["id"]
        try:
            first = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/deactivate",
                          headers=auth_headers)
            assert first.status_code == 200, f"First deactivate failed: {first.text}"
            second = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/deactivate",
                           headers=auth_headers)
            assert second.status_code == 409, "Deactivating an already-inactive employee must be 409"
            assert_error_array(second.json())
            assert_gateway_headers(second, gateway_headers_spec)
        finally:
            req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)

    def test_reactivate_active_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/employees",
                  headers=auth_headers, json_body=[make_employee()])
        if r.status_code != 201:
            pytest.skip("Could not create employee")
        emp_id = r.json()[0]["id"]
        try:
            # A freshly created employee is already active → reactivate must 409.
            resp = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/reactivate",
                         headers=auth_headers)
            assert resp.status_code == 409, "Reactivating an already-active employee must be 409"
            assert_gateway_headers(resp, gateway_headers_spec)
        finally:
            req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)

    def test_stale_version_update_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/employees",
                  headers=auth_headers, json_body=[make_employee()])
        if r.status_code != 201:
            pytest.skip("Could not create employee")
        emp_id = r.json()[0]["id"]
        try:
            # First PUT at version 1 succeeds and bumps to 2; a second PUT still
            # claiming version 1 is stale → 409 ROW_VERSION_MISMATCH.
            ok = _send(request.node, "PUT", f"{base_url}/employees/{emp_id}",
                       headers=auth_headers, json_body=make_employee_update(version=1))
            assert ok.status_code == 200, f"First update failed: {ok.text}"
            stale = _send(request.node, "PUT", f"{base_url}/employees/{emp_id}",
                          headers=auth_headers, json_body=make_employee_update(version=1))
            assert stale.status_code == 409, "Stale-version update must be 409"
            err = assert_error_array(stale.json())
            assert err["code"] == "ROW_VERSION_MISMATCH", f"Unexpected error code: {err['code']}"
            assert_gateway_headers(stale, gateway_headers_spec)
        finally:
            req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)


class TestJurisdictionNegativeContracts:
    @pytest.mark.parametrize("strategy", [
        "missing_required",
        "missing_boundary_relation",
        "empty_boundary_relation",
        "boundary_relation_as_strings",
        "incomplete_boundary_entry",
    ])
    def test_invalid_jurisdiction_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec, strategy):
        # Body binding is validated before employee existence, so a random
        # (well-formed) employee UUID is enough to exercise the 400 path.
        response = _send(request.node, "POST", f"{base_url}/employees/{_rand_id()}/jurisdictions",
                         headers=auth_headers, json_body=make_invalid_jurisdiction(strategy))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_for_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees/{_rand_id()}/jurisdictions",
                         headers=auth_headers, json_body=make_jurisdiction_create())
        if response.status_code == 400:
            pytest.skip("Boundary service enabled and rejected the sample codes before the FK check")
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_nonexistent_jurisdiction_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/employees/{_rand_id()}/jurisdictions/{_rand_id()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_jurisdiction_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT",
                         f"{base_url}/employees/{_rand_id()}/jurisdictions/{_rand_id()}",
                         headers=auth_headers, json_body=make_jurisdiction_update(version=1))
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
