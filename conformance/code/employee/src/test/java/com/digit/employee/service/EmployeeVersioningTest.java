package com.digit.employee.service;

import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.CreateJurisdictionRequest;
import com.digit.employee.model.Employee;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.JurisdictionSearchCriteria;
import com.digit.employee.model.PatchEmployeeRequest;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.model.UpdateJurisdictionRequest;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.repository.JurisdictionRepository;
import com.digit.employee.repository.EmployeeRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Optimistic-versioning + reconcile semantics (VERSIONING-DESIGN.md). Covers the service-level
 * fast-fail version checks (409/400) and the jurisdiction reconcile validation branches. The
 * compare-and-swap itself (0 rows → 409) is a repository concern that needs a DB and is not covered
 * here.
 */
class EmployeeVersioningTest {

    private static BoundaryRef ref(String code) {
        BoundaryRef r = new BoundaryRef();
        r.setCode(code);
        r.setBoundaryType("CITY");
        r.setHierarchyType("ADMIN");
        return r;
    }

    private static UpdateEmployeeRequest fullPutBody() {
        UpdateEmployeeRequest req = new UpdateEmployeeRequest();
        req.setEmployeeType("PERMANENT");
        req.setDepartment("REV");
        req.setDesignation("CLERK");
        req.setStatus("ACTIVE");
        req.setIsActive(true);
        req.setJurisdictions(List.of());
        return req;
    }

    @Test
    void updateEmployee_staleVersion_is409() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        Employee existing = new Employee();
        existing.setId("e1");
        existing.setVersion(5);
        Mockito.when(repo.findByUUID("e1", "t1")).thenReturn(existing);
        EmployeeService svc = new EmployeeService(repo, null, null, null, null, new EmployeeProperties(), null, null);

        UpdateEmployeeRequest req = fullPutBody();
        req.setVersion(3); // stale

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.updateEmployee("e1", req, "t1", "u1"));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        Mockito.verify(repo, Mockito.never()).update(Mockito.any(), Mockito.anyInt());
    }

    @Test
    void updateEmployee_missingVersion_is400() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        Employee existing = new Employee();
        existing.setVersion(5);
        Mockito.when(repo.findByUUID("e1", "t1")).thenReturn(existing);
        EmployeeService svc = new EmployeeService(repo, null, null, null, null, new EmployeeProperties(), null, null);

        UpdateEmployeeRequest req = fullPutBody(); // version left null

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.updateEmployee("e1", req, "t1", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    void patchEmployee_staleVersion_is409() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        Employee existing = new Employee();
        existing.setVersion(5);
        Mockito.when(repo.findByUUID("e1", "t1")).thenReturn(existing);
        EmployeeService svc = new EmployeeService(repo, null, null, null, null, new EmployeeProperties(), null, null);

        PatchEmployeeRequest req = new PatchEmployeeRequest();
        req.setStatus("ACTIVE");
        req.setVersion(3); // stale

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.patchEmployee("e1", req, "t1", "u1"));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        Mockito.verify(repo, Mockito.never()).patch(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt());
    }

    @Test
    void updateJurisdiction_staleVersion_is409() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Jurisdiction j = new Jurisdiction();
        j.setId("j1");
        j.setEmployeeId("EMP-A");
        j.setVersion(5);
        Mockito.when(repo.findByUUID("j1", "t1")).thenReturn(j);
        JurisdictionService svc = new JurisdictionService(repo, null, new EmployeeProperties(), null, Mockito.mock(BusinessMetrics.class));

        UpdateJurisdictionRequest req = new UpdateJurisdictionRequest();
        req.setBoundaryRelation(List.of(ref("B1")));
        req.setVersion(3); // stale

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.updateJurisdiction("EMP-A", "j1", req, "t1", "u1"));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        Mockito.verify(repo, Mockito.never()).update(Mockito.any(), Mockito.anyInt());
    }

    @Test
    void updateJurisdiction_missingVersion_is400() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Jurisdiction j = new Jurisdiction();
        j.setId("j1");
        j.setEmployeeId("EMP-A");
        j.setVersion(5);
        Mockito.when(repo.findByUUID("j1", "t1")).thenReturn(j);
        JurisdictionService svc = new JurisdictionService(repo, null, new EmployeeProperties(), null, Mockito.mock(BusinessMetrics.class));

        UpdateJurisdictionRequest req = new UpdateJurisdictionRequest();
        req.setBoundaryRelation(List.of(ref("B1"))); // version null

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.updateJurisdiction("EMP-A", "j1", req, "t1", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    void reconcile_idWithoutVersion_is400() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Jurisdiction existing = new Jurisdiction();
        existing.setId("j1");
        existing.setEmployeeId("EMP-A");
        existing.setVersion(2);
        Mockito.when(repo.search(Mockito.eq("t1"), Mockito.eq("EMP-A"), Mockito.any(JurisdictionSearchCriteria.class)))
                .thenReturn(List.of(existing));
        JurisdictionService svc = new JurisdictionService(repo, null, new EmployeeProperties(), null, Mockito.mock(BusinessMetrics.class));

        Jurisdiction item = new Jurisdiction();
        item.setId("j1"); // owned, but no version supplied

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.reconcileJurisdictions("EMP-A", List.of(item), "t1", "u1"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    @Test
    void reconcile_foreignId_is404() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Jurisdiction existing = new Jurisdiction();
        existing.setId("j1");
        existing.setEmployeeId("EMP-A");
        Mockito.when(repo.search(Mockito.eq("t1"), Mockito.eq("EMP-A"), Mockito.any(JurisdictionSearchCriteria.class)))
                .thenReturn(List.of(existing));
        JurisdictionService svc = new JurisdictionService(repo, null, new EmployeeProperties(), null, Mockito.mock(BusinessMetrics.class));

        Jurisdiction item = new Jurisdiction();
        item.setId("j9"); // not owned by EMP-A
        item.setVersion(1);

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.reconcileJurisdictions("EMP-A", List.of(item), "t1", "u1"));
        assertEquals("NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getHttpStatus());
    }

    @Test
    void reconcile_emptyArray_deactivatesAll() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Mockito.when(repo.search(Mockito.eq("t1"), Mockito.eq("EMP-A"), Mockito.any(JurisdictionSearchCriteria.class)))
                .thenReturn(List.of());
        JurisdictionService svc = new JurisdictionService(repo, null, new EmployeeProperties(), null, Mockito.mock(BusinessMetrics.class));

        svc.reconcileJurisdictions("EMP-A", List.of(), "t1", "u1");

        // Empty keep-list → deactivate every active jurisdiction of the employee.
        Mockito.verify(repo).deactivateOmitted("EMP-A", "t1", "u1", List.of());
    }

    @Test
    void reconcile_idLessInsert_isKeptNotDeactivated() {
        // Regression: a newly-inserted (id-less) jurisdiction must be added to the keep-set, else the
        // deactivate-omitted sweep immediately deactivates it.
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Mockito.when(repo.search(Mockito.eq("t1"), Mockito.eq("EMP-A"), Mockito.any(JurisdictionSearchCriteria.class)))
                .thenReturn(List.of()); // no existing jurisdictions
        EmployeeProperties props = new EmployeeProperties();
        props.getBoundary().setEnabled(false); // skip boundary validation
        JurisdictionService svc = new JurisdictionService(repo, null, props,
                Mockito.mock(com.digit.employee.pubsub.EventPublisher.class), Mockito.mock(BusinessMetrics.class));

        Jurisdiction item = new Jurisdiction();
        item.setBoundaryRelation(List.of(ref("B1"))); // id-less → insert

        svc.reconcileJurisdictions("EMP-A", List.of(item), "t1", "u1");

        // The freshly-created id must be in the keep-list passed to deactivateOmitted.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keep = ArgumentCaptor.forClass(List.class);
        Mockito.verify(repo).deactivateOmitted(Mockito.eq("EMP-A"), Mockito.eq("t1"), Mockito.eq("u1"), keep.capture());
        assertEquals(1, keep.getValue().size());
    }

    @Test
    void createJurisdiction_setsVersion1() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        EmployeeProperties props = new EmployeeProperties();
        props.getBoundary().setEnabled(false); // skip boundary validation
        BusinessMetrics metrics = Mockito.mock(BusinessMetrics.class);
        JurisdictionService svc = new JurisdictionService(repo, null, props, Mockito.mock(com.digit.employee.pubsub.EventPublisher.class), metrics);

        CreateJurisdictionRequest req = new CreateJurisdictionRequest();
        req.setBoundaryRelation(List.of(ref("B1")));

        svc.createJurisdiction("EMP-A", req, "t1", "u1");

        ArgumentCaptor<Jurisdiction> captor = ArgumentCaptor.forClass(Jurisdiction.class);
        Mockito.verify(repo).create(captor.capture());
        assertEquals(1, captor.getValue().getVersion());
    }
}
