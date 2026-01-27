package com.twitter.integration.base;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Base class for all integration tests.
 * Starts PostgreSQL, Redis, and Kafka/Redpanda containers.
 * 
 * All three containers are required because Spring Boot loads the full application
 * context which attempts to connect to all infrastructure components.
 * 
 * Uses lazy-initialized singleton containers - shared across all tests, only started
 * when Docker is available.
 */
@TestPropertySource(properties = {
    "app.timeline.celebrity-follower-threshold=10"
})
public abstract class FullStackTestBase {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    private static volatile boolean containersStarted = false;
    private static volatile boolean containersFailed = false;

    /**
     * Clean all data before each test.
     * Order matters due to foreign key constraints: outbox -> follows -> tweets -> users
     */
    @BeforeEach
    void cleanAllData() {
        // Clean PostgreSQL (order matters for FK constraints)
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("DELETE FROM outbox");
                jdbcTemplate.update("DELETE FROM follows");
                jdbcTemplate.update("DELETE FROM tweets");
                jdbcTemplate.update("DELETE FROM users");
            } catch (Exception e) {
                // Ignore cleanup errors - tables may not exist yet
            }
        }
        // Clean Redis
        if (stringRedisTemplate != null) {
            try {
                var connectionFactory = stringRedisTemplate.getConnectionFactory();
                if (connectionFactory != null) {
                    var connection = connectionFactory.getConnection();
                    connection.serverCommands().flushDb();
                    connection.close();
                }
            } catch (Exception e) {
                // Ignore cleanup errors - Redis may not be ready
            }
        }
    }

    public static boolean isDockerAvailable() {
        if (containersFailed) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    // Lazy initialization holder - containers only created when first accessed
    // Lifecycle managed via shutdown hook, not try-with-resources
    @SuppressWarnings("resource")
    private static class ContainerHolder {
        static final PostgreSQLContainer<?> postgres;
        static final GenericContainer<?> redis;
        static final RedpandaContainer redpanda;

        static {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(true);
            
            redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

            // topics will retain messages, just keep an eye on for test isolation issues
            redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:latest")
                    .withReuse(true);
        }
    }

    private static synchronized void startContainersIfNeeded() {
        if (containersStarted || containersFailed) {
            return;
        }
        try {
            Startables.deepStart(ContainerHolder.postgres, ContainerHolder.redis, ContainerHolder.redpanda).join();
            containersStarted = true;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                ContainerHolder.redpanda.close();
                ContainerHolder.redis.close();
                ContainerHolder.postgres.close();
            }));
        } catch (Exception e) {
            containersFailed = true;
            throw e;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!isDockerAvailable()) {
            // Provide dummy values so context can load (tests will be skipped)
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/dummy");
            registry.add("spring.datasource.username", () -> "dummy");
            registry.add("spring.datasource.password", () -> "dummy");
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> 6379);
            registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
            return;
        }
        
        try {
            startContainersIfNeeded();
        } catch (Exception e) {
            // Containers failed to start - provide dummy values
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/dummy");
            registry.add("spring.datasource.username", () -> "dummy");
            registry.add("spring.datasource.password", () -> "dummy");
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> 6379);
            registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
            return;
        }
        
        registry.add("spring.datasource.url", ContainerHolder.postgres::getJdbcUrl);
        registry.add("spring.datasource.username", ContainerHolder.postgres::getUsername);
        registry.add("spring.datasource.password", ContainerHolder.postgres::getPassword);
        registry.add("spring.data.redis.host", ContainerHolder.redis::getHost);
        registry.add("spring.data.redis.port", () -> ContainerHolder.redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", ContainerHolder.redpanda::getBootstrapServers);
    }
}
