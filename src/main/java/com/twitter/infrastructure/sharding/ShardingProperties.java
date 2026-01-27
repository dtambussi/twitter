package com.twitter.infrastructure.sharding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for database sharding.
 *
 * Example configuration for multiple shards:
 * <pre>
 * sharding:
 *   enabled: true
 *   shards:
 *     - url: jdbc:postgresql://shard0:5432/twitter
 *       username: postgres
 *       password: secret
 *     - url: jdbc:postgresql://shard1:5432/twitter
 *       username: postgres
 *       password: secret
 * </pre>
 *
 * With a single shard (default), inherits from spring.datasource.
 */
@Component
@ConfigurationProperties(prefix = "sharding")
public class ShardingProperties {

    private boolean enabled = false;
    private List<ShardDataSource> shards = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ShardDataSource> getShards() {
        return shards;
    }

    public void setShards(List<ShardDataSource> shards) {
        this.shards = shards;
    }

    /**
     * Returns the number of shards. When sharding is disabled or no shards
     * are explicitly configured, returns 1 (single default datasource).
     */
    public int getShardCount() {
        if (!enabled || shards.isEmpty()) {
            return 1;
        }
        return shards.size();
    }

    public static class ShardDataSource {
        private String url;
        private String username;
        private String password;
        private int maxPoolSize = 20;
        private int minIdle = 5;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }
    }
}
