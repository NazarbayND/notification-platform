# Storage Strategy

PostgreSQL remains the query-store choice. Phase 3 removes notification database writes from Kafka-mode HTTP intake, which addresses the most direct write amplification without adding another database technology.

The permanent notification projection and its repository abstraction are Phase 7 work. Expected access patterns are get notification by ID, list tenant notifications by time, list user notifications by time, update delivery state idempotently, and append attempts. PostgreSQL should first be tuned with appropriate indexes, batching, pooling, retention, and evidence-based partitioning.

DynamoDB remains an optional later implementation. A production design would use notification ID lookup, tenant/time and user/time indexes, conditional updates, TTL retention, and sharded keys for hot tenants. DynamoDB Local results are not evidence of AWS production capacity. MongoDB is not adopted without a demonstrated document-model or scaling need.
