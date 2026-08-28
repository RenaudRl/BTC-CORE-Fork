## ADDED Requirements

### Requirement: Extension telemetry is opt-in and bounded

The runtime MUST provide opt-in counters and duration observations by extension without blocking
game threads or recording player payload contents.

#### Scenario: Disabled telemetry

- **WHEN** telemetry is disabled
- **THEN** no per-event observation or I/O is performed

#### Scenario: Enabled telemetry

- **WHEN** an extension registers a bounded metric
- **THEN** counters and durations are exposed with reset and snapshot operations
