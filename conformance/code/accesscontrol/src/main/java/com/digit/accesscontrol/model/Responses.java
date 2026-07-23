package com.digit.accesscontrol.model;

import java.util.List;

/** Response + bulk request envelopes. Mirror the Go *Response / BulkCreate* structs. */
public final class Responses {
    private Responses() {}

    public static class RbacRuleListResponse {
        public List<Rule> rules;
        public int limit;
        public int offset;
        public int total;
        public RbacRuleListResponse() {}
        public RbacRuleListResponse(List<Rule> rules, int limit, int offset, int total) {
            this.rules = rules; this.limit = limit; this.offset = offset; this.total = total;
        }
        public List<Rule> getRules() { return rules; }
        public int getLimit() { return limit; }
        public int getOffset() { return offset; }
        public int getTotal() { return total; }
    }

    public static class RbacRuleResponse {
        public Rule rule;
        public RbacRuleResponse(Rule rule) { this.rule = rule; }
        public Rule getRule() { return rule; }
    }

    public static class JbacRuleListResponse {
        public List<JbacRule> rules;
        public int limit;
        public int offset;
        public int total;
        public JbacRuleListResponse() {}
        public JbacRuleListResponse(List<JbacRule> rules, int limit, int offset, int total) {
            this.rules = rules; this.limit = limit; this.offset = offset; this.total = total;
        }
        public List<JbacRule> getRules() { return rules; }
        public int getLimit() { return limit; }
        public int getOffset() { return offset; }
        public int getTotal() { return total; }
    }

    public static class JbacRuleResponse {
        public JbacRule rule;
        public JbacRuleResponse(JbacRule rule) { this.rule = rule; }
        public JbacRule getRule() { return rule; }
    }

    public static class BulkCreateRbacRulesRequest {
        public List<CreateRbacRuleRequest> rules;
        public List<CreateRbacRuleRequest> getRules() { return rules; }
        public void setRules(List<CreateRbacRuleRequest> rules) { this.rules = rules; }
    }

    public static class BulkCreateRbacRulesResponse {
        public int created;
        public BulkCreateRbacRulesResponse(int created) { this.created = created; }
        public int getCreated() { return created; }
    }

    public static class BulkCreateJbacRulesRequest {
        public List<CreateJbacRuleRequest> rules;
        public List<CreateJbacRuleRequest> getRules() { return rules; }
        public void setRules(List<CreateJbacRuleRequest> rules) { this.rules = rules; }
    }

    public static class BulkCreateJbacRulesResponse {
        public int created;
        public BulkCreateJbacRulesResponse(int created) { this.created = created; }
        public int getCreated() { return created; }
    }
}
