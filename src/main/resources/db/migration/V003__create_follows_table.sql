CREATE TABLE follows (
    follower_id VARCHAR(64) NOT NULL REFERENCES users(id),
    followee_id VARCHAR(64) NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, followee_id)
);

CREATE INDEX idx_follows_follower ON follows(follower_id);
CREATE INDEX idx_follows_followee ON follows(followee_id);
