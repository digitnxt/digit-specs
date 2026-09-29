package com.digit.employee.service;

import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.Employee;
import com.digit.employee.model.EmployeeResponse;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.pubsub.EventPublisher;
import com.digit.employee.repository.EmployeeRepository;
import com.digit.employee.repository.JurisdictionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embedded jurisdictions: one query per page rather than per employee, the whole collection rather
 * than a page of it, and a failed load surfaces instead of rendering as "no jurisdictions".
 */
class EmployeeJurisdictionLoadingTest {

    private static Employee employee(String id) {
        Employee e = new Employee();
        e.setId(id);
        return e;
    }

    private static Jurisdiction jurisdiction(String id, String employeeId) {
        Jurisdiction j = new Jurisdiction();
        j.setId(id);
        j.setEmployeeId(employeeId);
        j.setVersion(1);
        BoundaryRef r = new BoundaryRef();
        r.setCode("B-" + id);
        r.setBoundaryType("CITY");
        r.setHierarchyType("ADMIN");
        j.setBoundaryRelation(List.of(r));
        return j;
    }

    private static JurisdictionService jurisdictionService(JurisdictionRepository repo) {
        EmployeeProperties props = new EmployeeProperties();
        props.getBoundary().setEnabled(false);
        return new JurisdictionService(repo, null, props, Mockito.mock(EventPublisher.class),
                Mockito.mock(BusinessMetrics.class));
    }

    private static EmployeeService employeeService(EmployeeRepository repo, JurisdictionService js) {
        return new EmployeeService(repo, js, null, null, null, new EmployeeProperties(), null,
                Mockito.mock(BusinessMetrics.class), TransactionOperations.withoutTransaction());
    }

    @Test
    void search_loadsJurisdictionsForWholePageInOneQuery() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        JurisdictionRepository jRepo = Mockito.mock(JurisdictionRepository.class);
        Mockito.when(repo.search(Mockito.any())).thenReturn(List.of(employee("e1"), employee("e2"), employee("e3")));
        Mockito.when(jRepo.findByEmployeeIds("t1", List.of("e1", "e2", "e3")))
                .thenReturn(List.of(jurisdiction("j1", "e1"), jurisdiction("j2", "e3"), jurisdiction("j3", "e1")));

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        List<EmployeeResponse> result = employeeService(repo, jurisdictionService(jRepo)).searchEmployees(c, null);

        Mockito.verify(jRepo, Mockito.times(1)).findByEmployeeIds(Mockito.any(), Mockito.any());
        Mockito.verify(jRepo, Mockito.never()).search(Mockito.any(), Mockito.any(), Mockito.any());
        assertEquals(List.of("j1", "j3"), result.get(0).getJurisdictions().stream().map(j -> j.getId()).toList());
        assertTrue(result.get(1).getJurisdictions().isEmpty());
        assertEquals(List.of("j2"), result.get(2).getJurisdictions().stream().map(j -> j.getId()).toList());
    }

    @Test
    void getById_returnsEveryJurisdiction_notJustTen() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        JurisdictionRepository jRepo = Mockito.mock(JurisdictionRepository.class);
        Mockito.when(repo.findByUUID("e1", "t1")).thenReturn(employee("e1"));
        List<Jurisdiction> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(jurisdiction("j" + i, "e1"));
        }
        Mockito.when(jRepo.findByEmployeeIds("t1", List.of("e1"))).thenReturn(twelve);

        EmployeeResponse r = employeeService(repo, jurisdictionService(jRepo)).getEmployeeByUUID("e1", "t1");

        assertEquals(12, r.getJurisdictions().size());
    }

    @Test
    void reconcile_acceptsOwnedJurisdictionBeyondTheTenth() {
        JurisdictionRepository jRepo = Mockito.mock(JurisdictionRepository.class);
        List<Jurisdiction> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(jurisdiction("j" + i, "EMP-A"));
        }
        Mockito.when(jRepo.findByEmployeeIds("t1", List.of("EMP-A"))).thenReturn(twelve);

        // j11 is the 12th (oldest) row — outside a first page of 10, but still owned by EMP-A.
        JurisdictionService js = jurisdictionService(jRepo);
        js.applyReconcile("EMP-A", js.planReconcile("EMP-A", List.of(jurisdiction("j11", null)), "t1"), "t1", "u1");

        Mockito.verify(jRepo).update(Mockito.argThat(j -> "j11".equals(j.getId())), Mockito.eq(1));
    }

    @Test
    void search_jurisdictionLoadFailure_propagates() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        JurisdictionRepository jRepo = Mockito.mock(JurisdictionRepository.class);
        Mockito.when(repo.search(Mockito.any())).thenReturn(List.of(employee("e1")));
        Mockito.when(jRepo.findByEmployeeIds(Mockito.any(), Mockito.any()))
                .thenThrow(new QueryTimeoutException("pool exhausted"));

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        EmployeeService svc = employeeService(repo, jurisdictionService(jRepo));

        assertThrows(QueryTimeoutException.class, () -> svc.searchEmployees(c, null));
    }
}