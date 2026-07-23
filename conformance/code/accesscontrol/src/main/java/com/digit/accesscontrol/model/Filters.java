package com.digit.accesscontrol.model;

/** Query-parameter filter holders. Mirror the Go *Filter structs. */
public final class Filters {
    private Filters() {}

    /** RBAC list filters (tenant-scoped). limit 0-100, offset 0-10000. */
    public static class RbacRulesFilter {
        public String roleName;
        public String httpMethod;
        public String effect;
        public Boolean enabled;
        public int limit;
        public int offset;
    }

    /** JBAC list filters (tenant-scoped). limit 0-100, offset 0-10000. */
    public static class JbacRulesFilter {
        public String name;
        public String enforcement;
        public int limit;
        public int offset;
    }

    /** Internal cross-tenant list filters. limit 0-1000, offset 0-100000. */
    public static class AllRulesFilter {
        public int limit;
        public int offset;
    }
}
