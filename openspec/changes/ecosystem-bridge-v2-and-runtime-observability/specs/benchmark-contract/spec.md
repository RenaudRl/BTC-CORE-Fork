## ADDED Requirements

### Requirement: Benchmark runs are attributable

Every performance campaign MUST record runtime variant, artifact fingerprints, extension set,
bot count, world count, warmup, sample count and protocol phase.

#### Scenario: Valid campaign

- **WHEN** an A/B/A' campaign is started with the required manifest and clean log
- **THEN** the results contain p50, p95, p99 and method reservations

#### Scenario: Ambiguous campaign

- **WHEN** the runtime variant or artifact manifest is missing
- **THEN** the campaign is rejected as non-comparable
