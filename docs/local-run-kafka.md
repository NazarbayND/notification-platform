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

Use `NOTIFICATION_BROKER_INTAKE=legacy` and `NOTIFICATION_BROKER_DELIVERY=rabbitmq` for the rollback path. Kafka is the default for both intake and delivery.

Local partitions default to 6 for intake/channel/results/status/retry topics and 3 for reference events and DLQs. Production counts must be selected from measured throughput, key skew, retention, replication, and recovery requirements; replication factor should normally be at least 3 with an appropriate minimum ISR.

## Verified local result

The following were verified on 2026-07-10:

- `mvn test`: passed, 22 tests, 0 failures and 0 errors.
- `mvn -f services/pom.xml test`: passed.
- `mvn -f services/pom.xml package -DskipTests`: passed.
- `docker compose up -d --build`: passed.
- Topic initialization created/verified all 30 topics and exited 0.
- API readiness reported PostgreSQL, Redis, and Kafka `UP`.
- A smoke request returned `202`, appeared in `notification.requests.v1` under the expected `tenantId:recipientId` key, and was visible as `ACCEPTED` through the Redis fallback status lookup.

These results cover the Phase 3 baseline only. The Phase 4–8 implementation has intentionally not been built, run, or tested yet; validation is deferred until the implementation pass is finished.
