# casehub-desiredstate

[![Build](https://github.com/casehubio/casehub-desiredstate/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/casehub-desiredstate/actions/workflows/publish.yml) [![Open PRs](https://img.shields.io/github/issues-pr/casehubio/casehub-desiredstate)](https://github.com/casehubio/casehub-desiredstate/pulls)

Generic desired-state management runtime for the [casehubio](https://github.com/casehubio) platform. Domain-agnostic planner, reconciliation loop, and fault policy engine. Domains plug in via four SPIs: `GoalCompiler`, `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`.

Workflow execution delegates to `casehub-engine-flow`. Human provisioning steps generate `casehub-work` WorkItems. The same runtime handles agent topology, IoT device state, infrastructure provisioning, and compliance posture.

See domain implementations: [casehub-ops](https://github.com/casehubio/casehub-ops).
