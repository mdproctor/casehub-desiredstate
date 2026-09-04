# Typed Module Expansion

The YAML module system started simple: a module declares parameters and nodes, you import it with an alias, the expander prefixes IDs and wires dependencies. Clean enough for the monitoring and notification modules in our pipeline example.

Then the module system moved to `yaml-core` as a shared platform primitive. And immediately a design tension appeared.

## The Format Problem

yaml-core is domain-agnostic. It doesn't know what "nodes" or "rules" or "invariants" are — those are desiredstate concepts. So yaml-core's `YamlModule` holds generic sections:

```java
record YamlModule(String name,
    Map<String, YamlModuleParameter> parameters,
    Map<String, YamlModuleOutput> outputs,
    Map<String, Map<String, Object>> sections) {}
```

`Map<String, Map<String, Object>>` — raw maps all the way down. The type safety that desiredstate had with typed `nodes()`, `rules()`, `invariants()` accessors was gone.

Worse, the YAML format wanted to change. yaml-core expected a `sections:` wrapper:

```yaml
# yaml-core expected this
module:
  name: monitoring
sections:
  nodes:
    monitor: { ... }
```

But desiredstate's existing format — and the natural YAML — looks like this:

```yaml
# what users actually write
module:
  name: monitoring
nodes:
  monitor: { ... }
```

Two tensions: type safety lost, and format friction introduced. The obvious fix (write an adapter, change the format) solves neither cleanly. An adapter just moves the raw maps around. Changing the format punishes users for a platform abstraction they shouldn't know about.

## The ContextBridge Analogy

casehub-engine has the same problem in a different domain. `CaseContext` is opaque to the engine — each case type defines its own context shape. The engine handles persistence, event routing, lifecycle. The domain owns the type. The bridge between them:

```java
interface ContextBridge<T> {
    T initialise(CaseContext context, JsonNode input);
    JsonNode serialise(T context);
    T deserialise(JsonNode payload);
    Class<T> contextType();
}
```

The framework never inspects `T`. It serialises, deserialises, and passes it through. The domain provides the conversion logic.

The same pattern solves both module tensions.

## ModuleBridge<T>

<svg viewBox="0 0 720 320" xmlns="http://www.w3.org/2000/svg" style="max-width:720px;font-family:system-ui,sans-serif">
  <!-- Background -->
  <rect width="720" height="320" fill="#fafafa" rx="8"/>
  
  <!-- Domain Layer (top) -->
  <rect x="40" y="30" width="280" height="60" rx="6" fill="#e8f4e8" stroke="#4a9" stroke-width="2"/>
  <text x="180" y="55" text-anchor="middle" font-size="11" fill="#2a7" font-weight="bold">DOMAIN (desiredstate)</text>
  <text x="180" y="73" text-anchor="middle" font-size="10" fill="#333">DesiredStateModuleContent</text>
  
  <rect x="400" y="30" width="280" height="60" rx="6" fill="#e8f4e8" stroke="#4a9" stroke-width="2"/>
  <text x="540" y="55" text-anchor="middle" font-size="11" fill="#2a7" font-weight="bold">DOMAIN (desiredstate)</text>
  <text x="540" y="73" text-anchor="middle" font-size="10" fill="#333">TypedExpandedModule&lt;T&gt;</text>
  
  <!-- Bridge (middle) -->
  <rect x="160" y="120" width="400" height="50" rx="6" fill="#fff3e0" stroke="#f90" stroke-width="2"/>
  <text x="360" y="142" text-anchor="middle" font-size="12" fill="#c60" font-weight="bold">ModuleBridge&lt;T&gt;</text>
  <text x="360" y="158" text-anchor="middle" font-size="10" fill="#666">toSections() / fromSections() / rewriter()</text>
  
  <!-- Engine (bottom) -->
  <rect x="160" y="210" width="400" height="60" rx="6" fill="#e3f2fd" stroke="#47f" stroke-width="2"/>
  <text x="360" y="235" text-anchor="middle" font-size="11" fill="#25d" font-weight="bold">yaml-core ModuleExpander</text>
  <text x="360" y="253" text-anchor="middle" font-size="10" fill="#333">Map&lt;String, Map&lt;String, Object&gt;&gt; sections</text>
  
  <!-- Arrows -->
  <path d="M180 90 L260 120" stroke="#4a9" stroke-width="2" fill="none" marker-end="url(#arrow-green)"/>
  <text x="195" y="108" font-size="9" fill="#4a9">toSections()</text>
  
  <path d="M460 120 L540 90" stroke="#4a9" stroke-width="2" fill="none" marker-end="url(#arrow-green)"/>
  <text x="480" y="108" font-size="9" fill="#4a9">fromSections()</text>
  
  <path d="M360 170 L360 210" stroke="#47f" stroke-width="2" fill="none" marker-end="url(#arrow-blue)"/>
  <text x="375" y="195" font-size="9" fill="#47f">raw sections</text>
  
  <!-- Arrow markers -->
  <defs>
    <marker id="arrow-green" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="#4a9"/>
    </marker>
    <marker id="arrow-blue" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="#47f"/>
    </marker>
  </defs>
</svg>

The domain defines a typed content container:

```java
record DesiredStateModuleContent(
    Map<String, YamlNode> nodes,
    Map<String, YamlRule> rules,
    Map<String, YamlInvariant> invariants) {}
```

The bridge converts between this and raw sections:

```java
interface ModuleBridge<T> {
    T fromSections(Map<String, Map<String, Object>> sections);
    Map<String, Map<String, Object>> toSections(T content);
    SectionContentRewriter rewriter();
}
```

The expansion engine works on raw maps internally — alias-prefixing, section merging, parameter resolution are all structural operations that don't need to know what a "node" is. The bridge encapsulates the typed/untyped boundary so the consumer never touches raw maps:

```java
TypedExpandedModule<DesiredStateModuleContent> expanded =
    ModuleExpander.expand(imports, modules, existingContent, bridge);

expanded.content().nodes();       // Map<String, YamlNode> — typed
expanded.content().rules();       // Map<String, YamlRule> — typed
expanded.content().invariants();  // Map<String, YamlInvariant> — typed
```

## Dynamic Section Capture

The YAML format problem is solved separately, in `yaml-jackson`. A `YamlModuleFileBuilder` with `@JsonAnySetter` captures any top-level key that isn't `module` or `imports` as a section:

```java
@JsonPOJOBuilder(withPrefix = "")
class YamlModuleFileBuilder {
    private YamlModuleHeader module;
    private List<YamlImport> imports;
    private final Map<String, Map<String, Object>> sections = new LinkedHashMap<>();

    @JsonAnySetter
    void addSection(String name, Object value) {
        if (value instanceof Map) {
            sections.put(name, (Map<String, Object>) value);
        }
    }
    // ...
}
```

A Jackson mixin registers this builder for `YamlModuleFile` deserialization. The result: YAML authors keep writing `nodes:` at the top level. yaml-core stays domain-agnostic. No format change needed.

## Parameter Constraints

yaml-core's `YamlModuleParameter` carries constraint fields that the old local type didn't have:

```yaml
module:
  name: monitoring
  parameters:
    watched_node_id:
      type: string
      required: true
      minLength: 1
      pattern: "^[a-z][a-z0-9-]*$"
      constraintDescription: "lowercase alphanumeric with hyphens"
    alert_email:
      type: string
      default: "ops@example.com"
      pattern: "^[^@]+@[^@]+\\.[^@]+$"
```

`ParameterValidator` enforces these during expansion — `minLength`, `maxLength`, `pattern`, `minimum`, `maximum`, `allowedValues`. The validation is called inside `ModuleExpander.expand()`, so any consumer using the expander gets validation for free. Build-time errors, not runtime surprises.

## Module Extension

A module can now inherit from another:

```yaml
module:
  name: monitoring-with-slack
  extends: monitoring
  parameters:
    slack_channel:
      type: string
      required: true
nodes:
  slack-notifier:
    type: notifier
    dependsOn: [monitor]    # inherited from parent
    spec:
      channel: "${var.slack_channel}"
```

`resolveExtensions()` runs before expansion. It merges parent sections with child sections (child wins on key conflict), merges parameters (child can add or override defaults), and merges outputs. Single-level only — no A-extends-B-extends-C chains. Crossplane taught us that deep composition nesting is where operators go to debug for days.

<svg viewBox="0 0 720 280" xmlns="http://www.w3.org/2000/svg" style="max-width:720px;font-family:system-ui,sans-serif">
  <rect width="720" height="280" fill="#fafafa" rx="8"/>
  
  <!-- Parent Module -->
  <rect x="40" y="30" width="200" height="100" rx="6" fill="#e3f2fd" stroke="#47f" stroke-width="2"/>
  <text x="140" y="50" text-anchor="middle" font-size="11" font-weight="bold" fill="#25d">monitoring</text>
  <text x="55" y="72" font-size="9" fill="#555">params: watched_node_id, alert_email</text>
  <text x="55" y="86" font-size="9" fill="#555">nodes: monitor, alerter</text>
  <text x="55" y="100" font-size="9" fill="#555">invariants: monitor-has-target</text>
  <text x="55" y="118" font-size="9" fill="#888" font-style="italic">2 params, 2 nodes, 1 invariant</text>
  
  <!-- Child Module -->
  <rect x="280" y="30" width="200" height="100" rx="6" fill="#fff3e0" stroke="#f90" stroke-width="2"/>
  <text x="380" y="50" text-anchor="middle" font-size="11" font-weight="bold" fill="#c60">monitoring-with-slack</text>
  <text x="295" y="72" font-size="9" fill="#555">extends: monitoring</text>
  <text x="295" y="86" font-size="9" fill="#555">params: + slack_channel</text>
  <text x="295" y="100" font-size="9" fill="#555">nodes: + slack-notifier</text>
  <text x="295" y="118" font-size="9" fill="#888" font-style="italic">adds 1 param, 1 node</text>
  
  <!-- Merged Result -->
  <rect x="400" y="170" width="280" height="90" rx="6" fill="#e8f4e8" stroke="#4a9" stroke-width="2"/>
  <text x="540" y="192" text-anchor="middle" font-size="11" font-weight="bold" fill="#2a7">resolved module</text>
  <text x="415" y="212" font-size="9" fill="#555">params: watched_node_id, alert_email, slack_channel</text>
  <text x="415" y="226" font-size="9" fill="#555">nodes: monitor, alerter, slack-notifier</text>
  <text x="415" y="240" font-size="9" fill="#555">invariants: monitor-has-target</text>
  
  <!-- Arrows -->
  <path d="M240 100 L380 170" stroke="#888" stroke-width="1.5" fill="none" marker-end="url(#arrow-grey)" stroke-dasharray="6,3"/>
  <path d="M380 130 L440 170" stroke="#f90" stroke-width="2" fill="none" marker-end="url(#arrow-orange)"/>
  
  <text x="290" y="155" font-size="9" fill="#888">inherits</text>
  <text x="410" y="155" font-size="9" fill="#c60">adds</text>
  
  <!-- resolveExtensions label -->
  <rect x="40" y="185" width="180" height="30" rx="4" fill="#f5f5f5" stroke="#ccc"/>
  <text x="130" y="205" text-anchor="middle" font-size="10" fill="#666">resolveExtensions()</text>
  <path d="M220 200 L398 210" stroke="#ccc" stroke-width="1.5" fill="none" marker-end="url(#arrow-grey)"/>
  
  <defs>
    <marker id="arrow-grey" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="#888"/>
    </marker>
    <marker id="arrow-orange" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="#f90"/>
    </marker>
  </defs>
</svg>

## What This Opens Up

The `ModuleBridge<T>` pattern isn't specific to desiredstate. Any domain that uses yaml-core modules — an IoT deployment system with `devices` and `connections`, a data pipeline with `stages` and `transforms` — can provide its own `T` and bridge. The expansion engine doesn't change. The bridge is 112 lines for desiredstate; it would be a similar size for any domain.

Module extension opens composition patterns that were previously out of reach in YAML. A base "database" module with monitoring bolted on via extension. A "service" module extended with region-specific configuration. The single-level cap keeps it debuggable — and if you need deeper composition, that's what `GoalCompiler` implementations in Java are for.

The whole migration deleted 562 lines and added 233. Seven local files gone, replaced by two new classes and a platform dependency. The YAML files didn't change at all.
