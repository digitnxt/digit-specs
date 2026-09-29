package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Paginated envelope for {@code GET /tenants}. Mirrors Go models.TenantListResponse. */
@JsonPropertyOrder({"totalCount", "page", "size", "hasMore", "tenants"})
public class TenantListResponse {
    private int totalCount;
    private int page;
    private int size;
    private boolean hasMore;
    private List<TenantResponse> tenants;

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public List<TenantResponse> getTenants() { return tenants; }
    public void setTenants(List<TenantResponse> tenants) { this.tenants = tenants; }
}
