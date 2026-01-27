package com.twitter.infrastructure.sharding;

import com.twitter.domain.model.UserId;
import org.springframework.stereotype.Component;

/**
 * Routes users to database shards using consistent hashing.
 * With a single shard, all users route to shard 0.
 */
@Component
public class ShardRouter {

    private final int shardCount;

    public ShardRouter(ShardingProperties shardingProperties) {
        this.shardCount = shardingProperties.getShardCount();
    }

    /**
     * Determines which shard a user belongs to.
     * Uses the UUID's hash code for consistent distribution.
     */
    public int getShardForUser(UserId userId) {
        if (shardCount == 1) {
            return 0;
        }
        // Use absolute value to handle negative hash codes
        int hash = Math.abs(userId.value().hashCode());
        return hash % shardCount;
    }

    public int getShardCount() {
        return shardCount;
    }
}
