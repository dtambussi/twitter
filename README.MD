# Twitter Clone

A scalable Twitter-like backend demonstrating real-world system design patterns for high-throughput social media timelines.

## Use Cases

- **Post tweets** — Users can publish short-form content (max 280 characters)
- **Follow/unfollow users** — Build a social graph of connections
- **View timeline** — See tweets from followed users, sorted by recency
- **View user profile** — See a user's tweets, followers, and following

> **Note on Authentication**: Per assignment specifications, there is no real authentication. All requests include an `X-User-Id` header (UUID), and any valid UUID is accepted — users are created implicitly (upsert) on first interaction.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3.4 |
| Database | PostgreSQL (with sharding support) |
| Cache | Redis (timeline cache) |
| Message Broker | Redpanda (Kafka-compatible) |
| Observability | Prometheus, Grafana, Loki *(structured logging; distributed tracing with OpenTelemetry was out of scope)* |
| Testing | JUnit 5, Testcontainers |
| Build | Gradle |
| Containers | Docker Desktop 4.35.1 recommended |

📘 **[RUNBOOK.md](RUNBOOK.MD)** — Setup instructions, development, and how to run the application

📗 **[REFERENCE_API.md](REFERENCE_API.MD)** — REST API endpoints, authentication, pagination, and error codes

## Key Features

- **Hybrid Fan-out Strategy**: Fan-out-on-write for regular users, fan-out-on-read for celebrities (5000+ followers)
- **Transactional Outbox Pattern**: Guarantees exactly-once event delivery without distributed transactions
- **Cursor-based Pagination**: Efficient, consistent pagination using UUIDv7 timestamps
- **Hexagonal Architecture**: Clean separation between domain, application, and infrastructure layers

## Architecture

### Modular Monolith

This is a **modular monolith** — a single deployable unit with clear internal boundaries. This approach provides:

- **Simplicity**: One deployment, one codebase, easier debugging
- **Modularity**: Clean separation allows extracting services later if needed
- **Performance**: No network overhead between modules

The hexagonal architecture ensures modules communicate through well-defined ports (interfaces), making future extraction to microservices straightforward.

![Architecture Layers](images/architecture-layers.png)

### Read-Optimized Design

Social media is extremely read-heavy: users scroll their timelines far more often than they post. A typical ratio is **100:1 reads to writes**. This architecture optimizes for that reality:

| Operation | Optimization |
|-----------|--------------|
| Read timeline | Pre-computed in Redis — O(1) cache lookup |
| Post tweet | Async fan-out via Kafka — API returns immediately |
| Follow/unfollow | Background timeline rebuild |

The trade-off: writes do more work (fan-out) so reads can be instant.

### Timeline Strategy

**Regular Users (< 5000 followers)**: Fan-out-on-write
- When user tweets, push to all followers' Redis timelines immediately
- Fast reads: timeline is pre-computed in cache

**Celebrities (≥ 5000 followers)**: Fan-out-on-read  
- Tweets stored only in database
- At read time, merge celebrity tweets with cached timeline
- Prevents write amplification for viral accounts

### Event Flows by Use Case

All write operations use the **transactional outbox pattern**: data and event are saved atomically, then processed asynchronously.

#### Post Tweet

![Post Tweet Flow](images/post-tweet-flow.png)

#### Follow / Unfollow

![Follow Flow](images/follow-flow.png)

#### Read Timeline

![Read Timeline Flow](images/read-timeline-flow.png)

**Why outbox?** Publishing directly to Kafka risks event loss if the app crashes after DB commit but before publish. The outbox guarantees: if the data is saved, the event will eventually be delivered.

## Resilience

> **Note**: Explicit retry/circuit breaker libraries (e.g., Resilience4j) were considered but deemed out of scope. The architecture already provides resilience through:
> - **Transactional outbox** — Failed events remain in the table and are retried on the next poll cycle
> - **Kafka consumer offsets** — Failed messages are automatically redelivered
> - **HikariCP connection pool** — Built-in connection retry and timeout handling
>
> *For this backend challenge, Docker restart policies are not configured — a production deployment would add `restart: unless-stopped` and orchestration (Kubernetes, ECS, etc.).*

## Security (Backend Challenge Scope)

> ⚠️ **This is a backend challenge, not production-ready regarding security:**
> - **Open ports** — PostgreSQL (5432), Redis (6379), Kafka (19092), Prometheus (9090), Loki (3100) bind to `0.0.0.0`. On a public server, all services are exposed.
> - **Hardcoded credentials** — `postgres/postgres`, `admin/admin` for Grafana, no external configuration.
> - **No TLS** — API served on raw HTTP (port 8080), no HTTPS or reverse proxy.
> - **Unpinned image tags** — Redpanda, Prometheus, Grafana, Loki, Promtail use `:latest`. A version bump could break things unexpectedly.
>
> A production deployment would require proper secrets management, network isolation, TLS termination, and pinned image versions.

## Scalability

### Why This Architecture Scales

1. **Stateless Application**: The app server holds no state — all data lives in PostgreSQL, Redis, and Redpanda. Run multiple instances behind a load balancer.

2. **Read-Heavy Optimization**: Timelines are pre-computed in Redis. Most reads hit cache, not the database.

3. **Hybrid Fan-out**: Celebrities use fan-out-on-read to avoid write amplification. A user with 10M followers doesn't trigger 10M Redis writes.

4. **Async Processing**: Tweet fan-out happens asynchronously via Kafka. The API returns immediately; timeline updates happen in the background.

### Horizontal Scaling

![Horizontal Scaling](images/horizontal-scaling.png)

### Database Sharding (Future)

Sharding infrastructure is included but **not active** — the current single-database setup handles significant load. When needed, enable sharding by `userId`:

- **Tweets** → shard by `author_id`
- **Follows** → shard by `follower_id`
- **Timelines** → Redis key prefixing (`timeline:{userId}`)

See `infrastructure/sharding/` for the ready-to-use implementation.

### Further Scaling (Beyond This Backend Challenge)

For true production scale, additional infrastructure would include:

- **Container orchestration** — Kubernetes or ECS for auto-scaling, self-healing, and rolling deployments
- **Managed data services** — RDS/Aurora for PostgreSQL, ElastiCache for Redis, MSK for Kafka
- **CDN** — CloudFront/Cloudflare for static assets and API edge caching
- **Read replicas** — PostgreSQL replicas for read-heavy queries (user profiles, tweet lookups)
- **Global distribution** — Multi-region deployment with geo-routing

The modular monolith architecture makes these transitions straightforward — the hexagonal boundaries allow swapping infrastructure without touching domain logic.

## Project Structure

```
src/main/java/com/twitter/
├── domain/                 # Domain models, events, errors
│   ├── model/             # Tweet, User, Follow, Page, Result
│   ├── event/             # TweetCreated, UserFollowed, etc.
│   └── error/             # Domain-specific errors
├── application/           # Use cases and ports
│   ├── service/           # TweetService, TimelineService, FollowService
│   └── port/              # In/Out interfaces (hexagonal)
├── adapter/               # Infrastructure implementations
│   ├── in/web/            # REST controllers
│   ├── out/persistence/   # JDBC repositories
│   ├── out/cache/         # Redis timeline cache
│   └── out/messaging/     # Kafka consumer, outbox poller
└── infrastructure/        # Cross-cutting concerns
    ├── config/            # Spring configuration
    ├── filter/            # Auth filter
    └── sharding/          # Database sharding support
```

## License

MIT
