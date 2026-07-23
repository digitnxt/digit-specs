package com.digit.accesscontrol.config;

import com.digit.accesscontrol.model.AuditDetail;
import com.digit.accesscontrol.model.CreateJbacRuleRequest;
import com.digit.accesscontrol.model.CreateRbacRuleRequest;
import com.digit.accesscontrol.model.Filters;
import com.digit.accesscontrol.model.JbacRule;
import com.digit.accesscontrol.model.Nullable;
import com.digit.accesscontrol.model.Responses;
import com.digit.accesscontrol.model.Rule;
import com.digit.accesscontrol.model.UpdateJbacRuleRequest;
import com.digit.accesscontrol.model.UpdateRbacRuleRequest;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * GraalVM native-image binding hints for accesscontrol's DTOs and jsonb model types. These are
 * needed because the controllers parse request bodies manually via ObjectMapper (from {@code byte[]}),
 * so Spring's AOT engine cannot infer the bound types from controller signatures. Cross-cutting
 * hints (pub/sub, Flyway migrations, tenant event) come from the tracer-java / tenant-migration-java
 * libraries' aot.factories. Only consulted during native AOT; harmless on the JVM.
 */
@Configuration
@RegisterReflectionForBinding({
        Rule.class,
        JbacRule.class,
        CreateRbacRuleRequest.class,
        UpdateRbacRuleRequest.class,
        CreateJbacRuleRequest.class,
        UpdateJbacRuleRequest.class,
        Filters.class,
        Filters.RbacRulesFilter.class,
        Filters.JbacRulesFilter.class,
        Filters.AllRulesFilter.class,
        Responses.class,
        Responses.RbacRuleListResponse.class,
        Responses.RbacRuleResponse.class,
        Responses.JbacRuleListResponse.class,
        Responses.JbacRuleResponse.class,
        Responses.BulkCreateRbacRulesRequest.class,
        Responses.BulkCreateRbacRulesResponse.class,
        Responses.BulkCreateJbacRulesRequest.class,
        Responses.BulkCreateJbacRulesResponse.class,
        Nullable.class,
        AuditDetail.class
})
public class NativeHintsConfig {
}
