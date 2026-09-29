package com.digit.employee.service;

import com.digit.employee.client.BoundaryClient;
import com.digit.employee.client.IdGenClient;
import com.digit.employee.client.IndividualClient;
import com.digit.employee.client.KeycloakClient;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.Employee;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.OnboardRequest;
import com.digit.employee.model.OnboardUser;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.pubsub.EventPublisher;
import com.digit.employee.repository.EmployeeRepository;
import com.digit.employee.repository.JurisdictionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * A write transaction holds a pooled connection from start to commit, so it must wrap SQL only:
 * every downstream call (Keycloak, individual, idgen, boundary) and every event publish has to run
 * while no transaction is open. The transaction here records when it is open; downstream mocks fail
 * the test if they are reached inside it, and repository writes fail it if they are reached outside.
 */
class EmployeeTransactionScopeTest {

    private final AtomicBoolean inTx = new AtomicBoolean();
    private final AtomicInteger txCount = new AtomicInteger();
    private final TransactionOperations tx = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            txCount.incrementAndGet();
            inTx.set(true);
            try {
                return action.doInTransaction(null);
            } finally {
                inTx.set(false);
            }
        }
    };

    private EmployeeRepository repo;
    private JurisdictionRepository jRepo;
    private KeycloakClient keycloak;
    private IndividualClient individual;
    private IdGenClient idgen;
    private BoundaryClient boundary;
    private EventPublisher events;
    private EmployeeService svc;

    private <T> Answer<T> outsideTx(T value) {
        return inv -> {
            assertFalse(inTx.get(), inv.getMethod().getName() + " ran inside the write transaction");
            return value;
        };
    }

    private Answer<Object> insideTx() {
        return inv -> {
            assertTrue(inTx.get(), inv.getMethod().getName() + " ran outside the write transaction");
            return null;
        };
    }

    private static BoundaryRef ref(String code) {
        BoundaryRef r = new BoundaryRef();
        r.setCode(code);
        r.setBoundaryType("CITY");
        r.setHierarchyType("ADMIN");
        return r;
    }

    private static Jurisdiction jurisdiction(String id, int version) {
        Jurisdiction j = new Jurisdiction();
        j.setId(id);
        j.setEmployeeId(id == null ? null : "e1");
        j.setVersion(version);
        j.setBoundaryRelation(List.of(ref("B1")));
        return j;
    }

    private static Employee employee(int version) {
        Employee e = new Employee();
        e.setId("e1");
        e.setVersion(version);
        return e;
    }

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(EmployeeRepository.class);
        jRepo = Mockito.mock(JurisdictionRepository.class);
        keycloak = Mockito.mock(KeycloakClient.class);
        individual = Mockito.mock(IndividualClient.class);
        idgen = Mockito.mock(IdGenClient.class);
        boundary = Mockito.mock(BoundaryClient.class);
        events = Mockito.mock(EventPublisher.class);

        EmployeeProperties props = new EmployeeProperties();
        props.getKeycloak().setEnabled(true);
        props.getIndividual().setEnabled(true);
        props.getBoundary().setEnabled(true);
        BusinessMetrics metrics = Mockito.mock(BusinessMetrics.class);
        JurisdictionService js = new JurisdictionService(jRepo, boundary, props, events, metrics);
        svc = new EmployeeService(repo, js, idgen, individual, keycloak, props, events, metrics, tx);

        Mockito.when(keycloak.getUserByID(anyString(), anyString(), any())).then(outsideTx("kc-1"));
        Mockito.when(individual.getIndividualByID(anyString(), anyString())).then(outsideTx("ind-1"));
        Mockito.when(idgen.generateIDs(anyString(), anyInt(), any())).then(outsideTx(List.of("EMP-1")));
        Mockito.when(boundary.searchRelationship(anyString(), anyString(), anyString(), any()))
                .then(outsideTx(Set.of("B1")));
        Mockito.doAnswer(outsideTx(null)).when(events).publishEvent(any(), any(), any(), any(), any(), anyInt());

        Mockito.doAnswer(inv -> {
            assertTrue(inTx.get(), "employee insert ran outside the write transaction");
            Employee e = inv.getArgument(0);
            e.setId("e1");
            return e;
        }).when(repo).create(any());
        Mockito.doAnswer(insideTx()).when(repo).update(any(), anyInt());
        Mockito.doAnswer(insideTx()).when(jRepo).create(any());
        Mockito.doAnswer(insideTx()).when(jRepo).update(any(), anyInt());
        Mockito.doAnswer(insideTx()).when(jRepo).deactivateOmitted(any(), any(), any(), any());
        Mockito.when(repo.findByUUID("e1", "t1")).thenReturn(employee(1));
    }

    private static CreateEmployeeRequest createRequest() {
        CreateEmployeeRequest r = new CreateEmployeeRequest();
        r.setEmployeeType("PERMANENT");
        r.setDepartment("D");
        r.setDesignation("DE");
        r.setUserId("kc-1");
        r.setIndividualId("ind-1");
        r.setJurisdictions(List.of(jurisdiction(null, 0)));
        return r;
    }

    @Test
    void create_callsEveryDownstreamOutsideTheTransaction() {
        svc.createEmployees(List.of(createRequest()), "t1", "Bearer x", "u1");

        assertEquals(1, txCount.get());
        Mockito.verify(keycloak).getUserByID(anyString(), anyString(), any());
        Mockito.verify(individual).getIndividualByID(anyString(), anyString());
        Mockito.verify(idgen).generateIDs(anyString(), anyInt(), any());
        Mockito.verify(boundary).searchRelationship(anyString(), anyString(), anyString(), any());
        Mockito.verify(jRepo).create(any());
        // The insert returns the stored row, so nothing is read back per employee.
        Mockito.verify(repo, Mockito.never()).findByUUID(any(), any());
        // One jurisdiction CREATE and one employee CREATE, both after commit.
        Mockito.verify(events, Mockito.times(2)).publishEvent(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void create_failedInsert_publishesNothing() {
        Mockito.doThrow(new CustomException("EMPLOYEE_EXISTS", "Employee code already exists"))
                .when(repo).create(any());

        assertThrows(CustomException.class,
                () -> svc.createEmployees(List.of(createRequest()), "t1", "Bearer x", "u1"));

        Mockito.verify(events, Mockito.never()).publishEvent(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void put_validatesJurisdictionsOutsideTheTransaction() {
        Mockito.when(jRepo.findByEmployeeIds("t1", List.of("e1"))).thenReturn(List.of(jurisdiction("j1", 1)));
        UpdateEmployeeRequest req = new UpdateEmployeeRequest();
        req.setEmployeeType("PERMANENT");
        req.setDepartment("D");
        req.setDesignation("DE");
        req.setStatus("ACTIVE");
        req.setIsActive(true);
        req.setVersion(1);
        req.setJurisdictions(List.of(jurisdiction("j1", 1), jurisdiction(null, 0)));

        svc.updateEmployee("e1", req, "t1", "u1");

        assertEquals(1, txCount.get());
        // One lookup per jurisdiction: the in-place update and the insert.
        Mockito.verify(boundary, Mockito.times(2)).searchRelationship(anyString(), anyString(), anyString(), any());
        Mockito.verify(repo).update(any(), Mockito.eq(1));
        Mockito.verify(jRepo).update(any(), Mockito.eq(1));
        Mockito.verify(jRepo).create(any());
        Mockito.verify(jRepo).deactivateOmitted(any(), any(), any(), any());
    }

    @Test
    void onboard_holdsNoTransactionAcrossKeycloakOrIndividualCalls() {
        ObjectMapper om = new ObjectMapper();
        Mockito.when(keycloak.getRealmRole(anyString(), anyString(), any()))
                .then(outsideTx(om.createObjectNode().put("name", "EMPLOYEE")));
        Mockito.when(keycloak.createUser(anyString(), any(), any())).then(outsideTx("kc-1"));
        Mockito.doAnswer(outsideTx(null)).when(keycloak).assignRealmRoles(any(), any(), any(), any());
        Mockito.when(individual.createIndividual(any(), any(), any(), any()))
                .then(outsideTx(Map.<String, Object>of("id", "ind-1")));

        OnboardUser user = new OnboardUser();
        user.setMobileNumber("9999999999");
        user.setPassword("secret");
        user.setRoles(List.of("EMPLOYEE"));
        OnboardRequest req = new OnboardRequest();
        req.setUser(user);
        req.setIndividual(om.createObjectNode().put("name", "A"));
        req.setEmployee(createRequest());

        svc.onboardEmployee(req, "t1", "Bearer x", "u1");

        // Only createEmployees' insert opens a transaction; onboard itself never does.
        assertEquals(1, txCount.get());
        Mockito.verify(keycloak).createUser(anyString(), any(), any());
        Mockito.verify(individual).createIndividual(any(), any(), any(), any());
    }
}
