# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

**Build:**
```bash
mvn clean package
```

**Test:**
```bash
mvn test
```

**Run single test:**
```bash
mvn test -Dtest=TestClassName
```

**Rust WASM functions:**
The plugin-internal `src/test/rust/` workspace is currently empty — all
its members were legacy MODULE-mode fixtures and were retired when the
plugin went component-only. Component-mode test wasms live at
`~/git/webfunctions/crates/example-{uppercase-extension,multi-var-filter,sum-aggregate}`
so the four engine bindings (stardog, jena, rdf4j, oxigraph) share one
wasm per test surface; build them with `cargo component build --release`
under that repo. `cargo make build` inside `src/test/rust/` still runs
(no-op) because `WasmTestSuite.beforeClass` shells out to it.

## Architecture

This is a Stardog plugin that enables execution of WebAssembly (WASM) functions within SPARQL queries using IPFS for function distribution. The plugin builds against Java 21 and targets Stardog 12. WASM execution goes through the webassembly4j api (default provider: wasmtime) and is component-model only — the legacy core-module dispatch path was retired.

### Core Components

**WebFunction Service (`WebFunctionService.java`):**
- Implements Stardog's service interface for SPARQL SERVICE queries
- Handles SPARQL queries with `wf:call` patterns
- Validates service parameters and creates execution queries

**WASM Instance Manager (`StardogWasmInstance.java`):**
- Manages component lifecycle via the webassembly4j api
- Component Model ABI only — dispatches via the base sparql-extension
  world's `extension.call` / `aggregate-state.step` / `aggregate-state.finish`
  exports, threading typed WIT values through `ComponentInstance.invokeWit`.
  The legacy core-module ABI (malloc/free/JSON-payloads through linear
  memory, `webfunctions.engine.mode=module`) was retired
- Handles data serialization/deserialization between Java and WASM via
  `WitValueMarshaller`
- Component bytes + parsed Component are cached across invocations via
  Guava LoadingCache and a shared ConcurrentHashMap
- Integrates with Stardog's MappingDictionary for value translation

**WIT worlds:**
- Base substrate at `src/main/wit/base/` (extension / aggregate /
  host-callbacks / types / world-graph); shared with Jena, RDF4J,
  Oxigraph plugins.
- Stardog-only overlay at `src/main/wit/overlay/` (planner + doc).
- Extensions target the base `tegmentum:webfunction/extension@0.1.0`
  and `aggregate@0.1.0` interfaces. `ArrayLiteral` still cannot flow
  through the WIT boundary — the marshaller rejects it explicitly.

**Marshalling (`WitValueMarshaller.java`):**
- Stardog `Value` → WIT `WitValue` on the input path
- WIT return unwrapped as Java shapes (`Map`/`List`/`Optional`) because
  `ComponentInstance.invoke` calls `WitValue.toJava()` on the way back

**Configuration (`WebFunctionConfig.java`):**
- System-property driven; every property is optional
- `webfunctions.engine.provider` (wasmtime | endive | chicory | wamr | graalwasm)
- `webfunctions.engine.id`
- `webfunctions.fuel.limit`, `webfunctions.memory.max.bytes`,
  `webfunctions.timeout.millis`, `webfunctions.exec.max.millis`,
  `webfunctions.max.instances`, `webfunctions.table.max.elements`
- `webfunctions.engine.mode` was removed; the plugin runs the
  component-model dispatch path unconditionally.

**Function Types:**
- **Property Functions:** Custom SPARQL property functions (`incallout`, `outcallin` packages)
- **Filter Functions:** SPARQL filter functions in `webfunctions` package
- **Aggregates:** Custom aggregate functions (`WebFunctionAbstractAggregate.java`)

### Key Features

**IPFS Integration:**
- Functions loaded from IPFS URLs (e.g., `ipfs://QmHash/`)
- Custom URL protocol handlers for `ipfs://` and `ipns://`
- Functions can be called offline once cached

**WASM Function Interface:**
- Component-model exports on the `tegmentum:webfunction/extension@0.1.0`
  and `tegmentum:webfunction/aggregate@0.1.0` interfaces
  (`register` / `call`, `register-aggregates` / `new-aggregate` / step / finish).
- Component-mode WIT world at `~/git/stardog-webfunction-wit/`
- Host imports for graph, HTTP, and wasm callbacks under
  `tegmentum:webfunction/*-callbacks@0.1.0`; the legacy
  `stardog:webfunction/host@0.5.0` component-mode surface is also
  registered for extensions still on that shape
- Mapping dictionary integration for Stardog value management

### Package Structure

- `ai.tegmentum.stardog.kibble.webfunctions.*` - Main plugin classes
- `com.complexible.stardog.plan.aggregates.*` - Aggregate function support
- `sun.net.www.protocol.ipfs.*` - IPFS URL protocol handlers
- `src/test/rust/` — empty cargo workspace shell; the plugin-internal
  MODULE-mode fixtures that used to live here were retired when the
  plugin went component-only. Component-mode test wasms shared across
  engine bindings live at `~/git/webfunctions/crates/example-*`.

RDF vocabulary IRI remains `http://semantalytics.com/2021/03/ns/stardog/webfunction/` for identifier stability across the semantalytics → tegmentum org migration; do not change it.

### Development Notes

**Rust WASM Functions:**
- Component-mode extensions live at `~/git/webfunctions/crates/`
- Build with `cargo component build --release` targeting `wasm32-wasip2`
- Extensions must export the base `tegmentum:webfunction/extension@0.1.0`
  or `aggregate@0.1.0` interfaces
- The plugin-internal `src/test/rust/` workspace has no members today

**Maven Shading:**
- Plugin uses Maven Shade plugin with package relocation
- Minimizes JAR size and avoids dependency conflicts
- Service files are merged during shading process