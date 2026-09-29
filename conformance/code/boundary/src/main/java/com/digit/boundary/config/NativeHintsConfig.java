package com.digit.boundary.config;

import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryHierarchyRequest;
import com.digit.boundary.model.BoundaryHierarchyResponse;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryRelationshipRequest;
import com.digit.boundary.model.BoundaryRelationshipResponse;
import com.digit.boundary.model.BoundaryRelationshipSearchCriteria;
import com.digit.boundary.model.BoundaryRequest;
import com.digit.boundary.model.BoundaryResponse;
import com.digit.boundary.model.BoundarySearchCriteria;
import com.digit.boundary.model.BoundarySearchResponse;
import com.digit.boundary.model.BoundaryTypeHierarchy;
import com.digit.boundary.model.EnrichedBoundary;
import com.digit.boundary.model.HierarchyRelation;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * GraalVM native-image binding hints for boundary's DTOs and jsonb model types (geometry,
 * additionalDetails, hierarchy and relationship models). These are needed because the controllers
 * parse request bodies manually via ObjectMapper (from {@code byte[]}), so Spring's AOT engine
 * cannot infer the bound types from controller signatures. Cross-cutting hints (pub/sub, Flyway
 * migrations, tenant event) come from the tracer-java / tenant-migration-java libraries'
 * aot.factories. Only consulted during native AOT; harmless on the JVM.
 */
@Configuration
@RegisterReflectionForBinding({
        Boundary.class,
        BoundaryRequest.class,
        BoundaryResponse.class,
        BoundarySearchCriteria.class,
        BoundarySearchResponse.class,
        EnrichedBoundary.class,
        BoundaryHierarchy.class,
        BoundaryHierarchyRequest.class,
        BoundaryHierarchyResponse.class,
        BoundaryHierarchySearchCriteria.class,
        BoundaryTypeHierarchy.class,
        HierarchyRelation.class,
        BoundaryRelationship.class,
        BoundaryRelationshipRequest.class,
        BoundaryRelationshipResponse.class,
        BoundaryRelationshipSearchCriteria.class,
        AuditDetails.class
})
public class NativeHintsConfig {
}
