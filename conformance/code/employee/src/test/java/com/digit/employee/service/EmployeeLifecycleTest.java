package com.digit.employee.service;

import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.Employee;
import com.digit.employee.repository.EmployeeRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Slice D: deactivate/reactivate state-transition 409s + create batch guards. */
class EmployeeLifecycleTest {

    private EmployeeService svc(EmployeeRepository repo) {
        return new EmployeeService(repo, null, null, null, null, new EmployeeProperties(), null, null);
    }

    @Test
    void deactivate_alreadyInactive_is409() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        Employee e = new Employee();
        e.setActive(false);
        Mockito.when(repo.findByUUID("id", "t1")).thenReturn(e);

        CustomException ex = assertThrows(CustomException.class,
                () -> svc(repo).deactivateEmployee("id", "t1", "u1"));
        assertEquals("EMPLOYEE_ALREADY_INACTIVE", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
    }

    @Test
    void reactivate_alreadyActive_is409() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        Employee e = new Employee();
        e.setActive(true);
        Mockito.when(repo.findByUUID("id", "t1")).thenReturn(e);

        CustomException ex = assertThrows(CustomException.class,
                () -> svc(repo).reactivateEmployee("id", "t1", "u1"));
        assertEquals("EMPLOYEE_ALREADY_ACTIVE", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
    }

    @Test
    void create_emptyBatch_is400() {
        CustomException ex = assertThrows(CustomException.class,
                () -> svc(null).createEmployees(List.of(), "t1", "Bearer x", "u1"));
        assertEquals("INVALID_REQUEST", ex.getCode());
    }

    @Test
    void create_missingRequiredField_is400() {
        com.digit.employee.model.CreateEmployeeRequest r = new com.digit.employee.model.CreateEmployeeRequest();
        r.setDepartment("D");
        r.setDesignation("DE"); // employeeType missing
        CustomException ex = assertThrows(CustomException.class,
                () -> svc(null).createEmployees(List.of(r), "t1", "Bearer x", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    void create_jurisdictionFailure_propagates_noSwallow() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        JurisdictionService js = Mockito.mock(JurisdictionService.class);
        com.digit.employee.observability.BusinessMetrics m =
                Mockito.mock(com.digit.employee.observability.BusinessMetrics.class);
        EmployeeService svc = new EmployeeService(repo, js, null, null, null, new EmployeeProperties(), null, m);
        Mockito.when(js.createJurisdiction(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new CustomException("VALIDATION_ERROR", "bad boundary"));

        com.digit.employee.model.CreateEmployeeRequest r = new com.digit.employee.model.CreateEmployeeRequest();
        r.setCode("C1"); // set so idgen isn't called
        r.setEmployeeType("PERMANENT");
        r.setDepartment("D");
        r.setDesignation("DE");
        r.setJurisdictions(List.of(new com.digit.employee.model.Jurisdiction()));

        // Jurisdiction failure must propagate (so @Transactional rolls back) — not be swallowed.
        assertThrows(CustomException.class,
                () -> svc.createEmployees(List.of(r), "t1", "Bearer x", "u1"));
    }

    @Test
    void create_overLengthCode_is400() {
        com.digit.employee.model.CreateEmployeeRequest r = new com.digit.employee.model.CreateEmployeeRequest();
        r.setEmployeeType("PERMANENT");
        r.setDepartment("D");
        r.setDesignation("DE");
        r.setCode("x".repeat(65)); // > 64
        CustomException ex = assertThrows(CustomException.class,
                () -> svc(null).createEmployees(List.of(r), "t1", "Bearer x", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }
}
