package com.digit.employee.service;

import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.Employee;
import com.digit.employee.model.PatchEmployeeRequest;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.repository.EmployeeRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice C: PATCH partial semantics (empty-body 400) and strict PUT required-field validation. */
class EmployeePatchPutTest {

    @Test
    void patchRequest_hasAnyField() {
        assertFalse(new PatchEmployeeRequest().hasAnyField());
        PatchEmployeeRequest r = new PatchEmployeeRequest();
        r.setStatus("ACTIVE");
        assertTrue(r.hasAnyField());
    }

    @Test
    void patch_emptyBody_is400() {
        EmployeeService svc = new EmployeeService(null, null, null, null, null, new EmployeeProperties(), null, null);
        CustomException ex = assertThrows(CustomException.class,
                () -> svc.patchEmployee("id", new PatchEmployeeRequest(), "t1", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    void put_missingRequiredField_is400() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        Mockito.when(repo.findByUUID("id", "t1")).thenReturn(new Employee());
        EmployeeService svc = new EmployeeService(repo, null, null, null, null, new EmployeeProperties(), null, null);

        UpdateEmployeeRequest req = new UpdateEmployeeRequest();
        req.setEmployeeType("PERMANENT"); // department/designation/status missing
        req.setIsActive(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.updateEmployee("id", req, "t1", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }
}
