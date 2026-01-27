# API Reference

The application exposes a RESTful JSON API.

## Authentication

All endpoints require the `X-User-Id` header (UUID). Per assignment specifications, there is no user registration — users are created implicitly (upsert) on first interaction. Any valid UUID is accepted as a user identity.

```bash
curl -H "X-User-Id: 550e8400-e29b-41d4-a716-446655440000" \
     http://localhost:8080/api/v1/tweets
```

## Endpoints

### Tweets

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/tweets` | Create a tweet |
| GET | `/api/v1/users/{userId}/tweets` | Get user's tweets (paginated) |

### Timeline

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/users/{userId}/timeline` | Get home timeline (paginated) |

### Follows

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/users/{userId}/follow/{targetId}` | Follow a user |
| DELETE | `/api/v1/users/{userId}/follow/{targetId}` | Unfollow a user |
| GET | `/api/v1/users/{userId}/followers` | Get followers (paginated) |
| GET | `/api/v1/users/{userId}/following` | Get following (paginated) |

## Pagination

All list endpoints support cursor-based pagination:

```bash
# First page
GET /api/v1/users/{userId}/timeline?limit=20

# Response
{
  "data": [...],
  "pagination": {
    "nextCursor": "0192fd7e-...",
    "hasMore": true
  }
}

# Next page
GET /api/v1/users/{userId}/timeline?limit=20&cursor=0192fd7e-...
```

### Query Parameters

| Parameter | Default | Max | Description |
|-----------|---------|-----|-------------|
| `limit` | 20 | 100 | Number of items per page |
| `cursor` | — | — | Opaque cursor from previous response |

### Response Format

```json
{
  "data": [
    {
      "id": "0192fd7e-...",
      "userId": "550e8400-...",
      "content": "Hello world!",
      "createdAt": "2026-01-27T10:30:00Z"
    }
  ],
  "pagination": {
    "nextCursor": "0192fd7e-...",
    "hasMore": true
  }
}
```

## Error Responses

All errors return a consistent format:

```json
{
  "error": "ERROR_CODE",
  "message": "Human readable message",
  "requestId": "c8b6298e-..."
}
```

### Common Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `UNAUTHORIZED` | 401 | Missing or invalid `X-User-Id` header |
| `USER_NOT_FOUND` | 404 | Referenced user does not exist |
| `TWEET_CONTENT_EMPTY` | 400 | Tweet content is empty |
| `TWEET_CONTENT_TOO_LONG` | 400 | Tweet exceeds 280 characters |
| `ALREADY_FOLLOWING` | 400 | Already following this user |
| `NOT_FOLLOWING` | 400 | Not following this user |
| `CANNOT_FOLLOW_SELF` | 400 | Cannot follow yourself |

## Interactive Documentation

Access the interactive API console at: **http://localhost:8080/docs.html**

This provides a Stripe-like interface to test all endpoints with pre-filled headers and example requests.
