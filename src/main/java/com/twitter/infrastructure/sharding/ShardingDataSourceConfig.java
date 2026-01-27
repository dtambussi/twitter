package com.twitter.infrastructure.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures the sharded DataSource infrastructure.
 *
 * When sharding is disabled (default), uses Spring's auto-configured DataSource
 * as the single shard. When enabled, creates a DataSource per configured shard
 * and routes connections based on ShardContext.
 */
@Configuration
public class ShardingDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ShardingDataSourceConfig.class);

    private final ShardingProperties shardingProperties;
    private final DataSourceProperties dataSourceProperties;

    public ShardingDataSourceConfig(
            ShardingProperties shardingProperties,
            DataSourceProperties dataSourceProperties) {
        this.shardingProperties = shardingProperties;
        this.dataSourceProperties = dataSourceProperties;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();

        if (shardingProperties.isEnabled() && !shardingProperties.getShards().isEmpty()) {
            // Multi-shard mode: create a DataSource for each configured shard
            List<ShardingProperties.ShardDataSource> shards = shardingProperties.getShards();
            for (int i = 0; i < shards.size(); i++) {
                ShardingProperties.ShardDataSource shardConfig = shards.get(i);
                DataSource shardDataSource = createShardDataSource(shardConfig, i);
                targetDataSources.put(i, shardDataSource);
                log.info("Configured shard {} with URL: {}", i, shardConfig.getUrl());
            }
            log.info("Sharding enabled with {} shards", shards.size());
        } else {
            // Single-shard mode: use Spring's default DataSource configuration
            DataSource defaultDataSource = createDefaultDataSource();
            targetDataSources.put(0, defaultDataSource);
            log.info("Sharding disabled - using single datasource: {}", dataSourceProperties.getUrl());
        }

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(targetDataSources.get(0));
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }

    private DataSource createShardDataSource(ShardingProperties.ShardDataSource config, int shardId) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(config.getUrl());
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword());
        dataSource.setMaximumPoolSize(config.getMaxPoolSize());
        dataSource.setMinimumIdle(config.getMinIdle());
        dataSource.setPoolName("shard-" + shardId);
        return dataSource;
    }

    private DataSource createDefaultDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(dataSourceProperties.getUrl());
        dataSource.setUsername(dataSourceProperties.getUsername());
        dataSource.setPassword(dataSourceProperties.getPassword());
        dataSource.setMaximumPoolSize(20);
        dataSource.setMinimumIdle(5);
        dataSource.setPoolName("shard-0");
        return dataSource;
    }
}
