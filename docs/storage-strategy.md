# Storage Strategy

PostgreSQL remains the query-store choice. Kafka-mode HTTP intake avoids notification database writes, and `notification-projection-service` owns the rebuildable query schema.

The projection repository supports notification lookup, tenant/time listing, idempotent delivery updates, and append-oriented attempts. PostgreSQL should be tuned with batching, pooling, retention, and evidence-based partitioning before considering another database.

DynamoDB remains an optional later implementation. A production design would use notification ID lookup, tenant/time and user/time indexes, conditional updates, TTL retention, and sharded keys for hot tenants. DynamoDB Local results are not evidence of AWS production capacity. MongoDB is not adopted without a demonstrated document-model or scaling need.
