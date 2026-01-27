package com.twitter.adapter.out.persistence;

import com.twitter.application.port.out.TweetRepository;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.*;

@Repository
public class JdbcTweetRepository implements TweetRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Tweet> ROW_MAPPER = (rs, rowNum) -> new Tweet(
        UUID.fromString(rs.getString("id")),
        UserId.fromTrusted(rs.getString("user_id")),
        rs.getString("content"),
        rs.getTimestamp("created_at").toInstant()
    );

    public JdbcTweetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Tweet tweet) {
        jdbc.update("""
            INSERT INTO tweets (id, user_id, content, created_at)
            VALUES (?, ?, ?, ?)
            """,
            tweet.id(),  // UUID object for UUID column
            tweet.userId().toString(),  // String for VARCHAR column
            tweet.content(),
            Timestamp.from(tweet.createdAt())
        );
    }

    @Override
    public Optional<Tweet> findById(UUID id) {
        return jdbc.query(
            "SELECT id, user_id, content, created_at FROM tweets WHERE id = ?",
            ROW_MAPPER,
            id  // UUID object for UUID column
        ).stream().findFirst();
    }

    @Override
    public List<Tweet> findByUserId(UserId userId, UUID cursor, int limit) {
        if (cursor == null) {
            return jdbc.query("""
                SELECT id, user_id, content, created_at
                FROM tweets
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT ?
                """,
                ROW_MAPPER,
                userId.toString(),
                limit
            );
        }
        return jdbc.query("""
            SELECT id, user_id, content, created_at
            FROM tweets
            WHERE user_id = ? AND id < ?
            ORDER BY id DESC
            LIMIT ?
            """,
            ROW_MAPPER,
            userId.toString(),
            cursor,  // UUID object for UUID column
            limit
        );
    }

    @Override
    public List<Tweet> findByUserIdLatest(UserId userId, int limit) {
        return jdbc.query("""
            SELECT id, user_id, content, created_at
            FROM tweets
            WHERE user_id = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            ROW_MAPPER,
            userId.toString(),
            limit
        );
    }

    @Override
    public List<Tweet> findByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, user_id, content, created_at FROM tweets WHERE id IN (" + placeholders + ")";

        Object[] params = ids.toArray();  // UUID objects for UUID column
        return jdbc.query(sql, ROW_MAPPER, params);
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tweets", Long.class);
        return count != null ? count : 0;
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM tweets");
    }
}
