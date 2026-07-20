package com.digit.individual.config;

import com.digit.individual.model.Address;
import com.digit.individual.model.AddressDTO;
import com.digit.individual.model.AuditDetail;
import com.digit.individual.model.Config;
import com.digit.individual.model.ConfigDTO;
import com.digit.individual.model.Document;
import com.digit.individual.model.DocumentDTO;
import com.digit.individual.model.ExistsResponse;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.IdentifierDTO;
import com.digit.individual.model.Individual;
import com.digit.individual.model.IndividualDTO;
import com.digit.individual.model.IndividualSearchResponse;
import com.digit.individual.model.SearchCriteria;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * GraalVM native-image binding hints for individual's DTOs and jsonb model types. These are needed
 * because the controllers parse request bodies manually via ObjectMapper (from {@code byte[]}),
 * so Spring's AOT engine cannot infer the bound types from controller signatures. Cross-cutting
 * hints (pub/sub, Flyway migrations, tenant event) come from the tracer-java / tenant-migration-java
 * libraries' aot.factories. Only consulted during native AOT; harmless on the JVM.
 */
@Configuration
@RegisterReflectionForBinding({
        Individual.class,
        IndividualDTO.class,
        Address.class,
        AddressDTO.class,
        Identifier.class,
        IdentifierDTO.class,
        Document.class,
        DocumentDTO.class,
        Config.class,
        ConfigDTO.class,
        SearchCriteria.class,
        ExistsResponse.class,
        IndividualSearchResponse.class,
        AuditDetail.class
})
public class NativeHintsConfig {
}
