CREATE TABLE tweets (
    id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id),
    content VARCHAR(280) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tweets_user_id_id ON tweets(user_id, id DESC);
