package com.digit.employee.config;

import com.digit.employee.model.AuditDetails;
import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.CreateJurisdictionRequest;
import com.digit.employee.model.Employee;
import com.digit.employee.model.EmployeeResponse;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.JurisdictionResponse;
import com.digit.employee.model.JurisdictionSearchCriteria;
import com.digit.employee.model.PatchEmployeeRequest;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.model.UpdateJurisdictionRequest;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * GraalVM native-image binding hints for employee's DTOs and jsonb model types (employee,
 * jurisdiction, boundaryRelation references, activation/deactivation details). These are needed
 * because the controllers parse request bodies manually via ObjectMapper (from {@code byte[]}),
 * so Spring's AOT engine cannot infer the bound types from controller signatures. Cross-cutting
 * hints (pub/sub, Flyway migrations, tenant event) come from the org.digit:tracer / tenant-migration-java
 * libraries' aot.factories. Only consulted during native AOT; harmless on the JVM.
 */
@Configuration
@RegisterReflectionForBinding({
        Employee.class,
        EmployeeResponse.class,
        EmployeeSearchCriteria.class,
        CreateEmployeeRequest.class,
        UpdateEmployeeRequest.class,
        PatchEmployeeRequest.class,
        Jurisdiction.class,
        JurisdictionResponse.class,
        JurisdictionSearchCriteria.class,
        CreateJurisdictionRequest.class,
        UpdateJurisdictionRequest.class,
        BoundaryRef.class,
        AuditDetails.class
})
public class NativeHintsConfig {
}
