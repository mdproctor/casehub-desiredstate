# 0002 — Custom YAML surface over existing tools (CUE, ytt, Terraform HCL)

Date: 2026-09-02
Status: Accepted

## Context and Problem Statement

The desiredstate YAML surface provides variable resolution, forEach expansion, conditional
inclusion, reusable modules with parameters, parameter validation with type-aware constraints,
graph rules, and graph invariants. This feature set overlaps substantially with existing
configuration languages and tools. Before continuing to invest in the custom implementation,
we evaluated whether an existing tool should be adopted instead.

## Decision Drivers

* The YAML surface is embedded in a Java/Quarkus build pipeline — YAML compiles to
  `DesiredStateGraph` via Quarkus build extensions at augmentation time
* Operators write YAML, not code — the surface must feel like configuration, not programming
* The graph-aware features (pattern matching rules, structural invariants, cardinality
  constraints) have no equivalent in any existing tool
* The module system and parameter validation are shared across CaseHub repos via
  `casehub-platform-yaml-core`

## Considered Options

### Option A — Adopt CUE

CUE is a constraint-based configuration language where types and constraints are unified.
It has a module/package system, a standard library with constraint functions
(`strings.MaxRunes`, `time.Format`), and can validate YAML via `cue vet`. Developed at
Google by the co-creator of Borg Configuration Language.

Strengths: constraints are first-class (not annotations on a separate type system),
module imports work like Go packages, `cue vet` integrates into CI pipelines.

Rejected because: CUE is Go-based. There is no Java implementation. Embedding CUE
evaluation in a Quarkus build extension would require either a Go subprocess (latency,
deployment complexity) or a JNI bridge (fragile, maintenance burden). CUE also has no
concept of graph structure — nodes, edges, dependency traversal, pattern matching are
outside its domain.

### Option B — Adopt ytt (Carvel)

ytt is VMware's YAML templating tool. It understands YAML structure (not text templating),
has data values with schema validation, functions via Starlark (sandboxed Python-like),
overlays for declarative patching, and modular template libraries.

Strengths: structural YAML awareness avoids quoting and indentation problems that plague
text-based templating. Starlark gives real programming constructs. Battle-tested in
production Kubernetes deployments.

Rejected because: ytt is Go-based, same embedding problem as CUE. Starlark scripting
is more powerful than needed — we want declarative YAML, not an embedded scripting
language. ytt has no graph awareness.

### Option C — Adopt Terraform HCL

HCL is Terraform's configuration language. It has modules with parameters and outputs,
validation blocks with custom conditions and error messages, `for_each` iteration,
conditional resources, and a mature type system.

Strengths: module outputs with chaining (`module.db.connection_url`) are the most
mature implementation of cross-module composition. The validation model
(`condition` + `error_message`) is expressive.

Rejected because: HCL is a standalone language with its own parser, evaluator, and
state management. It cannot be embedded as a library in a Java build pipeline. Terraform's
module system is tightly coupled to its provider/resource model. The graph operations
(dependency ordering, plan/apply) are hardcoded to infrastructure provisioning.

### Option D — Custom YAML surface (chosen)

Build variable resolution, forEach, conditionals, modules, and parameter validation as
Java libraries in `casehub-platform-yaml-core`. Build graph rules and invariants as
separate engines (currently in desiredstate, future extraction to `graph-core`).

Strengths: native Java — embeds in Quarkus build extensions with zero external
dependencies. YAML stays declarative — no scripting language. Graph-aware features
(pattern matching, structural invariants, cardinality constraints) are purpose-built
for the domain. Shared across all CaseHub repos via the platform BOM.

Trade-offs: we maintain the templating and module primitives ourselves. These are
well-understood patterns (every IaC tool implements them), but they are still code
we own. The parameter constraint model mirrors CloudFormation's — not novel, but
not borrowed either.

## Decision

Option D — custom YAML surface. The embedding requirement (Java/Quarkus build pipeline)
rules out CUE, ytt, and HCL. The graph-aware features have no equivalent in any
existing tool and justify a purpose-built system. The templating and module primitives
are table stakes that we implement because the embedding constraint prevents adoption.

## Consequences

* The YAML primitives (variable resolution, forEach, modules, parameter validation)
  live in `casehub-platform-yaml-core` and are maintained as platform infrastructure
* The graph engines (rules, invariants, pattern matching) will be extracted to
  `graph-core` (platform#267) with a `GraphView` + reader/adapter pattern
* New YAML features should be evaluated against prior art (CloudFormation, Terraform,
  CUE) before design — the concepts are well-established and we should learn from
  existing implementations rather than designing from scratch
* If a Java CUE implementation emerges, this decision should be revisited — CUE's
  constraint-as-type model is architecturally superior to our separate type + constraint
  approach

## References

* [CUE Language](https://cuelang.org/) — constraint-based configuration, Go implementation
* [Carvel ytt](https://carvel.dev/ytt/) — structural YAML templating, Go implementation
* [Terraform HCL](https://github.com/hashicorp/hcl) — HashiCorp Configuration Language
* [CloudFormation Parameters](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/parameters-section-structure.html) — MinLength/MaxLength/AllowedValues/AllowedPattern
* casehubio/platform#252 — yaml-core module system
* casehubio/platform#267 — graph-core extraction
* `specs/issue-128-migrate-yaml-core/2026-09-02-yaml-core-migration-context.md` §3 — prior art analysis
