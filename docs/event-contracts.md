# Event Contracts

Versioned JSON DTOs live in `services/shared-event-contracts`. Persistence entities are not shared.

Phase 2 provides:

- `NotificationRequested`
- `DeliveryRequested`
- `DeliveryResult`
- `NotificationStatusChanged`
- `AggregateChangedEvent` for version-aware template/preference events

Every event has a globally unique string `eventId` and integer `schemaVersion`. Template/preference changes also carry `aggregateVersion`; projection consumers must ignore a version older than the stored version.

`NotificationRequested` v1 contains notification/request/event IDs, tenant, product and idempotency key, template ID/key, recipient addresses, requested channels, variables, timestamp, and schema version. JSON serialization compatibility tests assert the v1 names and round-trip behavior.

`DeliveryRequested` also carries retry lineage: original topic/partition/offset, attempt number, first/last failure timestamps, and bounded error code/message fields.

Compatibility rules:

- Add optional fields for backward-compatible v1 evolution.
- Never rename or change the meaning/type of an existing field in place.
- Introduce a new schema version for incompatible changes.
- Consumers must tolerate unknown fields.
- Do not use Java type headers as the wire contract; topic and schema version identify the payload.

## Test result

`EventContractSerializationTest` passed all 3 tests on 2026-07-10 for the Phase 3 contract. It now also asserts `productId`; the Phase 4–10 changes have not been run yet.
