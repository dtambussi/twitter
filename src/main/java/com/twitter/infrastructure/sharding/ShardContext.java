package com.twitter.infrastructure.sharding;

/**
 * Thread-local context for database shard routing.
 * Set at the beginning of each request based on the user's shard assignment.
 */
public final class ShardContext {

    private static final ThreadLocal<Integer> currentShard = new ThreadLocal<>();

    private ShardContext() {
    }

    public static void set(int shardId) {
        currentShard.set(shardId);
    }

    public static Integer get() {
        return currentShard.get();
    }

    public static void clear() {
        currentShard.remove();
    }
}
