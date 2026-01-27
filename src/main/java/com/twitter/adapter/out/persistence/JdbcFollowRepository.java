package com.twitter.adapter.out.persistence;

import com.twitter.application.port.out.FollowRepository;
import com.twitter.domain.model.Follow;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcFollowRepository implements FollowRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<FollowedUser> FOLLOWED_USER_ROW_MAPPER = (rs, rowNum) -> new FollowedUser(
        new User(
            UserId.fromTrusted(rs.getString("id")),
            rs.getTimestamp("user_created_at").toInstant()
        ),
        rs.getTimestamp("followed_at").toInstant()
    );

    public JdbcFollowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Follow follow) {
        jdbc.update("""
            INSERT INTO follows (follower_id, followee_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (follower_id, followee_id) DO NOTHING
            """,
            follow.followerId().toString(),
            follow.followeeId().toString(),
            Timestamp.from(follow.createdAt())
        );
    }

    @Override
    public void delete(UserId followerId, UserId followeeId) {
        jdbc.update(
            "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?",
            followerId.toString(),
            followeeId.toString()
        );
    }

    @Override
    public boolean exists(UserId followerId, UserId followeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
            Integer.class,
            followerId.toString(),
            followeeId.toString()
        );
        return count != null && count > 0;
    }

    @Override
    public List<FollowedUser> findFollowing(UserId userId, String cursor, int limit) {
        if (cursor == null) {
            return jdbc.query("""
                SELECT u.id, u.created_at AS user_created_at, f.created_at AS followed_at
                FROM follows f
                JOIN users u ON f.followee_id = u.id
                WHERE f.follower_id = ?
                ORDER BY f.created_at DESC
                LIMIT ?
                """,
                FOLLOWED_USER_ROW_MAPPER,
                userId.toString(),
                limit
            );
        }
        Timestamp cursorTimestamp = Timestamp.from(Instant.parse(cursor));
        return jdbc.query("""
            SELECT u.id, u.created_at AS user_created_at, f.created_at AS followed_at
            FROM follows f
            JOIN users u ON f.followee_id = u.id
            WHERE f.follower_id = ? AND f.created_at < ?
            ORDER BY f.created_at DESC
            LIMIT ?
            """,
            FOLLOWED_USER_ROW_MAPPER,
            userId.toString(),
            cursorTimestamp,
            limit
        );
    }

    @Override
    public List<FollowedUser> findFollowers(UserId userId, String cursor, int limit) {
        if (cursor == null) {
            return jdbc.query("""
                SELECT u.id, u.created_at AS user_created_at, f.created_at AS followed_at
                FROM follows f
                JOIN users u ON f.follower_id = u.id
                WHERE f.followee_id = ?
                ORDER BY f.created_at DESC
                LIMIT ?
                """,
                FOLLOWED_USER_ROW_MAPPER,
                userId.toString(),
                limit
            );
        }
        Timestamp cursorTimestamp = Timestamp.from(Instant.parse(cursor));
        return jdbc.query("""
            SELECT u.id, u.created_at AS user_created_at, f.created_at AS followed_at
            FROM follows f
            JOIN users u ON f.follower_id = u.id
            WHERE f.followee_id = ? AND f.created_at < ?
            ORDER BY f.created_at DESC
            LIMIT ?
            """,
            FOLLOWED_USER_ROW_MAPPER,
            userId.toString(),
            cursorTimestamp,
            limit
        );
    }

    @Override
    public List<UserId> findAllFollowerIds(UserId userId) {
        return jdbc.queryForList(
            "SELECT follower_id FROM follows WHERE followee_id = ?",
            String.class,
            userId.toString()
        ).stream().map(UserId::fromTrusted).toList();
    }

    @Override
    public long countFollowers(UserId userId) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM follows WHERE followee_id = ?",
            Long.class,
            userId.toString()
        );
        return count != null ? count : 0;
    }

    @Override
    public List<UserId> findFollowedCelebrities(UserId userId, int followerThreshold) {
        // Find users that this user follows who have more than threshold followers
        return jdbc.queryForList("""
            SELECT f.followee_id
            FROM follows f
            WHERE f.follower_id = ?
              AND (SELECT COUNT(*) FROM follows f2 WHERE f2.followee_id = f.followee_id) > ?
            """,
            String.class,
            userId.toString(),
            followerThreshold
        ).stream().map(UserId::fromTrusted).toList();
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM follows", Long.class);
        return count != null ? count : 0;
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM follows");
    }
}
