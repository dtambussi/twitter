# Load Testing

Stress tests the Twitter clone with realistic user journeys. **Only Docker required.**

## Quick Start

Base url argument is for user to indicate his host ip:port or target server ip:port

```bash
# Build the image
docker build -t twitter-load-test ./load-test

# Smoke test - verify all endpoints work (fast, shows responses)
docker run --rm -it twitter-load-test --smoke http://host.docker.internal:8080

# Full load test - stress test with many users
docker run --rm -it twitter-load-test http://host.docker.internal:8080
```

For Linux (no Docker Desktop): use `http://<your-host-ip>:8080` instead.

## Smoke Test (`--smoke`)

Quick verification that all endpoints work correctly. Shows:
- Actual request/response for each endpoint
- Pagination in action (cursor + limit)
- Complete user flow: create tweets → follow → timeline → unfollow

```
═══ Step 3: Get User Tweets (with pagination) ═══

Get User 1's tweets (limit=1 to demo pagination)
  GET /api/v1/users/{id}/tweets?limit=1
  Status: 200
  Response:
  { "data": [...], "pagination": { "nextCursor": "0192fd7e-...", "hasMore": true } }

Fetch NEXT PAGE using cursor=0192fd7e-...
  GET /api/v1/users/{id}/tweets?limit=1&cursor=0192fd7e-...
  Status: 200
  Response:
  { "data": [...], "pagination": { "nextCursor": null, "hasMore": false } }
```

## What It Tests

The load tester simulates realistic usage in two phases:

### Setup Phase (data preparation)
| Step | Description |
|------|-------------|
| Initialize Users | Creates regular users + celebrities via first tweets |
| Build Celebrity Followers | 5,000 followers per celebrity (triggers fan-out-on-read) |
| Build Social Graph | Regular users follow each other + celebrities |

### Runtime Phase (scored)
| Step | Description |
|------|-------------|
| Create Tweets | Each user posts multiple tweets |
| Read Timelines | Users paginate through timelines using `cursor` + `limit` |
| Check Profiles | Users paginate through their own tweets |
| Unfollow Some | Some users unfollow others |
| Mixed Activity | Realistic mix of tweets, follows, and reads happening together |

**Pagination is tested!** Timeline reads use `pagination_page_size` (default 10) and follow `nextCursor` to fetch all pages. With 9 users followed × 10 tweets each = ~90 tweets per timeline = ~9 pages per read.

## Configuration

Edit `config.json` to customize the test:

```json
{
  "users": {
    "regular": 50,
    "celebrities": 2
  },
  "activity": {
    "tweets_per_user": 10,
    "timeline_reads_per_user": 25,
    "follows_per_regular_user": 7,
    "unfollows_per_user": 3
  },
  "concurrency": {
    "max_concurrent_requests": 50
  },
  "thresholds": {
    "max_error_rate_percent": 5,
    "max_p95_latency_ms": 2000
  }
}
```

**Note:** The celebrity follower threshold (5,000) is defined in the backend (`application.yml`), not here.

## Sample Output

```
⚙️ Configuration
Setting                         Value
Target                http://...
Users
  Regular Users              50
  Celebrities                 2
  Celebrity Threshold     5,000 (see application.yml)
Activity (per user)
  Tweets                     10
  Timeline Reads             25
Volume
  Setup Phase         10,402 requests
  Runtime Phase        1,972 requests

━━━ Setup Phase (artificial load to create test data) ━━━
1. Initialize Users (first tweets)  ████████  52/52     elapsed: 0:00:01
2. Build Celebrity Followers        ████████  10000/10000 elapsed: 0:00:05
3. Build Social Graph               ████████  350/350   elapsed: 0:00:00

   └─ Users created: 10,052 (50 regular + 2 celebrities + 10,000 celebrity followers)

━━━ Runtime Phase (simulated real user activity) ━━━
4. Create Tweets       ████████  520/520   elapsed: 0:00:02
5. Read Timelines      ████████  1250/1250 elapsed: 0:00:03
6. Check Profiles      ████████  52/52     elapsed: 0:00:00
7. Unfollow Some       ████████  150/150   elapsed: 0:00:01
8. Mixed Activity      ████████  100/100   elapsed: 0:00:02

╭─────────────────────────────────────────────╮
│ 📦 Setup Phase (data preparation - not scored) │
╰─────────────────────────────────────────────╯
  Requests       10,402
  Success Rate   100.0%
  Latency p95    4801ms
  
  High latency expected due to
  10,000 concurrent follow ops

╭─────────────────────────────────────────────╮
│ 🚀 Runtime Phase (simulated real usage - scored) │
╰─────────────────────────────────────────────╯
  Requests        1,972
  Success Rate   100.0%
  Latency p50      32ms
  Latency p95     153ms

╭──────────────────────────────────────╮
│ ✓ PASSED                             │
│ Runtime: 100.0% success, p95 153ms   │
╰──────────────────────────────────────╯
```

## Running Without Docker

```bash
pip install rich aiohttp
python demo_load_tester.py http://localhost:8080
```
