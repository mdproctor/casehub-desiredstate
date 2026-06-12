# Desired State Management — Research Document

**Date:** 2026-06-07
**Status:** Research / Pre-Design
**Next step:** Brainstorm formal design of generic runtime

---

## 1. Executive Summary

This document captures the research and analysis from an exploratory session into a generalised Desired State Management (DSM) system for CaseHub. The core idea is a platform-level primitive that allows any domain to declare a desired state as a goal graph, generate transition workflows automatically, and continuously reconcile actual state against desired state via an event-driven loop.

The primary motivation is CaseHub's own agent deployment management — declaring the desired topology of agents, channels, case types, and trust configuration for a CaseHub application, and having the system provision, maintain, and self-heal that topology continuously. This makes the system directly useful to CaseHub's core mission (agents working together) rather than a speculative capability.

A generic runtime is designed first, with domains layered on top via SPIs. The CaseHub deployment domain is the primary target. IoT is the secondary domain. Infrastructure provisioning (Terraform-style) and compliance posture serve as additional demonstration domains, proving the generic runtime extends cleanly to other concerns.

---

## 2. The Core Concept

### 2.1 The Problem

Infrastructure and system configuration is currently managed in one of two paradigms:

- **Procedural** (Ansible playbooks, shell scripts) — describes _how_ to reach a state. Order-dependent, fragile to partial failure, does not continuously reconcile.
- **Declarative point-in-time** (Terraform) — describes _what_ should exist. Plans and applies on demand. Does not continuously reconcile. Drift goes undetected until the next manual run. No first-class model for human-in-the-loop steps or fault recovery.

Neither paradigm captures the full lifecycle: declare intent → plan transition → execute → reconcile continuously → recover from fault.

### 2.2 The Pattern

The desired state management pattern has three phases:

```
DECLARE   →   PLAN   →   EXECUTE   →   RECONCILE (continuous)
 goals          graph       workflows      event loop
 constraints    nodes       ordered        actual vs desired
 data           edges       transitions    fault rules
```

**Declare:** the operator expresses goals, constraints, and relevant data. These are high-level statements of intent, not implementation steps.

**Plan:** the system compiles goals + constraints into a dependency graph. Each node represents something that needs to exist (a deployed service, a configured device, a running agent, a provisioned resource). Edges represent dependencies. The planner produces an ordered set of transition workflows — the sequence of operations required to move from actual state to desired state.

**Execute:** workflows are executed. Pruning (removal) always happens before growing (addition). This ensures safe ordering: you never add something that depends on a node you are about to remove.

**Reconcile:** a continuous event-driven loop compares desired state against actual state. As workflow steps complete, events flow back and are reconciled into a live view. If a node fails, goes down, or is externally changed, the reconciliation loop detects the drift and applies fault rules.

### 2.3 Key Components

| Component | Responsibility |
|---|---|
| **Goal compiler** | Translates goals + constraints + data into a dependency graph. Domain-specific SPI. |
| **Graph model** | Directed acyclic graph of desired nodes and their dependencies. Core runtime type — domain-agnostic. |
| **Planner** | Topological sort + pruning-first ordering to produce transition workflows. Core runtime. |
| **Workflow executor** | Executes transition workflows. Delegates to casehub-engine-flow (Serverless Workflow). |
| **Reconciliation loop** | Event-driven loop comparing actual state to desired state. Core runtime. |
| **Actual state adapter** | Reads current reality from domain-specific sources. Domain SPI. |
| **Fault policy engine** | Rules that mutate the desired graph in response to fault events. Domain SPI with core primitives. |
| **Event source** | CDI events, webhooks, polling — domain-specific. Feeds the reconciliation loop. |

### 2.4 Pruning Before Growing

The ordering rule is critical for safety:

1. Generate the diff (desired graph vs actual state)
2. Identify nodes to remove and nodes to add
3. Plan removal workflows first (with dependency-aware ordering — remove leaves before roots)
4. Plan addition workflows second (with dependency-aware ordering — add roots before leaves)
5. Execute sequentially

This ensures you never have dangling dependencies or half-removed states.

---

## 3. Relationship to Terraform and Ansible

### 3.1 What Terraform Does

Terraform maintains a state file tracking resources. `terraform plan` diffs desired (HCL) against actual (state file). `terraform apply` executes the diff. Providers translate resource declarations into API calls.

The model is sound. The limitations are:

| Limitation | Detail |
|---|---|
| Point-in-time only | Drift goes undetected until the next manual `plan` run. No continuous reconciliation. |
| No human-in-the-loop | Change management (approval before destroying prod) is bolted on externally via Atlantis or Terraform Cloud. Not first-class. |
| No fault recovery model | If a resource fails to provision, Terraform errors and stops. Recovery requires manual intervention or external scripting. |
| No audit trail | State file tracks what exists; it is not tamper-evident and does not record who approved what. |
| No event-driven reconciliation | Infrastructure drifts silently. |

### 3.2 What Ansible Does

Ansible playbooks are ordered, idempotent task lists. AWX/Tower adds scheduling and approval workflows. Still fundamentally procedural: you describe steps, not intent. The system does not infer the correct ordering from a goal — the operator writes it.

### 3.3 Where the CaseHub Model Advances Both

| Capability | Terraform | Ansible | CaseHub DSM |
|---|---|---|---|
| Declarative intent | ✅ | ❌ | ✅ |
| Continuous reconciliation | ❌ | ❌ | ✅ |
| Human-in-the-loop (first-class) | ❌ | Partial (AWX) | ✅ (casehub-work WorkItems) |
| Fault policy | ❌ | ❌ | ✅ |
| Tamper-evident audit trail | ❌ | ❌ | ✅ (casehub-ledger) |
| Trust-weighted execution | ❌ | ❌ | ✅ (casehub-ledger trust scoring) |
| Agent-driven remediation | ❌ | Partial | ✅ (casehub-engine) |
| Pruning-before-growing ordering | ✅ | ❌ | ✅ |

---

## 4. Generic Framework Design

### 4.1 Design Principle: Generic First, Domains Layered

The runtime is entirely domain-agnostic. It knows about graphs, nodes, edges, planners, reconciliation loops, and fault policy primitives. It does not know what a Kubernetes pod is, what an IoT device is, or what a CaseHub agent is.

Domains plug in via SPIs:

```
casehub-desired-state (generic runtime)
    │
    ├── GoalCompiler SPI          ← domain implements
    ├── ActualStateAdapter SPI    ← domain implements
    ├── NodeProvisioner SPI       ← domain implements
    ├── FaultPolicy SPI           ← domain provides rules
    └── EventSource SPI           ← domain provides events
         │
         ├── casehub-deployment   ← CaseHub agent topology domain
         ├── casehub-iot-state    ← IoT device state domain
         ├── casehub-infra        ← Infrastructure (Terraform-style demo)
         └── casehub-compliance   ← Compliance posture domain
```

This means the graph model, planner, reconciliation loop, and workflow orchestration are written once. Each new domain contributes only the domain-specific knowledge: how to compile goals into nodes, how to read actual state, how to provision a node, and what fault rules apply.

### 4.2 Core SPI Contracts (preliminary)

**GoalCompiler** — transforms a goal declaration into a `DesiredStateGraph`:

```java
interface GoalCompiler<G> {
    DesiredStateGraph compile(G goals, Constraints constraints, DomainData data);
}
```

**ActualStateAdapter** — reads the current actual state of all nodes the domain manages:

```java
interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired);
}
```

**NodeProvisioner** — provisions or deprovisions a single node:

```java
interface NodeProvisioner {
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(ActualNode node, DeprovisionContext context);
}
```

**FaultPolicy** — given a fault event and the current desired graph, returns graph mutations:

```java
interface FaultPolicy {
    GraphMutation onFault(FaultEvent event, DesiredStateGraph current);
}
```

**EventSource** — streams actual-state events into the reconciliation loop:

```java
interface EventSource {
    Multi<StateEvent> stream();
}
```

### 4.3 Core Runtime Types

```
DesiredStateGraph
    nodes: List<DesiredNode>
    edges: List<Dependency>

DesiredNode
    id: NodeId
    type: NodeType
    spec: NodeSpec          ← domain-specific, opaque to runtime
    requiresHuman: boolean  ← if true, provision via WorkItem not automation

TransitionPlan
    removals: List<OrderedStep>
    additions: List<OrderedStep>

ReconciliationResult
    resolved: List<NodeId>
    drifted: List<NodeId>
    faulted: List<NodeId>
    mutations: List<GraphMutation>
```

### 4.4 Workflow Execution

The planner generates `TransitionPlan` containing ordered steps. Steps are executed via `casehub-engine-flow` (Serverless Workflow). This is deliberate — the DSM system does not build its own workflow execution engine. It delegates to the existing infrastructure.

For `requiresHuman: true` nodes, the provisioning step generates a `WorkItem` (casehub-work) instead of calling an automated provisioner. The reconciliation loop awaits the WorkItem completion event (via `CaseSignalSink`) before marking the node as provisioned and proceeding to dependent nodes.

### 4.5 Tier Placement

```
Foundation tier
  casehub-desired-state     ← generic runtime (no domain deps)

Integration tier
  casehub-deployment        ← CaseHub agent topology domain
                               depends on: eidos-api, qhorus-api, engine-api,
                                           claudony (provisioner), openclaw (provisioner)
  casehub-iot-state         ← IoT desired state domain
                               depends on: casehub-iot-api
```

The generic runtime has zero casehubio dependencies — it depends only on `casehub-platform-api` for Path, Preferences, and CurrentPrincipal. This makes it independently embeddable.

---

## 5. Domain Analysis

### 5.1 Infrastructure Provisioning (Terraform-style)

**Status:** Demonstration domain — validates the generic runtime against a universally understood problem.

**What it covers:** Cloud resource provisioning (VMs, databases, load balancers, networking). Kubernetes application deployment. Multi-cloud orchestration.

**Node types:**
- `KubernetesNamespace`
- `PostgreSQLInstance`
- `KubernetesSecret`
- `KubernetesDeployment`
- `KubernetesService`
- `KubernetesIngress`

**Example goal:** "Deploy a Quarkus application with a PostgreSQL database, secret management, and an ingress."

**Planner output:**
```
namespace → secret + postgres → deployment → service → ingress
```

**Reconciliation events:** Kubernetes informers (pod state, deployment health), cloud provider webhooks.

**Fault rules:** `DeploymentCrashLooping → rollback deployment node, create human escalation WorkItem`.

**Why it matters for CaseHub:** The clearest demonstration to external audiences. Every developer knows Terraform. Showing the same model in CaseHub — with continuous reconciliation, human approval gates, and an audit trail — makes the generic value immediately legible.

**Market position:** Not a market target. HashiCorp (now IBM), Pulumi, CDK, OpenTofu own this space with 1,500+ providers. The ecosystem moat is too large. This is proof-of-concept only.

---

### 5.2 IoT / Device Management

**Status:** Secondary target domain — casehub-iot is the foundation.

**What it covers:** Home automation (Home Assistant, OpenHAB), industrial IoT, building management, smart devices.

**Critical distinction — two node types:**

| Node type | Provisioning | CaseHub mechanism |
|---|---|---|
| Physical device | Human must install | `WorkItem` (casehub-work) |
| Logical configuration | Automated | `DeviceProvider`, HA/OpenHAB API |

Physical nodes do not break the model. They produce a WorkItem with installation instructions. When the human completes it, the WorkItem closes, `CaseSignalSink` signals the reconciliation loop, and the system proceeds to provision all downstream logical nodes automatically.

**Example goal:** "Provision a heating system — living room thermostat, bedroom thermostat, boiler controller, with night mode automation."

**Graph:**
```
PhysicalInstall(LivingRoomThermostat)  →  HADiscovery  →  HeatingAutomation
PhysicalInstall(BedroomThermostat)     →  HADiscovery  →  HeatingAutomation
PhysicalInstall(BoilerController)      →  HADiscovery  ──┘
                                                         └→  NightModeScene
```

Physical installs run in parallel (three WorkItems). Each completion triggers its discovery chain. Automations are created once all dependencies are satisfied.

**Reconciliation events:** `StateChangeEvent` (CDI fireAsync from casehub-iot) — carries before/after `DeviceEntity` and `changedCapabilities`. Already exists in the stack.

**Pruning:** Remove a thermostat from the goal → system creates WorkItem "physically remove/unpair the thermostat," removes dependent automations, removes HA entity.

**Why this is better than pure Terraform:** Physical installation is inherently human. Terraform cannot model this step. CaseHub can — human and automated tasks are both first-class in the same workflow.

**Existing infrastructure that maps directly:**

| DSM concept | casehub-iot implementation |
|---|---|
| Actual state | `DeviceRegistry`, `DeviceProvider.status()` |
| Provisioning (logical) | `DeviceProvider.dispatch(DeviceCommand)` |
| Reconciliation events | `StateChangeEvent` CDI bus |
| Fault detection | Device goes offline → `StateChangeEvent` with null/unavailable state |

---

### 5.3 Network / SDN / Intent-Based Networking

**Status:** Toy example candidate — validates the model in a non-trivial technical domain.

**What it covers:** Software-Defined Networking, firewall policy, traffic routing, VLAN configuration, zero-trust network policy.

**The pattern:** Intent-Based Networking (IBN) is the industry term for desired-state applied to networks. "I want encrypted, sub-10ms traffic between service A and service B with a 99.9% availability SLA" → the system generates flow rules, ACLs, and routing configuration as a graph.

**Node types:**
- `VLANSegment`
- `FirewallRule`
- `TrafficPolicy`
- `RoutingEntry`
- `TLSPolicy`

**Reconciliation events:** Network telemetry (SNMP, NETCONF/YANG, streaming telemetry). Firewall logs.

**Fault rules:** Link goes down → reroute traffic, update routing nodes, alert on SLA breach.

**Market context:** Cisco Catalyst Center (formerly DNA Center), Juniper Apstra, VMware NSX own enterprise IBN. CaseHub cannot compete here — too much domain expertise required, hardware vendor lock-in. But it validates that the generic runtime extends to a fundamentally different domain from IoT or agent management.

---

### 5.4 Compliance Posture Management

**Status:** Strong market gap — potential future product domain.

**What it covers:** Continuous compliance against regulatory frameworks (SOC2, ISO27001, GDPR, EU AI Act Art.12, DORA, NIS2). Policy drift detection and remediation.

**The gap:** Vanta, Drata, Secureframe are point-in-time audit preparation tools. They collect evidence for annual audits. Drata does continuous monitoring, but it is monitoring and alerting — not a control plane that reconciles and remediates. Nobody has built compliance posture as a desired-state runtime.

**Node types:**
- `LogRetentionPolicy` (must retain for N days)
- `EncryptionAtRestControl` (must be enabled for all storage)
- `AccessReviewControl` (must be performed quarterly)
- `IncidentResponsePlaybook` (must exist and be tested)
- `DataProcessingRecord` (GDPR Art.30)
- `AISystemRiskAssessment` (EU AI Act)

**Goal declaration:**
```yaml
compliance:
  frameworks: [SOC2-TypeII, GDPR, EU-AI-Act-Art12]
  environment: production
  auditDate: 2026-09-01
```

The compiler generates the full control graph from the framework definitions.

**Reconciliation events:** Certificate expiry, policy change events, access review deadlines, control monitoring webhooks.

**Fault rules:** Log retention policy lapses → auto-remediate if possible, else create human WorkItem. Cert expires → immediate human escalation.

**Why CaseHub fits exceptionally well:**

| Requirement | CaseHub capability |
|---|---|
| Tamper-evident audit trail | casehub-ledger (Merkle Mountain Range) |
| Human approval for remediation | casehub-work WorkItems |
| Agent-driven automated remediation | casehub-engine + claudony/openclaw |
| Trust-weighted execution | casehub-ledger trust scoring |
| GDPR Art.17 erasure | casehub-ledger LedgerErasureService |
| Compliance supplement records | casehub-ledger ComplianceSupplement |

This is the strongest potential market gap. The GRC market is ~$50B and continuous compliance is the fastest-growing segment. Regulatory tailwinds (EU AI Act, DORA, NIS2) are compounding. CaseHub's existing infrastructure maps better to this domain than any current compliance tooling.

---

### 5.5 Agent / AI Fleet Management — Primary Target

**Status:** Primary target domain. CaseHub's core mission.

**What it covers:** Declaring the desired topology of agents, channels, case types, trust configuration, and provisioners for a CaseHub application. The system provisions, maintains, and heals the topology continuously.

**Why this is the right primary domain:**

1. CaseHub is fundamentally about agents working together. Managing that topology declaratively is the natural completion of the platform.
2. Every CaseHub application (devtown, aml, clinical, life) needs agent topology management. This solves a real operational problem for all of them.
3. The actual state is already tracked by existing infrastructure — nothing new needed to read reality.
4. The provisioning layer already exists (claudony, openclaw).
5. The reconciliation events already flow (trust CDI events, agent state changes, channel events).
6. No other vendor provides this. The multi-agent orchestration market (LangChain, CrewAI, AutoGen, Microsoft Copilot Studio) is about building agent pipelines, not managing agent fleets as desired state. First-mover.

**Full goal declaration example (devtown deployment):**

```yaml
# Desired state of a CaseHub devtown deployment
agents:
  - type: analyst
    count: 3
    capabilities: [code-review, security-analysis]
    minTrustScore: 0.7
    provisioner: claudony
    model: claude-sonnet-4-6

  - type: reviewer
    count: 2
    capabilities: [approval, quality-gate]
    minTrustScore: 0.8
    provisioner: openclaw

  - type: coordinator
    count: 1
    capabilities: [routing, escalation]
    minTrustScore: 0.9
    provisioner: claudony

channels:
  - name: pr-review-work
    type: work
    participants: [analyst, reviewer]
    acl: [analyst, reviewer, coordinator]

  - name: pr-review-observe
    type: observe
    participants: [analyst, reviewer, human-pm]

  - name: pr-review-oversight
    type: oversight
    participants: [human-pm]
    deniedTypes: [EVENT]

cases:
  - type: pr-review
    workers: [code-analyzer, security-checker, approval-gate]
    sla: 4h
    breachPolicy: escalate-to-human

trust:
  routing: trust-weighted
  minObservations: 50
  fallback: availability-routing
  policies:
    - trust-routing.yaml

connectors:
  - type: slack
    workspace: casehubio
    channels: [#pr-reviews, #alerts]
```

**Mapping to actual state in existing stack:**

| Desired state element | Actual state source |
|---|---|
| Agent instances | `AgentRegistry` (casehub-eidos) |
| Agent health | `AgentStateStore` (casehub-eidos) |
| Capability health | `CapabilityHealth.probe()` (casehub-eidos) |
| Trust scores | `TrustExportService` (casehub-ledger) |
| Channel existence | `ChannelStore` (casehub-qhorus) |
| Channel ACLs | `Channel.allowedWriters` (casehub-qhorus) |
| Case type registry | `CaseHubRuntime` (casehub-engine) |
| Active WorkItems | `WorkItemStore` (casehub-work) |
| Connector configuration | `ConnectorService` (casehub-connectors) |

**Reconciliation events already flowing:**

| Event | Source | Triggers |
|---|---|---|
| `TrustScoreRoutingPublisher` CDI event | casehub-ledger | Trust threshold breach |
| `AgentStateStore` degradation record | casehub-eidos | Agent capability degraded |
| `WorkflowExecutionCompleted` | claudony | Agent session ended |
| `MessageReceivedEvent` | casehub-qhorus | Channel activity |
| `WorkItemLifecycleEvent` | casehub-work | Human task completed |

**Fault rules:**

| Fault event | Default rule | Override |
|---|---|---|
| Agent trust drops below `minTrustScore` | Suspend agent, create human review WorkItem | Domain FaultPolicy SPI |
| Agent crashes / session ends | Provision replacement via same provisioner | Configurable retry limit |
| Channel missing | Recreate channel with same ACL | — |
| Case queue backing up (WorkItems stacking) | Provision additional agents of that type | Scale limit config |
| Provisioner unhealthy (claudony/openclaw) | Escalate to human, suspend affected nodes | — |
| Required capability unavailable | Route to fallback, alert operator | Trust maturity model |

**Tier placement:**

```
Foundation tier
  casehub-desired-state    ← generic runtime

Integration tier
  casehub-deployment       ← CaseHub agent topology domain
    depends on:
      casehub-eidos-api    (actual state: agent registry, capability health)
      casehub-ledger-api   (actual state: trust scores)
      casehub-qhorus-api   (actual state: channels, commitments)
      casehub-work-api     (WorkItem provisioning for human nodes)
      casehub-engine-api   (case type registry)
      claudony-casehub     (agent provisioner)
      openclaw-casehub     (agent provisioner)
```

---

## 6. Market Gap Analysis

| Domain | Market gap | CaseHub fit | Priority |
|---|---|---|---|
| Agent/AI fleet management | High — nobody does this | Exceptional — eidos, ledger, qhorus, claudony/openclaw all map | Primary |
| Compliance posture | High — existing tools are point-in-time audit prep | Strong — ledger, work, GDPR, compliance supplement map directly | Future product |
| Regulated IoT | Medium — general MDM saturated, regulated IoT underserved | Strong — casehub-iot + work for physical nodes | Secondary |
| Infrastructure provisioning | Low — Terraform/Pulumi ecosystem moat | Weak standalone, good demo | Demo only |
| Network/SDN | Low — Cisco/Juniper hardware vendor lock-in | Requires domain expertise CaseHub lacks | Toy example |
| Container/Kubernetes | None — Kubernetes IS the market | N/A | N/A |

---

## 7. Implementation Strategy

### 7.1 Phase 1 — Generic Runtime (`casehub-desired-state`)

Build the domain-agnostic core:

- `DesiredStateGraph` model (nodes, edges, dependencies)
- `TransitionPlanner` (topological sort, pruning-first ordering)
- `ReconciliationLoop` (event-driven, diff against actual state)
- `FaultPolicyEngine` (rule evaluation, graph mutation)
- Core SPIs: `GoalCompiler`, `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, `EventSource`
- Workflow delegation to `casehub-engine-flow` for step execution
- `requiresHuman` node type → WorkItem generation via casehub-work

Validate with a minimal toy domain — infrastructure provisioning is the right choice here because it is universally understood and fully automatable (no human node complications to deal with in the first iteration).

### 7.2 Phase 2 — CaseHub Deployment Domain (`casehub-deployment`)

Implement the agent topology domain on top of the generic runtime:

- `AgentTopologyGoalCompiler` — translates YAML declarations into `DesiredStateGraph`
- `CasehubActualStateAdapter` — reads from eidos, qhorus, ledger, engine
- `ClaudonyNodeProvisioner` and `OpenclawNodeProvisioner`
- `ChannelNodeProvisioner` — creates/removes Qhorus channels
- `CasehubFaultPolicy` — default rules for trust breach, crash, scale-out

Validate against a real devtown or aml deployment.

### 7.3 Phase 3 — IoT Domain

Implement the IoT desired state domain:

- Physical node type → WorkItem generation (casehub-work)
- Logical node type → HA/OpenHAB REST API calls (casehub-iot)
- `StateChangeEvent` as the `EventSource` implementation
- Fault rules for device offline, discovery failure

### 7.4 Phase 4 — Toy Examples

At least two additional toy domain implementations to prove the runtime is genuinely generic and not accidentally coupled to CaseHub-specific concerns:

- **Infrastructure (Terraform-style):** Kubernetes namespace, deployment, service, ingress. Demonstrates fully automated provisioning with dependency ordering. Reconciliation via Kubernetes informers.
- **Compliance posture (stub):** Framework definitions → control nodes. Demonstrates a non-infrastructure, non-agent domain. Even if the remediation logic is minimal, the goal compilation and reconciliation loop show the model works.

These do not need to be production-quality. They exist to prove the SPI contracts are domain-agnostic and that two engineers working in different domains can build against the same runtime independently.

---

## 8. Relationship to Existing CaseHub Infrastructure

### 8.1 What DSM Does Not Replace

- **casehub-engine** — process orchestration (Serverless Workflow-based `CaseInstance`/`WorkOrchestrator`) remains for case-level workflow. DSM manages topology; the engine manages case execution within that topology. Complementary, not competing.
- **casehub-work** — WorkItem lifecycle management is used BY the DSM system for human nodes. Not replaced.
- **casehub-iot** — IoT device abstraction layer used BY the IoT domain implementation. Not replaced.
- **claudony / openclaw** — agent provisioners used BY the deployment domain implementation. Not replaced.

### 8.2 What DSM Adds

A declarative layer above the existing runtime. Instead of imperative configuration ("call this API, create this channel, register this agent"), operators declare intent ("I want these agents, these channels, this trust configuration") and the system manages the rest.

### 8.3 The Self-Referential Property

CaseHub uses the desired-state system to manage CaseHub deployments. The platform manages its own topology. This is a strong internal validation — the system is useful enough that we use it for ourselves — and a good story for external audiences.

---

## 9. Open Design Questions

These require formal brainstorming before implementation begins:

1. **Goal DSL format** — YAML (shown in examples above) is readable but limited. HCL (Terraform-style) is more expressive for complex constraints. A Java fluent DSL (like case definitions) is most flexible but least accessible. What is the right format for the agent topology domain specifically?

2. **Graph cycle detection** — Dependency graphs must be DAGs. How does the planner detect and report cycles in goal declarations? What is the user-facing error model?

3. **Partial reconciliation** — If the system is in an intermediate state (some nodes provisioned, some not), can operators declare a new desired state mid-flight? What happens to in-progress workflows?

4. **Desired state versioning** — Should the desired state declaration be version-controlled (Git-backed, like GitOps)? Or is it live configuration stored in a database? Both have tradeoffs.

5. **Multi-tenancy** — Each CaseHub tenant declares their own desired topology. How does the reconciliation loop partition by tenant? Does each tenant get an isolated loop?

6. **Scale limits** — The deployment domain needs to know when NOT to auto-scale (don't provision 1,000 agents in response to a queue spike). How are scale limits expressed in the goal declaration?

7. **Idempotency of provisioners** — `NodeProvisioner.provision()` must be idempotent (calling it twice has the same result as calling it once). How is this enforced at the SPI level?

8. **Reconciliation frequency** — Event-driven is correct for most cases. But what about drift that generates no events (silent failure, manual out-of-band change)? A periodic re-sync is needed. What is the right interval and how is it configured?

9. **FaultPolicy composition** — Multiple fault policies may apply to the same fault event. How are they composed? First match? All applied in priority order? Conflict resolution?

10. **WorkItem integration for human nodes** — The WorkItem created for a physical node needs structured instructions ("install thermostat model X in room Y, then scan QR code to pair"). How does the `NodeSpec` carry the human-readable provisioning instructions through to the WorkItem content?

---

## 10. References and Related Work

**Academic / theoretical foundation:**
- Control theory: closed-loop control systems as the mathematical basis for reconciliation loops
- [Borg, Omega, and Kubernetes — Burns et al., 2016](https://research.google/pubs/borg-omega-and-kubernetes/) — the canonical paper on desired-state control planes
- [Desired State Systems — Branislav Jenco](https://branislavjenco.github.io/desired-state-systems/) — the pattern appearing across the full stack

**Industry implementations:**
- Terraform (HashiCorp/IBM) — declarative IaC, point-in-time
- Kubernetes — control loop architecture, operators pattern
- ArgoCD — GitOps reconciliation (desired state in Git vs actual cluster state)
- Cisco Catalyst Center / Juniper Apstra — Intent-Based Networking
- [Esper MDM](https://www.esper.io/blog/lighten-your-it-burden-with-desired-state-management) — desired state for device fleets
- Microsoft DSC v3 (2025) — agentless, cross-platform desired state configuration

**Market context:**
- Network automation market: ~$43B by 2034, CAGR 23%
- GRC/compliance market: ~$50B, continuous compliance fastest-growing segment
- [SDN policy automation research (2024)](https://www.sciencedirect.com/science/article/abs/pii/S0167404824001512)
- [Reconfigurable Manufacturing Systems](https://www.sciencedirect.com/science/article/pii/S027861252100056X)

**Related CaseHub specs:**
- `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` (casehub-iot) — IoT domain foundation
- `docs/superpowers/specs/2026-06-05-life-layer9-home-automation.md` — home automation Layer 9

---

*This document captures exploratory research. No implementation decisions have been committed. Formal design begins with a brainstorming session (`superpowers:brainstorming`) before any code is written.*
