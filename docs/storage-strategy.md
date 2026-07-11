# Storage Strategy

PostgreSQL remains the query-store choice. Kafka-mode HTTP intake avoids notification database writes, and `notification-projection-service` owns the rebuildable query schema.

The projection repository supports notification lookup, tenant/time listing, idempotent delivery updates, and append-oriented attempts. PostgreSQL should be tuned with batching, pooling, retention, and evidence-based partitioning before considering another database.

## Optional DynamoDB implementation

Set `NOTIFICATION_PROJECTION_STORE=dynamodb` and activate the `dynamodb` Spring profile to replace the PostgreSQL repository. PostgreSQL remains the default because the DynamoDB path has not been benchmarked or operationally validated.

The implementation uses four tables:

| Table | Primary key | Access patterns |
| --- | --- | --- |
| `notification-projections` | `notificationId` | ID lookup; `tenant-requested-index` for tenant/time and `user-requested-index` for tenant/user/time listing |
| `notification-deliveries` | `deliveryId` | Delivery update; `notification-updated-index` for notification deliveries |
| `notification-delivery-attempts` | `eventId` | Idempotent append of attempts |
| `notification-processed-events` | `consumerEventId` | Conditional consumer deduplication |

All tables use the `expiresAt` TTL attribute. Request, status, attempt, delivery, and processed-event writes use DynamoDB transactions where an event/database atomicity boundary is required. AWS credentials use the default provider chain unless explicit local credentials are configured.

Compatibility with the existing offset-based admin API requires scans when no tenant or notification key is supplied. That is intentionally not presented as a high-scale access pattern. A production DynamoDB API should use tenant-scoped cursor pagination, avoid global dashboard scans, maintain aggregate counters, and introduce deterministic tenant-key sharding only when measured hot partitions justify it.

LocalStack results are useful for contract compatibility, not evidence of AWS production throughput, latency, adaptive capacity, throttling, or cost. MongoDB is not adopted without a demonstrated document-model or scaling requirement.

Production tables, alarms, point-in-time recovery, encryption, backups, capacity policy, and IAM grants must be managed by the deployment environment's infrastructure-as-code. The LocalStack creation script is not production infrastructure.
