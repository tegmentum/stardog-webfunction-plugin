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
```bash
# Build all WASM functions
cd src/test/rust
cargo make build

# Build and optimize WASM
cargo make optimize_wasm

# Run Rust tests
cargo make test
```

## Architecture

This is a Stardog plugin that enables execution of WebAssembly (WASM) functions within SPARQL queries using IPFS for function distribution. The plugin builds against Java 21 and targets Stardog 12. WASM execution goes through the webassembly4j api (default provider: wasmtime).

### Core Components

**WebFunction Service (`WebFunctionService.java`):**
- Implements Stardog's service interface for SPARQL SERVICE queries
- Handles SPARQL queries with `wf:call` patterns
- Validates service parameters and creates execution queries

**WASM Instance Manager (`StardogWasmInstance.java`):**
- Manages WebAssembly module lifecycle via the webassembly4j api
- Two execution modes selected by `webfunctions.engine.mode`:
  - `module` (default): core module ABI, malloc/JSON payloads through linear memory
  - `component`: Component Model ABI, typed WIT values via `ComponentInstance.invoke`
- Provides memory management for WASM function execution
- Handles data serialization/deserialization between Java and WASM
- Supports function caching via Guava LoadingCache
- Integrates with Stardog's MappingDictionary for value translation

**WIT world (`src/main/wit/webfunction.wit`):**
- Package `stardog:webfunction@0.2.0`
- Exports `evaluate`, `aggregate-step`, `aggregate-finish`, `cardinality-estimate`, `doc`
- Variant `value` = iri | literal | bnode (ArrayLiteral not currently expressible in WIT)

**Marshalling (`WitValueMarshaller.java`):**
- Stardog `Value` → WIT `WitValue` on the input path
- WIT return unwrapped as Java shapes (`Map`/`List`/`Optional`) because
  `ComponentInstance.invoke` calls `WitValue.toJava()` on the way back

**Configuration (`WebFunctionConfig.java`):**
- System-property driven; every property is optional
- `webfunctions.engine.provider` (wasmtime | endive | chicory | wamr | graalwasm)
- `webfunctions.engine.id`, `webfunctions.engine.mode`
- `webfunctions.fuel.limit`, `webfunctions.memory.max.bytes`,
  `webfunctions.timeout.millis`, `webfunctions.exec.max.millis`,
  `webfunctions.max.instances`, `webfunctions.table.max.elements`

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
- Standard WASM exports: `evaluate`, `aggregate`, `cardinality_estimate`, `doc`
- Memory management functions: `malloc`, `free`
- Mapping dictionary integration for Stardog value management

### Package Structure

- `ai.tegmentum.stardog.kibble.webfunctions.*` - Main plugin classes
- `com.complexible.stardog.plan.aggregates.*` - Aggregate function support
- `sun.net.www.protocol.ipfs.*` - IPFS URL protocol handlers
- Rust test functions in `src/test/rust/function/` — legacy module-mode crates
- `src/test/rust/function/to_upper_component/` — component-mode proof, built via `cargo component build --release`

RDF vocabulary IRI remains `http://semantalytics.com/2021/03/ns/stardog/webfunction/` for identifier stability across the semantalytics → tegmentum org migration; do not change it.

### Development Notes

**Rust WASM Functions:**
- Use `cargo make` for building WASM modules
- Functions must export standard interface (evaluate, aggregate, etc.)
- Test functions available in `src/test/rust/function/` and `src/test/rust/aggregate/`

**Maven Shading:**
- Plugin uses Maven Shade plugin with package relocation
- Minimizes JAR size and avoids dependency conflicts
- Service files are merged during shading process