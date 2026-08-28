## ADDED Requirements

### Requirement: Runtime compatibility is observable

The server MUST expose a startup snapshot containing fork, engine variant, plugin/API versions,
capabilities and persistence/bridge state without secrets.

#### Scenario: Compatible startup

- **WHEN** the expected BTC-CORE and Typewriter artifacts are loaded
- **THEN** the snapshot reports READY and lists the active capabilities

#### Scenario: Artifact mismatch

- **WHEN** an expected adapter or artifact is missing or incompatible
- **THEN** the snapshot reports DEGRADED with a stable reason and no silent success
