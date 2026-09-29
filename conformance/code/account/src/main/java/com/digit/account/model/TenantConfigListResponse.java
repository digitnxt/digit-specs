package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Paginated envelope for {@code GET /config}. Mirrors Go models.TenantConfigListResponse. */
@JsonPropertyOrder({"totalCount", "page", "size", "hasMore", "configs"})
public class TenantConfigListResponse {
    private int totalCount;
    private int page;
    private int size;
    private boolean hasMore;
    private List<TenantConfigResponse> configs;

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public List<TenantConfigResponse> getConfigs() { return configs; }
    public void setConfigs(List<TenantConfigResponse> configs) { this.configs = configs; }
}
