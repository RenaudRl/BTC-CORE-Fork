## ADDED Requirements

### Requirement: Offline progress is owned and fenced

The platform MUST resolve an island by its persisted world name and MUST reject catch-up for a
world that is absent, unowned, or claimed by a backend with an older fencing token.

#### Scenario: Unknown world

- **WHEN** a world-load or chunk-resume callback has no canonical island ownership
- **THEN** no catch-up handler runs and a bounded diagnostic is emitted

#### Scenario: Stale backend

- **WHEN** a backend presents a lease token older than the canonical MySQL token
- **THEN** the operation is rejected without changing island state

### Requirement: Resume callbacks are Folia-safe

The platform MUST dispatch activation and resume callbacks on the global or region context that
owns the world/chunk. A handler MUST NOT be invoked for a chunk that is unloaded or not owned.

#### Scenario: Chunk reload

- **WHEN** an owned chunk reaches the resume state after being unloaded
- **THEN** registered handlers receive one bounded context and the callback is associated with the
  live region thread

### Requirement: Catch-up operations are journalled and replay-safe

The platform MUST record one durable entry per handler per catch-up operation, keyed on the pair
`(operation_id, system_key)`. A re-run of an operation MUST be reported as a replay and MUST leave
the existing entry unchanged. A journal failure MUST NOT fail the catch-up, and MUST NOT cause the
window to be committed when the work itself did not complete.

#### Scenario: Crash between mutation and commit

- **WHEN** a backend dies after handlers ran but before the window was committed
- **THEN** the window stays open, the re-run writes under the same key, and the second write is
  reported as a replay rather than counted a second time

#### Scenario: Journal unavailable

- **WHEN** the journal store cannot be reached during an operation
- **THEN** the catch-up outcome is unchanged, the loss is logged, and no window is committed that
  would not have been committed with a working journal

### Requirement: One runtime plugin is delivered

The BTC-CORE distribution MUST expose one server plugin named `BTCCore` containing ASWM and bridge
services, with one descriptor and one documented deployable plugin jar.

#### Scenario: Clean installation

- **WHEN** the server is started with the paperclip and `btccore-plugin` only
- **THEN** ASWM world loading, bridge health and BTC-CORE APIs initialize without `BTCBridge` or
  `ASPaperPlugin` as a second runtime plugin
