> **Repository moved.** As of 2026-07-05 this project lives at
> [tegmentum/stardog-webfunction-plugin](https://github.com/tegmentum/stardog-webfunction-plugin)
> (previously `semantalytics/stardog-webfunction-plugin`). The old URL
> auto-redirects. Java package base and Maven groupId are now `ai.tegmentum.*`.
> The `http://semantalytics.com/2021/03/ns/stardog/webfunction/` RDF vocabulary
> IRI is preserved for identifier stability.

# Stardog WebFunctions

A [Stardog](http://stardog.com) plugin for executing web assembly functions.

Part of a three-binding family that all share one component ABI:

| Binding | Repo |
|---|---|
| Stardog | you are here |
| Apache Jena | [tegmentum/jena-webfunction-plugin](https://github.com/tegmentum/jena-webfunction-plugin) |
| Eclipse RDF4J | [tegmentum/rdf4j-webfunction-plugin](https://github.com/tegmentum/rdf4j-webfunction-plugin) |

The WIT world at `src/main/wit/webfunction.wit` (package `stardog:webfunction@0.2.0`)
is byte-for-byte identical across the three repos, so a single Rust component
(built with [`cargo component`](https://github.com/bytecodealliance/cargo-component))
runs unmodified under any of the three SPARQL engines. WASM runtime is
[webassembly4j](https://github.com/tegmentum/webassembly4j) (wasmtime provider).

```
prefix wf: <http://semantalytics.com/2021/03/ns/stardog/webfunction/>
prefix f: <ipfs://QmVx8jryTscgnbJoh8iuUYUiiGBeu4tr1i1A3PmCqcE5Vk/>

select ?result where { bind(wf:call(f:toUpper, \"stardog\") AS ?result) }";
```

Stardog WebFunctions supports loading functions from IPFS to reduce external dependencies and allow functions to be called offline

## SPARQL surfaces

The wf:call function is exposed through four different SPARQL surfaces; all
back onto the same component's `evaluate` / `aggregate-step` /
`aggregate-finish` exports.

| Shape | Syntax | When to reach for it |
|---|---|---|
| Filter | `BIND(wf:call(<url>, args...) AS ?x)` | one value out of one wasm call |
| Aggregate | `SELECT (wf:call(...) AS ?sum) WHERE {...} GROUP BY ...` | reduce query rows to one value |
| Property | `?x wf:call (<url> args...)` | multi-row output, single subject variable |
| SERVICE | `SERVICE <url> { ... }` | multi-row, multi-var output |

## Performance

Component-mode caches a shared `Engine` (built once from `WebFunctionConfig`
on first `wf:call`) and a `ConcurrentHashMap<URL, Component>` of compiled
components. Repeat calls skip download + compile; only the per-call
`ComponentInstance` is fresh. `webfunctions.*` system properties are read once
at first use — changing them mid-run has no effect.

Bench (Darwin aarch64, `to_upper` component, warm cache):
- Component mode `evaluate`: ~20 µs/op (49k ops/s)
- Component mode `instantiate`: ~356 µs/op — down from 16.8 ms/op before
  caching (47× faster).
- Module mode `evaluate`: ~49 µs/op — the malloc / JSON round-trip on every
  call is real overhead.

Module mode is not cached; its linking context binds a per-instance
`MappingDictionary` reference, and the caching refactor would need to route
that through the shared engine, which isn't worth the effort while
component mode is the recommended path.

## Testing

Two paths depending on your environment:

**`mvn test`** — component-mode direct-instantiation tests (no Stardog server
required). Includes `TestComponentMode` and `TestComponentAggregate`. The
embedded-Stardog `WasmTestSuite` is included but skips cleanly (via JUnit
`assume`) when the machine's installed Stardog native library
(`$STARDOG/lib/libStarrocks-*`) doesn't match the Stardog Java jars this
build compiles against.

**`mvn verify`** — additionally runs `WasmTestSuiteIT` via Testcontainers:
boots a `stardog/stardog` container, mounts the shaded plugin JAR from
`target/` into `/var/opt/stardog/.ext/`, drops the smoke-test wasm into
`/opt/wasm/`, and runs a `wf:call` SPARQL query against the running server.
Requirements: Docker; `STARDOG_LICENSE_PATH` env pointing at a valid license
file. On Apple Silicon, set `DOCKER_DEFAULT_PLATFORM=linux/amd64` (Stardog
only ships linux/amd64 images).
