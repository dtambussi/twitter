package com.twitter.infrastructure.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * A DataSource that routes to the appropriate shard based on ShardContext.
 * Uses Spring's AbstractRoutingDataSource for transparent connection routing.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        Integer shardId = ShardContext.get();
        // Default to shard 0 if no context set (e.g., during startup, migrations)
        return shardId != null ? shardId : 0;
    }
}
