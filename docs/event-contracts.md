# Event Contracts

Versioned JSON DTOs live in `services/shared-event-contracts`. Persistence entities are not shared.

The contracts module provides:

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

`EventContractSerializationTest` covers the v1 field names, `productId`, and JSON round-trip compatibility. See the repository cleanup report or current CI run for the latest execution result.
