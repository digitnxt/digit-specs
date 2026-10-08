package com.digit.employee.service;

import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.config.JacksonConfig;
import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.repository.JurisdictionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** isActive on jurisdictions embedded in employee create/PUT bodies, parsed with the service's mapper. */
class EmbeddedJurisdictionBindingTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    private static final String BOUNDARY =
            "\"boundaryRelation\":[{\"code\":\"B1\",\"boundaryType\":\"CITY\",\"hierarchyType\":\"ADMIN\"}]";

    @Test
    void createBody_isActiveFalse_isBound() throws Exception {
        List<CreateEmployeeRequest> req = mapper.readValue(
                "[{\"code\":\"EMP-1\",\"jurisdictions\":[{\"isActive\":false," + BOUNDARY + "}]}]",
                new TypeReference<List<CreateEmployeeRequest>>() {});

        assertFalse(req.get(0).getJurisdictions().get(0).getIsActive());
    }

    @Test
    void createBody_isActiveOmitted_defaultsToTrue() throws Exception {
        List<CreateEmployeeRequest> req = mapper.readValue(
                "[{\"code\":\"EMP-1\",\"jurisdictions\":[{" + BOUNDARY + "}]}]",
                new TypeReference<List<CreateEmployeeRequest>>() {});

        assertTrue(req.get(0).getJurisdictions().get(0).getIsActive());
    }

    @Test
    void putBody_inactiveJurisdictionSentBack_staysInactive() throws Exception {
        Jurisdiction stored = new Jurisdiction();
        stored.setId("j1");
        stored.setEmployeeId("EMP-A");
        stored.setVersion(1);
        stored.setIsActive(false);
        BoundaryRef ref = new BoundaryRef();
        ref.setCode("B1");
        stored.setBoundaryRelation(List.of(ref));

        JurisdictionRepository repo = Mockito.mock(JurisdictionRepository.class);
        Mockito.when(repo.findByEmployeeIds("t1", List.of("EMP-A"))).thenReturn(List.of(stored));
        EmployeeProperties props = new EmployeeProperties();
        props.getBoundary().setEnabled(false); // skip boundary validation
        JurisdictionService svc = new JurisdictionService(repo, null, props, null, Mockito.mock(BusinessMetrics.class));

        UpdateEmployeeRequest req = mapper.readValue(
                "{\"jurisdictions\":[{\"id\":\"j1\",\"version\":1,\"isActive\":false," + BOUNDARY + "}]}",
                UpdateEmployeeRequest.class);

        svc.applyReconcile("EMP-A", svc.planReconcile("EMP-A", req.getJurisdictions(), "t1"), "t1", "u1");

        ArgumentCaptor<Jurisdiction> written = ArgumentCaptor.forClass(Jurisdiction.class);
        Mockito.verify(repo).update(written.capture(), Mockito.eq(1));
        assertFalse(written.getValue().getIsActive());
    }
}
