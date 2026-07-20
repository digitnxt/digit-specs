package com.digit.employee.service;

import com.digit.employee.client.KeycloakClient;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice B: search-by-role resolves via Keycloak and short-circuits to empty when no member holds it. */
class EmployeeSearchByRoleTest {

    private EmployeeService svc(EmployeeRepository repo, KeycloakClient kc) {
        BusinessMetrics metrics = Mockito.mock(BusinessMetrics.class);
        return new EmployeeService(repo, null, null, null, kc, new EmployeeProperties(), null, metrics);
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
}
