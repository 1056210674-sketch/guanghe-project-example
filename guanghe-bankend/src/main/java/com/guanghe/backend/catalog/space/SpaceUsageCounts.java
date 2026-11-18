package com.guanghe.backend.catalog.space;

public record SpaceUsageCounts(long productCount, long competitorCount) {

    public boolean inUse() {
        return productCount > 0 || competitorCount > 0;
    }
}
