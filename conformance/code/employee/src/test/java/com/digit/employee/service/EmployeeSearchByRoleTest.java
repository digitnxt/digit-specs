package com.digit.employee.service;

import com.digit.employee.client.KeycloakClient;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice B: search-by-role resolves via Keycloak and short-circuits to empty when no member holds it;
 * search-by-userIds hits the DB directly, and the two filters intersect when both are supplied.
 */
class EmployeeSearchByRoleTest {

    private EmployeeService svc(EmployeeRepository repo, KeycloakClient kc) {
        BusinessMetrics metrics = Mockito.mock(BusinessMetrics.class);
        return new EmployeeService(repo, null, null, null, kc, new EmployeeProperties(), null, metrics, TransactionOperations.withoutTransaction());
    }

    @Test
    void roleWithNoMembers_shortCircuits_withoutHittingDb() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        KeycloakClient kc = Mockito.mock(KeycloakClient.class);
        Mockito.when(kc.getUserIDsByRole("t1", "ADMIN", "Bearer x")).thenReturn(List.of());

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        c.setRole("ADMIN");

        var result = svc(repo, kc).searchEmployees(c, "Bearer x");

        assertTrue(result.isEmpty());
        // Empty role membership must NOT fall through to an unfiltered repo scan.
        Mockito.verify(repo, Mockito.never()).search(Mockito.any());
    }

    @Test
    void roleWithMembers_setsUserIdsFilter() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        KeycloakClient kc = Mockito.mock(KeycloakClient.class);
        Mockito.when(kc.getUserIDsByRole("t1", "ADMIN", "Bearer x")).thenReturn(List.of("u1", "u2"));
        Mockito.when(repo.search(Mockito.any())).thenReturn(List.of());

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        c.setRole("ADMIN");

        svc(repo, kc).searchEmployees(c, "Bearer x");

        assertTrue(c.getUserIds() != null && c.getUserIds().containsAll(List.of("u1", "u2")));
        Mockito.verify(repo).search(Mockito.any());
    }

    @Test
    void userIdsWithoutRole_goesStraightToDb_withoutKeycloak() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        KeycloakClient kc = Mockito.mock(KeycloakClient.class);
        Mockito.when(repo.search(Mockito.any())).thenReturn(List.of());

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        c.setUserIds(List.of("u1", "u2"));

        svc(repo, kc).searchEmployees(c, null);

        assertEquals(List.of("u1", "u2"), c.getUserIds());
        Mockito.verify(repo).search(Mockito.any());
        Mockito.verifyNoInteractions(kc);
    }

    @Test
    void roleAndUserIds_intersect() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        KeycloakClient kc = Mockito.mock(KeycloakClient.class);
        Mockito.when(kc.getUserIDsByRole("t1", "ADMIN", "Bearer x")).thenReturn(List.of("u1", "u2", "u3"));
        Mockito.when(repo.search(Mockito.any())).thenReturn(List.of());

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        c.setRole("ADMIN");
        c.setUserIds(List.of("u2", "u9"));

        svc(repo, kc).searchEmployees(c, "Bearer x");

        // u2 holds the role; u9 does not; u1/u3 hold it but were not requested.
        assertEquals(List.of("u2"), c.getUserIds());
        Mockito.verify(repo).search(Mockito.any());
    }

    @Test
    void roleAndUserIds_emptyIntersection_shortCircuits_withoutHittingDb() {
        EmployeeRepository repo = Mockito.mock(EmployeeRepository.class);
        KeycloakClient kc = Mockito.mock(KeycloakClient.class);
        Mockito.when(kc.getUserIDsByRole("t1", "ADMIN", "Bearer x")).thenReturn(List.of("u1", "u2"));

        EmployeeSearchCriteria c = new EmployeeSearchCriteria();
        c.setTenantId("t1");
        c.setRole("ADMIN");
        c.setUserIds(List.of("u9"));

        var result = svc(repo, kc).searchEmployees(c, "Bearer x");

        assertTrue(result.isEmpty());
        // An empty IN list would be dropped as "no filter" and return every employee.
        Mockito.verify(repo, Mockito.never()).search(Mockito.any());
    }
}
