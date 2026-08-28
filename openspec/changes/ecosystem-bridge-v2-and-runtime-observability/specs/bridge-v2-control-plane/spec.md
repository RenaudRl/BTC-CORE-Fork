## ADDED Requirements

### Requirement: Control messages are authenticated

The backend MUST reject control messages whose source is a client Player or whose backend identity
is not registered for the channel. V2 messages MUST use the canonical envelope fields version,
messageId, type, sourceBackend, targetBackend, issuedAt, expiresAt and payload. Target servers
MUST be selected from an allowlist.

#### Scenario: Client-originated control message

- **WHEN** a Player-originated plugin message requests a transfer, preload, or party warp
- **THEN** the backend rejects it and emits a bounded security reason

#### Scenario: Authorized backend message

- **WHEN** a registered backend sends a valid control envelope for an allowed target
- **THEN** the message is dispatched exactly once

### Requirement: Control messages are bounded and replay-safe

The codec MUST reject oversized, malformed, expired, or duplicate messages before dispatch. The
canonical response types are ack, nack and world_load_failed; kind and correlationId aliases MUST
not be accepted.

#### Scenario: Duplicate message

- **WHEN** the same messageId is received within the deduplication window
- **THEN** no second transfer or preload is started and an idempotent ACK is returned

### Requirement: World preload reports truth

The backend MUST emit WorldLoaded only after the requested world is actually available and MUST
emit a typed failure otherwise.

#### Scenario: Loader failure

- **WHEN** the world loader or runtime adapter cannot load the requested world
- **THEN** the proxy receives WorldLoadFailed and never a false success
