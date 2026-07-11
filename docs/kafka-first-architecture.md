# Kafka-First Architecture

## Current migration state (Phases 1–8 implemented)

Kafka is the first durable intake write and the default delivery transport. The legacy synchronous DB/outbox and RabbitMQ path remains available for rollback.

```mermaid
flowchart LR
    C[Client] --> API[notification-api-service]
    API --> AC[Redis admission + acceptance cache]
    API -->|durable NotificationRequested| K[(notification.requests.v1)]
    K --> ORCH[notification-orchestrator-service]
    ORCH --> CH[(Kafka channel topics)]
    CH --> W[Kafka channel workers]
    W --> RES[(delivery results)]
    K & RES --> PROJ[notification-projection-service]
    API -. rollback flag .-> DB[(legacy DB/outbox)]
```

UUID identifiers are retained for API and PostgreSQL compatibility; event contracts treat IDs as opaque strings. The current HTTP contract remains single-recipient/single-channel, while the orchestrator is structured to emit a child command for every requested channel.

```mermaid
flowchart LR
    C[Client] --> API[Admission-controlled API]
    API --> REQ[(notification.requests.v1)]
    REQ --> ORCH[notification-orchestrator-service]
    TE[(template.events.v1)] --> ORCH
    PE[(preference.events.v1)] --> ORCH
    ORCH --> E[(notification.email.v1)]
    ORCH --> S[(notification.sms.v1)]
    ORCH --> PU[(notification.push.v1)]
    ORCH --> WH[(notification.webhook.v1)]
    ORCH --> IA[(notification.in-app.v1)]
    E & S & PU & WH & IA --> W[Channel workers]
    W --> RES[(notification.delivery-results.v1)]
    ORCH --> ST[(notification.status-events.v1)]
    REQ & RES & ST --> PROJ[notification-projection-service]
    PROJ --> Q[(PostgreSQL query store)]
```

## Intake sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant A as notification-api-service
    participant R as Redis
    participant K as Kafka
    C->>A: POST /notifications
    A->>A: schema/body/fan-out validation
    A->>R: global + tenant rate checks
    A->>A: acquire concurrency permit
    A->>K: NotificationRequested (acks=all)
    alt acknowledged before deadline
        K-->>A: record metadata
        A->>R: acceptance + idempotency cache (20m)
        A-->>C: 202 ACCEPTED
    else timeout/buffer/broker failure
        A-->>C: 503 + Retry-After: 1
    end
```

`ACCEPTED` means Kafka acknowledged the command under the configured producer durability settings. It does not mean rendering, preference evaluation, scheduling, or provider delivery succeeded. The permanent query projection can lag; status lookup checks the projection first, legacy storage second, then Redis acceptance state.

## Ordering and partitioning

Intake and delivery records use `tenantId:recipientId` as the stable key. Ordering exists only within a partition. Global ordering is not provided. Partition count is configurable and is never assumed to equal worker count.

## Successful delivery sequence

```mermaid
sequenceDiagram
    participant O as Orchestrator
    participant C as Channel topic
    participant W as Worker
    participant P as Provider
    participant R as Result topic
    participant Q as Projection
    O->>C: DeliveryRequested
    C->>W: at-least-once consume
    W->>W: durable deduplication
    W->>P: provider call + delivery idempotency key
    P-->>W: success
    W->>R: DeliveryResult(DELIVERED)
    R->>Q: project result idempotently
```

## Projection rebuild

```mermaid
flowchart LR
    STOP[Stop projection consumers] --> CLEAR[Create/clear rebuild tables]
    CLEAR --> SEEK[Use a new consumer group from earliest]
    SEEK --> REPLAY[Replay requests, statuses, results]
    REPLAY --> SWAP[Validate counts and lag, then swap]
    SWAP --> RESUME[Resume normal consumer group]
```

## Outbox decision

Kafka-first notification intake removes the initial database/Kafka dual write: the API does not create notification, delivery, or outbox rows in Kafka mode. The old tables and code remain for rollback. The orchestrator, template service, and preference service each use a service-owned transactional outbox for their database/event boundary.
