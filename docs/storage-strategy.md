# Storage Strategy

PostgreSQL remains the query-store choice. Kafka-mode HTTP intake avoids notification database writes, and `notification-projection-service` owns the rebuildable query schema.

The projection repository supports notification lookup, tenant/time listing, idempotent delivery updates, and append-oriented attempts. PostgreSQL should be tuned with batching, pooling, retention, and evidence-based partitioning before considering another database.

## Database decision

PostgreSQL is the sole projection store. It already supports notification ID lookup, tenant/time and user/time indexes, transactional idempotency, append-oriented attempts, rebuilds, and the existing admin query shape without introducing another operational system.

DynamoDB was considered but not adopted. The current workload has no benchmark evidence of PostgreSQL saturation, predictable key-volume requirement, or cost advantage that would justify DynamoDB-specific indexes, cursor APIs, IAM, capacity policy, backups, and consistency trade-offs. MongoDB is likewise not adopted without a demonstrated document-model or scaling requirement.
