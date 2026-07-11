# Kafka-First Notification Platform Migration Plan

You are working on an existing Spring Boot microservices Notification Platform.

Your task is to evolve the platform from a synchronous database-first architecture using PostgreSQL Outbox + RabbitMQ into a Kafka-first, asynchronous, backpressure-aware notification platform.

Do not rewrite the whole project at once. Implement the migration in safe phases, preserve existing functionality where practical, and keep the project runnable locally after every major phase.

Current architecture

The existing flow is approximately:

1. Client calls POST /notifications.
2. notification-api-service synchronously calls:
   - template-service
   - preference-service
3. notification-api-service writes:
   - notification row
   - delivery rows
   - outbox rows
4. outbox-publisher-service polls PostgreSQL.
5. Outbox publisher sends jobs to RabbitMQ.
6. Channel workers consume jobs.
7. Workers invoke providers and store delivery attempts.

Existing services include:

- notification-api-service
- template-service
- preference-service
- outbox-publisher-service
- email-worker-service
- sms-worker-service
- push-worker-service
- in-app-worker-service
- webhook-worker-service
- admin-bff-service
- admin-frontend

Existing infrastructure includes:

- PostgreSQL
- Redis
- RabbitMQ
- MailHog
- Docker Compose
- Kubernetes manifests
- Prometheus/Grafana/OpenTelemetry if already present

Target architecture

Implement this target flow:

Client
|
API Gateway / admission-control layer
|
notification-api-service
|
Kafka: notification.requests.v1
|
notification-orchestrator-service
|
+--> notification.email.v1
+--> notification.sms.v1
+--> notification.push.v1
+--> notification.webhook.v1
+--> notification.in-app.v1
|
Channel workers
|
Kafka: notification.delivery-results.v1
|
notification-projection-service
|
Notification query store

Additional flows:

template-service
|
Kafka: template.events.v1
|
orchestrator local template projection/cache
preference-service
|
Kafka: preference.events.v1
|
orchestrator local preference projection/cache

The HTTP intake API must perform only lightweight synchronous work:

- authentication/tenant validation if already implemented
- request schema validation
- request-size validation
- idempotency-key validation
- admission control
- Kafka publish
- return 202 Accepted

The API must no longer synchronously render templates, check preferences, create delivery rows, or wait for provider work.

Critical architectural requirements

1. Kafka-first durable intake

Kafka must be the first durable write for new notification requests.

Flow:

POST /notifications
-> validate request
-> generate notificationId if absent
-> publish NotificationRequested command to Kafka
-> return 202 Accepted

Configure the Kafka producer for strong durability:

- acks=all
- idempotent producer enabled
- bounded producer memory
- finite max.block.ms
- sensible delivery timeout
- retries enabled
- compression enabled where useful

Do not add an unbounded in-memory queue in front of Kafka.

When Kafka cannot accept the event within the configured timeout, return a controlled error:

- 503 Service Unavailable, or
- 429 Too Many Requests when caused by admission limits

Do not allow requests to wait until a generic HTTP timeout.

2. API response semantics

POST /notifications must return:

202 Accepted

Example response:

{
"notificationId": "01J...",
"requestId": "01J...",
"status": "ACCEPTED",
"acceptedAt": "2026-07-10T12:00:00Z"
}

Document clearly:

- ACCEPTED means Kafka durably accepted the command.
- It does not mean rendering or delivery succeeded.
- The notification may not yet exist in the query projection immediately after the response.

Add an endpoint or behavior that supports eventual consistency:

GET /notifications/{notificationId}/status

Possible statuses:

- ACCEPTED
- PROCESSING
- SCHEDULED
- PARTIALLY_DELIVERED
- DELIVERED
- FAILED
- REJECTED
- NOT_FOUND

If the permanent projection has not processed the event yet, use Redis as a short-lived acceptance cache:

notification:acceptance:{notificationId}

Suggested TTL: 10 to 30 minutes.

The API should check:

1. Permanent notification projection
2. Redis acceptance state
3. Otherwise return not found

4. Admission control and backpressure

Implement protection at the API layer.

Required controls:

- global request rate limit
- per-tenant request rate limit
- maximum concurrent request limit
- maximum request body size
- maximum number of recipients per request
- maximum number of channels per request
- bounded Kafka producer buffer
- Kafka publish timeout
- graceful load shedding
- Retry-After header for rejected requests where appropriate

Use Redis-backed rate limiting if Redis already exists.

Support configurable limits through environment variables or application configuration.

Example configuration:

notification:
intake:
global-rate-per-second: 5000
per-tenant-rate-per-second: 500
max-concurrent-requests: 1000
max-request-bytes: 262144
max-recipients-per-request: 1000
kafka-publish-timeout: 3s

Expose metrics:

- notification intake requests total
- accepted total
- rejected total
- rate-limited total
- concurrent requests
- Kafka publish latency
- Kafka publish failures
- Kafka producer buffer exhaustion
- request payload size
- accepted requests per tenant

Use low-cardinality metric labels. Do not use notification IDs or user IDs as metric labels.

4. New notification orchestrator service

Create:

notification-orchestrator-service

Responsibilities:

1. Consume notification.requests.v1.
2. Deduplicate using eventId or idempotencyKey.
3. Mark notification as processing through status events.
4. Resolve template information.
5. Resolve user/product/channel preferences.
6. Validate required variables.
7. Determine enabled delivery channels.
8. Render channel-specific content.
9. Generate one delivery ID per channel/recipient.
10. Publish channel delivery commands.
11. Publish rejection/status events when the request is invalid.
12. Avoid synchronous template and preference service calls on the hot path.

The orchestrator should use Kafka-based local projections or caches for templates and preferences.

For the first migration phase, a controlled synchronous fallback may be used behind a feature flag, but the final default path must use local projections.

5. Template and preference change events

Update template-service to publish events when templates change.

Topic:

template.events.v1

Events:

- TemplateCreated
- TemplateUpdated
- TemplateDeleted

Update preference-service to publish events when preferences change.

Topic:

preference.events.v1

Events:

- PreferenceCreated
- PreferenceUpdated
- PreferenceDeleted

Because these services modify PostgreSQL and publish Kafka events, preserve atomicity with one of these approaches:

Preferred:

- keep a transactional outbox inside template-service and preference-service
- use a Kafka publisher for their outbox events

Do not remove outbox indiscriminately. The outbox is removed from notification intake because Kafka becomes the first durable write. It may still be needed where a service must atomically update its database and publish an event.

Create local projections inside the orchestrator:

template_projection
preference_projection

These may initially be stored in PostgreSQL, Redis, or an embedded local store.

Prefer a design that is:

- rebuildable from Kafka
- queryable efficiently by template ID and tenant/user/product
- version-aware
- safe against out-of-order updates

Each projection event must include:

{
"eventId": "01J...",
"eventType": "TemplateUpdated",
"aggregateId": "template-123",
"aggregateVersion": 7,
"occurredAt": "2026-07-10T12:00:00Z",
"schemaVersion": 1,
"payload": {}
}

Ignore stale projection events when aggregateVersion is older than the stored version.

6. Kafka topics

Create and document at least these topics:

notification.requests.v1
notification.email.v1
notification.sms.v1
notification.push.v1
notification.webhook.v1
notification.in-app.v1
notification.delivery-results.v1
notification.status-events.v1
template.events.v1
preference.events.v1

Retry and DLQ topics:

notification.email.retry-1m.v1
notification.email.retry-5m.v1
notification.email.retry-30m.v1
notification.email.dlq.v1
notification.sms.retry-1m.v1
notification.sms.retry-5m.v1
notification.sms.retry-30m.v1
notification.sms.dlq.v1
notification.push.retry-1m.v1
notification.push.retry-5m.v1
notification.push.retry-30m.v1
notification.push.dlq.v1
notification.webhook.retry-1m.v1
notification.webhook.retry-5m.v1
notification.webhook.retry-30m.v1
notification.webhook.dlq.v1
notification.in-app.retry-1m.v1
notification.in-app.retry-5m.v1
notification.in-app.retry-30m.v1
notification.in-app.dlq.v1

Use configurable partition counts.

Document recommended local defaults and production recommendations.

Example local default:

notification.requests.v1: 6 partitions
channel topics: 6 partitions each
delivery-results: 6 partitions
template/preference events: 3 partitions

Do not hardcode assumptions that partition count equals worker count.

7. Partitioning strategy

Use a stable partition key.

For intake, use:

tenantId + recipientId

when per-recipient ordering is needed.

For requests with many recipients, consider generating child delivery commands and partitioning each child command separately.

Do not use only tenantId as the partition key because a large tenant could create a hot partition.

Document ordering guarantees:

- ordering is guaranteed only within one Kafka partition
- global ordering is not guaranteed
- per-recipient ordering depends on the selected key
- retries may affect perceived ordering unless handled explicitly

8. Event contracts

Create versioned event DTOs in a shared contracts module.

Do not share JPA entities between services.

Suggested module:

services/shared-event-contracts

Create contracts for:

NotificationRequested

{
"eventId": "01J...",
"notificationId": "01J...",
"requestId": "01J...",
"tenantId": "tenant-123",
"idempotencyKey": "order-456-shipped",
"templateId": "order-shipped",
"recipient": {
"userId": "user-123",
"email": "user@example.com",
"phone": "+973..."
},
"requestedChannels": ["EMAIL", "PUSH"],
"variables": {
"orderId": "456"
},
"requestedAt": "2026-07-10T12:00:00Z",
"schemaVersion": 1
}

DeliveryRequested

{
"eventId": "01J...",
"notificationId": "01J...",
"deliveryId": "01J...",
"tenantId": "tenant-123",
"recipientId": "user-123",
"channel": "EMAIL",
"recipientAddress": "user@example.com",
"subject": "Your order has shipped",
"body": "...",
"attempt": 1,
"createdAt": "2026-07-10T12:00:01Z",
"schemaVersion": 1
}

DeliveryResult

{
"eventId": "01J...",
"notificationId": "01J...",
"deliveryId": "01J...",
"tenantId": "tenant-123",
"channel": "EMAIL",
"status": "DELIVERED",
"attempt": 1,
"providerMessageId": "provider-789",
"errorCode": null,
"errorMessage": null,
"occurredAt": "2026-07-10T12:00:03Z",
"schemaVersion": 1
}

NotificationStatusChanged

{
"eventId": "01J...",
"notificationId": "01J...",
"tenantId": "tenant-123",
"status": "PROCESSING",
"reasonCode": null,
"reasonMessage": null,
"occurredAt": "2026-07-10T12:00:01Z",
"schemaVersion": 1
}

Use JSON serialization initially.

Add schema versioning and compatibility tests.

Do not silently break existing event contracts.

9. Idempotency and delivery guarantees

Use at-least-once delivery.

Every consumer must be idempotent.

Required strategy:

- each event has globally unique eventId
- each notification request may include idempotencyKey
- persist processed event IDs or use a durable idempotency table
- enforce uniqueness at the database level
- duplicate events must not cause duplicate provider sends where preventable
- duplicate delivery results must not create duplicate delivery attempts

Suggested tables:

processed_events (
consumer_name varchar not null,
event_id varchar not null,
processed_at timestamp not null,
primary key (consumer_name, event_id)
)
notification_idempotency (
tenant_id varchar not null,
idempotency_key varchar not null,
notification_id varchar not null,
created_at timestamp not null,
primary key (tenant_id, idempotency_key)
)

Use one database transaction for:

- deduplication record
- consumer-owned state update

Commit Kafka offsets only after successful processing.

Where Kafka transactions are practical for consume-transform-produce operations, use them. Do not claim exactly-once delivery to external providers.

Document clearly:

Kafka processing can be exactly-once or effectively-once within Kafka boundaries.
External email/SMS/push providers remain at-least-once.
Provider-level idempotency keys should be used when supported.

10. Channel workers

Migrate channel workers from RabbitMQ to Kafka consumers.

Each channel worker must:

1. Consume its channel topic.
2. Deduplicate by event ID or delivery ID.
3. Apply provider-specific rate limits.
4. Call the provider.
5. Record an attempt locally if the worker owns an attempt store.
6. Publish a delivery result event.
7. Retry transient failures.
8. Send permanent failures or exhausted retries to DLQ.
9. Expose provider latency and failure metrics.

Classify failures:

Transient:

- timeout
- connection reset
- provider 429
- provider 5xx
- temporary DNS/network error

Permanent:

- invalid email
- invalid phone number
- invalid payload
- unsupported channel
- provider 4xx excluding retryable responses
- missing required template data

Use exponential backoff with jitter.

Do not retry permanent failures.

Prevent unbounded concurrent provider calls.

Use:

- bounded worker executor
- provider concurrency semaphore
- per-provider rate limiter
- Kafka consumer pause/resume when local capacity is full

11. Retry implementation

Prefer non-blocking Kafka retry topics rather than sleeping inside the consumer thread.

Required retry stages:

- 1 minute
- 5 minutes
- 30 minutes
- DLQ after the configured maximum attempt count

Include these headers or event fields:

originalTopic
originalPartition
originalOffset
attempt
firstFailureAt
lastFailureAt
errorCode
errorMessage

Provide an admin endpoint to inspect and manually replay DLQ events.

Manual replay must:

- preserve the original notification and delivery IDs
- generate a new replay event ID
- record who or what triggered the replay
- avoid accidental infinite replay loops

12. Projection service and notification store

Create:

notification-projection-service

Responsibilities:

- consume NotificationRequested
- consume NotificationStatusChanged
- consume DeliveryResult
- build the query model used by:
  - notification API GET endpoints
  - admin BFF
  - dashboard
- support rebuilding projection state from Kafka

Initially keep PostgreSQL as the query store.

Do not migrate to MongoDB only because it is NoSQL.

Optimize PostgreSQL first:

- remove intake writes from the synchronous path
- use append-oriented delivery attempt tables
- use proper indexes
- use batch writes where useful
- partition large tables by time or tenant/time if justified
- add retention/archival strategy
- avoid unnecessary indexes
- use connection pooling
- separate write-heavy projection work from admin queries if needed

Design a storage abstraction:

public interface NotificationProjectionRepository {
void upsertAcceptedNotification(...);
void updateNotificationStatus(...);
void upsertDelivery(...);
void appendDeliveryAttempt(...);
Optional<NotificationView> findById(...);
Page<NotificationSummary> findByTenant(...);
}

Provide PostgreSQL implementation first.

Prepare, but do not necessarily fully implement in the first pass:

DynamoDbNotificationProjectionRepository

Add a design document describing DynamoDB access patterns:

- get notification by ID
- list tenant notifications by time
- list user notifications by time
- update delivery state
- TTL-based retention
- conditional idempotent updates
- hot-partition mitigation

Do not use DynamoDB Local benchmark results as proof of AWS production scaling.

13. RabbitMQ migration strategy

Do not remove RabbitMQ immediately.

Implement feature flags:

notification:
broker:
intake: kafka
delivery: rabbitmq

Then support:

notification:
broker:
intake: kafka
delivery: kafka

Migration phases:

Phase A

- Add Kafka infrastructure.
- API publishes to Kafka.
- New orchestrator consumes Kafka.
- Orchestrator temporarily publishes to existing RabbitMQ queues.
- Existing workers continue operating.
- Validate Kafka intake independently.

Phase B

- Add Kafka channel topics.
- Migrate one worker first, preferably email.
- Run email worker in Kafka mode.
- Keep remaining workers on RabbitMQ.

Phase C

- Migrate all channel workers to Kafka.
- Disable RabbitMQ delivery publishing.
- Keep RabbitMQ configuration available temporarily for rollback.

Phase D

- Remove RabbitMQ runtime dependency only after:
  - integration tests pass
  - load tests pass
  - delivery parity is validated
  - rollback documentation exists

Do not operate dual publishing without deduplication.

If a compatibility period requires publishing to Kafka and RabbitMQ, include a migration ID and ensure workers cannot send the same provider notification twice.

14. Outbox changes

Remove the notification intake outbox from the active Kafka-first path.

Do not immediately delete old tables or code.

Mark the old flow as legacy behind configuration.

Keep outbox where needed for:

- template database update + template event
- preference database update + preference event
- any other database update + broker publication requiring atomicity

Update documentation to explain:

Kafka-first intake removes the initial notification DB/Kafka dual write.
Transactional outbox still protects service-local DB/event dual writes.

15. Observability

Add end-to-end observability.

Use OpenTelemetry trace propagation through Kafka headers.

Trace stages:

- HTTP intake
- Kafka producer
- orchestrator consume
- template/preference projection lookup
- channel event publication
- worker consume
- provider call
- result publication
- projection update

Do not create one unbounded trace containing an extremely large batch of recipients. For large fan-out, use links or child traces carefully.

Metrics required:

API

- request rate
- accepted/rejected rate
- 429 count
- 503 count
- Kafka produce latency
- Kafka publish errors
- active requests

Kafka

- producer throughput
- consumer throughput
- consumer lag
- oldest unprocessed record age
- rebalance count
- processing failures
- retry-topic volume
- DLQ volume
- partition skew

Orchestrator

- requests processed
- render latency
- preference resolution latency
- rejected notifications
- generated deliveries
- deduplicated events

Workers

- provider calls
- provider success rate
- provider failure rate
- provider latency
- retries
- permanent failures
- active worker tasks
- local queue depth
- rate-limit delays

Projection

- projection lag
- projection update latency
- duplicate events
- database write latency
- database errors

Create Grafana dashboards for:

1. Intake health
2. Kafka and consumer lag
3. Orchestrator performance
4. Worker/provider performance
5. Retry and DLQ health
6. Projection/database health

Add alerts for:

- intake rejection rate above threshold
- Kafka publish failure
- sustained consumer lag
- oldest event age above threshold
- high DLQ rate
- projection lag
- provider failure spike
- database connection-pool exhaustion

16. Local infrastructure

Update Docker Compose to include Kafka.

Prefer KRaft mode where practical to avoid ZooKeeper.

Add:

- Kafka broker
- Kafka UI
- topic initialization
- health checks
- persistent local volumes where appropriate

Suggested local ports:

Kafka: 9092
Kafka UI: 8080 or another free port

Avoid port conflicts with existing services.

Update Kubernetes manifests or Helm configuration with:

- Kafka bootstrap servers
- consumer group IDs
- topic names
- retry configuration
- resource requests/limits
- readiness/liveness probes
- autoscaling hooks

Do not attempt to deploy a production-grade Kafka cluster from scratch in the application Helm chart unless the project intentionally owns that infrastructure.

For local Kubernetes, allow:

- Strimzi, or
- Bitnami Kafka, or
- an externally configured Kafka endpoint

Document the selected approach.

17. Autoscaling and backpressure

Add worker autoscaling based on Kafka lag where supported.

Kubernetes HPA alone cannot directly scale from Kafka lag without an external metrics adapter.

Prefer KEDA for Kafka-driven scaling.

Add example KEDA ScaledObject manifests.

Configure:

- minimum replicas
- maximum replicas
- lag threshold
- cooldown period
- polling interval

Also protect downstream providers. Do not scale workers so aggressively that they exceed provider rate limits.

Document the distinction:

Kafka lag controls how much internal processing capacity is needed.
Provider quotas control the maximum useful delivery concurrency.

18. Testing

Add unit, integration, contract, resilience, and load tests.

Unit tests

Cover:

- API validation
- rate limiting
- idempotency
- event serialization
- partition-key selection
- orchestrator routing
- preference evaluation
- template validation
- transient/permanent failure classification
- retry policy
- status aggregation

Integration tests

Use Testcontainers for:

- Kafka
- PostgreSQL
- Redis

Test:

1. API publishes request.
2. Orchestrator consumes it.
3. Orchestrator publishes channel command.
4. Worker consumes command.
5. Worker publishes result.
6. Projection updates status.
7. GET status returns final state.

Test duplicate delivery:

- publish the same event twice
- verify only one provider side effect or one idempotently recorded delivery

Test broker outage:

- Kafka unavailable
- API returns controlled error
- no hanging request

Test projection outage:

- stop PostgreSQL
- events remain in Kafka
- restart projection
- projection catches up

Test worker outage:

- stop worker
- lag grows
- restart worker
- backlog drains

Test poison event:

- malformed or permanently invalid event
- verify DLQ behavior

Test rebalance:

- run multiple consumers
- add/remove instance
- verify no lost deliveries

Contract tests

Add compatibility tests for all versioned Kafka event schemas.

Load tests

Create k6 scenarios:

Intake throughput

Goal:

- determine maximum sustainable accepted HTTP requests per second
- measure p50, p95, p99
- verify controlled rejection instead of timeout

Test stages:

warm-up
steady load
step increase
stress load
spike load
recovery

Burst test

Send a sudden burst much larger than normal traffic.

Verify:

- API remains responsive
- accepted requests enter Kafka
- excess traffic gets 429/503 quickly
- no unbounded memory growth
- backlog drains after the burst

End-to-end throughput

Measure:

- accepted requests per second
- orchestrated deliveries per second
- provider-worker throughput
- projection update rate
- final delivery latency

Backpressure test

Artificially slow the email provider.

Verify:

- email topic lag increases
- API can continue accepting within configured limits
- worker local queue stays bounded
- retry topics work
- SMS/push channels remain unaffected
- provider concurrency does not exceed configured maximum

Database outage test

Stop PostgreSQL projection database during load.

Verify:

- intake continues while Kafka is healthy
- projection consumer stops committing failed events
- no events are lost
- projection catches up after recovery

Output a machine-readable load-test summary.

19. Performance acceptance criteria

Create configurable acceptance thresholds.

Initial local targets may be modest and hardware-dependent, but tests must report:

- accepted RPS
- rejected RPS
- timeout count
- p50/p95/p99 API latency
- Kafka producer latency
- orchestrator throughput
- worker throughput
- end-to-end latency
- consumer lag
- backlog drain time
- database write rate
- CPU
- memory
- connection pool utilization
- GC pauses

Critical behavioral criteria:

- no uncontrolled HTTP timeouts during overload
- overload produces explicit 429/503 responses
- no unbounded queues
- no event loss during consumer restarts
- duplicate events do not create duplicate logical deliveries
- Kafka backlog drains after traffic returns below capacity
- projection recovers after database outage
- DLQ events are inspectable and replayable

Do not claim “100k throughput” without specifying:

- 100k what
- per second, per minute, or total
- notification requests or channel deliveries
- hardware
- number of partitions
- number of worker replicas
- payload size
- provider behavior
- test duration

20. Documentation

Create or update:

docs/kafka-first-architecture.md
docs/backpressure-and-admission-control.md
docs/event-contracts.md
docs/delivery-semantics.md
docs/retry-and-dlq.md
docs/storage-strategy.md
docs/rabbitmq-to-kafka-migration.md
docs/load-testing.md
docs/failure-scenarios.md
docs/local-run-kafka.md

Include Mermaid diagrams for:

- current architecture
- target architecture
- intake sequence
- successful delivery sequence
- retry sequence
- projection rebuild
- Kafka outage
- provider slowdown
- database outage

Document trade-offs honestly:

- Kafka improves durable buffering but does not make API capacity unlimited.
- Admission control is still required.
- At-least-once processing requires idempotent consumers.
- Kafka transactions do not make external provider calls exactly-once.
- Eventual consistency means status may not be visible immediately.
- PostgreSQL remains acceptable as a query projection until benchmarks prove otherwise.
- DynamoDB may be a good production alternative for predictable high-scale access patterns.
- MongoDB is not adopted without a concrete document-model or scaling requirement.

21. Implementation phases

Implement in this order.

Phase 1: Analysis and baseline

Before changing code:

1. Inspect all modules.
2. Document the exact current request and delivery flow.
3. Identify current:
   - entities
   - outbox schema
   - RabbitMQ exchanges/queues
   - idempotency logic
   - retry logic
   - metrics
   - load tests
4. Run existing tests.
5. Run a baseline load test.
6. Record current bottlenecks and results.

Create:

docs/kafka-migration-baseline.md

Phase 2: Kafka infrastructure and contracts

Implement:

- Kafka in Docker Compose
- Kafka UI
- topic initialization
- shared event contracts
- serialization tests
- Kafka health checks
- configuration properties

Do not change production flow yet.

Phase 3: Kafka-first intake

Implement:

- API publishes NotificationRequested
- 202 Accepted
- Redis acceptance cache
- rate limiting
- bounded concurrency
- controlled Kafka failure responses
- metrics
- feature flag for old and new intake paths

Keep old RabbitMQ delivery flow temporarily.

Phase 4: Orchestrator

Implement:

- notification-orchestrator-service
- request consumption
- idempotency
- template/preference resolution
- channel routing
- RabbitMQ compatibility publisher first
- status events

Phase 5: Template/preference projections

Implement:

- template events
- preference events
- service-local outboxes if needed
- orchestrator local projections
- remove synchronous hot-path calls
- projection rebuild support

Phase 6: Kafka worker migration

Migrate workers one by one:

1. Email
2. SMS
3. Push
4. Webhook
5. In-app

For each worker:

- Kafka consumer
- idempotency
- retry topics
- DLQ
- result event
- metrics
- integration tests

Phase 7: Notification projection service

Implement:

- accepted notification projection
- status projection
- delivery projection
- admin query compatibility
- rebuild process
- PostgreSQL optimizations

Phase 8: Disable RabbitMQ path

After parity tests:

- make Kafka delivery default
- keep temporary rollback configuration
- update docs
- mark RabbitMQ flow deprecated

Phase 9: Load testing and tuning

Implementation note (2026-07-11): scenario scripts, machine-readable summaries, backlog-drain timing, configurable thresholds, and tuning properties are present. They have not been executed for the final architecture.

Run:

- baseline comparison
- steady load
- burst
- stress
- provider slowdown
- Kafka restart
- projection DB outage
- worker restart

Tune:

- partitions
- consumer concurrency
- producer batching
- worker concurrency
- database batch size
- connection pools
- retries
- rate limits

Phase 10: Optional DynamoDB projection

Implementation note (2026-07-11): the optional repository, LocalStack provisioning, gated contract test, and Kubernetes overlay are present. PostgreSQL remains the default, and no comparison has been run.

Only after the Kafka/PostgreSQL architecture is stable:

- define concrete access patterns
- add repository implementation
- use DynamoDB Local or LocalStack
- add contract tests
- compare complexity and performance
- do not replace PostgreSQL without evidence

Coding standards

- Java 17 or the project’s current Java version
- Spring Boot conventions already used in the repository
- constructor injection
- immutable DTOs where practical
- no shared persistence entities between services
- explicit configuration properties
- validation at service boundaries
- structured logging
- correlation IDs and trace IDs
- no secrets committed
- no unbounded executors
- no Thread.sleep for Kafka retries
- no swallowed exceptions
- no generic catch-and-ack behavior
- no high-cardinality metric labels
- migrations through Flyway or the project’s existing migration framework
- maintain backward compatibility where reasonable

Required deliverables

At the end, provide:

1. Summary of implemented changes
2. Updated architecture diagram
3. List of new services/modules
4. List of new Kafka topics
5. Event contract documentation
6. Migration explanation
7. Local run instructions
8. Test instructions
9. Load-test instructions
10. Benchmark results
11. Known limitations
12. Remaining production-readiness gaps
13. Rollback procedure
14. Explanation of where outbox remains and why
15. Explanation of delivery guarantees
16. Explanation of backpressure behavior
17. Explanation of storage decision

Required commands

Ensure these or equivalent commands work:

mvn test
mvn -f services/pom.xml test
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
docker compose ps

Add documented commands for:

# Create or verify Kafka topics

./scripts/kafka/create-topics.sh

# Run integration tests

./scripts/test/integration.sh

# Run intake load test

./scripts/load/intake.sh

# Run end-to-end load test

./scripts/load/end-to-end.sh

# Run burst test

./scripts/load/burst.sh

# Run failure scenarios

./scripts/test/failure-scenarios.sh

# Rebuild notification projection

./scripts/projection/rebuild.sh

Execution behavior

Start by inspecting the repository and writing a concrete implementation plan mapped to existing files and modules.

Then implement Phase 1 through Phase 3 first.

Do not attempt all phases in one uncontrolled change.

After Phase 3:

1. Run tests.
2. Run the local stack.
3. Run the intake load test.
4. Report:
   - files changed
   - tests passed/failed
   - current architecture
   - benchmark results
   - issues found
   - exact next phase

Continue into later phases only when the repository remains runnable and tests are passing.

When existing code conflicts with this specification, preserve the architectural intent but adapt implementation details to the codebase. Document every significant deviation and its reason.
