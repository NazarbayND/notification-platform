# Local Kafka Runbook

The Compose stack uses a single Apache Kafka 3.9.1 KRaft broker for local development, a one-shot topic initializer, and Kafka UI. This is not a production cluster topology.

```bash
mvn test
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
docker compose ps
```

Local endpoints:

- Kafka external bootstrap: `localhost:9092`
- Kafka internal bootstrap: `kafka:29092`
- Kafka UI: `http://localhost:8080`
- Notification API: `http://localhost:8081`
- Orchestrator: `http://localhost:8091`
- Projection API: `http://localhost:8092`

Create or verify topics from inside the broker image:

```bash
docker compose run --rm kafka-topic-init
```

If `kafka-topics.sh` is installed on the host, this also works:

```bash
./scripts/kafka/create-topics.sh localhost:9092
```

Local partitions default to 6 for intake/channel/results/status/retry topics and 3 for reference events and DLQs. Production counts must be selected from measured throughput, key skew, retention, replication, and recovery requirements; replication factor should normally be at least 3 with an appropriate minimum ISR.

## Verified local results

The following were verified on 2026-07-10:

- `mvn test`: passed, 22 tests, 0 failures and 0 errors.
- `mvn -f services/pom.xml test`: passed.
- `mvn -f services/pom.xml package -DskipTests`: passed.
- `docker compose up -d --build`: passed.
- Topic initialization created/verified all 30 topics and exited 0.
- API readiness reported Redis and Kafka `UP`.
- A smoke request returned `202`, appeared in `notification.requests.v1` under the expected `tenantId:recipientId` key, and was visible as `ACCEPTED` through the Redis fallback status lookup.

The pre-cleanup Kafka migration topology was subsequently verified on 2026-07-12:

- `mvn -f services/pom.xml clean test`: passed, 23 tests, 0 failures and 0 errors.
- All 12 then-present Spring application health endpoints returned `200`/`UP`; Grafana also returned `200` after removal of a duplicate default Prometheus datasource.
- The service reactor packaged successfully and the integration script passed against the then-present PostgreSQL, Kafka, Redis, and compatibility infrastructure.
- PostgreSQL connection usage stabilized at 27/100 after local Hikari pools were capped at five connections per service.
- Kafka intake accepted 3,001/3,001 requests at 100 RPS for 30 seconds, with 6.62 ms p95 HTTP latency.
- PUSH end-to-end delivery completed 1,501/1,501 requests at 50 RPS for 30 seconds, with zero terminal failures/timeouts/dropped iterations and 4.27 s p95 delivery latency.
- The fixed PUSH consumer drained a previously accumulated 333-message backlog in 7 seconds.

Those numbers are retained as historical evidence and are not proof of the cleaned topology. Current verification results belong in `docs/load-testing.md`.

## Current cleanup verification

On 2026-07-24 the cleaned Compose topology built and started successfully. All
eleven backend health endpoints, the admin frontend, and Grafana returned HTTP
200. A real PUSH request progressed from API acceptance to `DELIVERED`, and
both short load scenarios documented in [load-testing.md](load-testing.md)
passed.

- `mvn -f services/pom.xml clean test`: passed, 23 tests, 0 failures and 0
  errors.
- `mvn -f services/pom.xml package -DskipTests`: passed.
- Frontend ESLint and the TypeScript/Vite production build passed on Node
  20.15.1.
- `docker compose config --quiet`, shell syntax, dashboard JSON syntax,
  manifest YAML syntax, and `git diff --check` passed.
- The repository integration script passed, and the admin dashboard,
  notification-page, and delivery-page endpoints returned HTTP 200 against
  PostgreSQL-backed projections.
