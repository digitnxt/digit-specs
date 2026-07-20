package com.digit.individual.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Search response. Mirrors Go internal/models/request_response.go IndividualSearchResponse. */
@JsonPropertyOrder({"totalCount", "page", "size", "hasMore", "individuals"})
public class IndividualSearchResponse {
    private long totalCount;
    private int page;
    private int size;
    private boolean hasMore;
    private List<IndividualDTO> individuals;

    public IndividualSearchResponse(long totalCount, int page, int size, boolean hasMore, List<IndividualDTO> individuals) {
        this.totalCount = totalCount;
        this.page = page;
        this.size = size;
        this.hasMore = hasMore;
        this.individuals = individuals;
    }

    public long getTotalCount() { return totalCount; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public boolean isHasMore() { return hasMore; }
    public List<IndividualDTO> getIndividuals() { return individuals; }
}
