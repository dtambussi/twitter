package com.twitter.adapter.out.persistence;

import com.twitter.application.port.out.UserRepository;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User(
        UserId.fromTrusted(rs.getString("id")),
        rs.getTimestamp("created_at").toInstant()
    );

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(User user) {
        jdbc.update("""
            INSERT INTO users (id, created_at)
            VALUES (?, ?)
            ON CONFLICT (id) DO NOTHING
            """,
            user.id().toString(),
            Timestamp.from(user.createdAt())
        );
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jdbc.query(
            "SELECT id, created_at FROM users WHERE id = ?",
            ROW_MAPPER,
            id.toString()
        ).stream().findFirst();
    }

    @Override
    public boolean exists(UserId id) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?",
            Integer.class,
            id.toString()
        );
        return count != null && count > 0;
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count != null ? count : 0;
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM users");
    }
}
