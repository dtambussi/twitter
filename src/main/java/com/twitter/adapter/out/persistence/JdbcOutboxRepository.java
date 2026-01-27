package com.twitter.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.domain.event.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static final RowMapper<OutboxEntry> ROW_MAPPER = (rs, rowNum) -> new OutboxEntry(
        UUID.fromString(rs.getString("id")),
        rs.getString("event_type"),
        rs.getString("aggregate_id"),
        rs.getString("payload"),
        rs.getString("request_id")
    );

    public JdbcOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(DomainEvent event, String requestId) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbc.update(
                "INSERT INTO outbox (id, event_type, aggregate_id, payload, request_id, created_at) VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                event.eventId(),  // UUID object for UUID column
                event.eventType(),
                event.aggregateId(),
                payload,
                requestId,
                Timestamp.from(event.occurredAt())
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }

    @Override
    public List<OutboxEntry> findUnprocessedWithLock(int limit) {
        return jdbc.query(
            "SELECT id, event_type, aggregate_id, payload, request_id FROM outbox WHERE processed_at IS NULL ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
            ROW_MAPPER,
            limit
        );
    }

    @Override
    public void markAsProcessed(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "UPDATE outbox SET processed_at = NOW() WHERE id IN (" + placeholders + ")";
        Object[] params = ids.toArray();  // UUID objects for UUID column
        jdbc.update(sql, params);
    }

    @Override
    public void deleteProcessedOlderThan(Instant threshold) {
        jdbc.update(
            "DELETE FROM outbox WHERE processed_at IS NOT NULL AND processed_at < ?",
            Timestamp.from(threshold)
        );
    }

    @Override
    public long countUnprocessed() {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE processed_at IS NULL",
            Long.class
        );
        return count != null ? count : 0;
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM outbox");
    }
}
