package com.digit.employee.service;

import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.repository.JurisdictionRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Slice A: nested/owned jurisdiction semantics — duplicate-boundary detection + ownership 404. */
class JurisdictionServiceTest {

    private static BoundaryRef ref(String code, String bt, String ht) {
        BoundaryRef r = new BoundaryRef();
        r.setCode(code);
        r.setBoundaryType(bt);
        r.setHierarchyType(ht);
        return r;
    }

    @Test
    void duplicateBoundaryRelation_isRejected() {
        List<BoundaryRef> dup = List.of(ref("B1", "CITY", "ADMIN"), ref("B1", "CITY", "ADMIN"));
        assertThrows(CustomException.class,
                () -> JurisdictionService.checkDuplicateBoundaryRelations(dup));
    }

    @Test
    void distinctBoundaryRelations_pass() {
        List<BoundaryRef> ok = List.of(ref("B1", "CITY", "ADMIN"), ref("B2", "CITY", "ADMIN"));
        assertDoesNotThrow(() -> JurisdictionService.checkDuplicateBoundaryRelations(ok));
    }

    @Test
    void getByUuid_wrongOwner_is404() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Jurisdiction j = new Jurisdiction();
        j.setId("j1");
        j.setEmployeeId("EMP-A");
        Mockito.when(repo.findByUUID("j1", "t1")).thenReturn(j);

        JurisdictionService svc = new JurisdictionService(repo, null, null, null, null);

        CustomException ex = assertThrows(CustomException.class,
                () -> svc.getJurisdictionByUUID("EMP-B", "j1", "t1"));
        assertEquals("NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getHttpStatus());
    }

    @Test
    void getByUuid_correctOwner_returns() {
        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Jurisdiction j = new Jurisdiction();
        j.setId("j1");
        j.setEmployeeId("EMP-A");
        Mockito.when(repo.findByUUID("j1", "t1")).thenReturn(j);

        JurisdictionService svc = new JurisdictionService(repo, null, null, null, null);

        assertDoesNotThrow(() -> svc.getJurisdictionByUUID("EMP-A", "j1", "t1"));
    }
}
