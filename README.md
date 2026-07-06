> **Repository moved.** As of 2026-07-05 this project lives at
> [tegmentum/stardog-webfunction-plugin](https://github.com/tegmentum/stardog-webfunction-plugin)
> (previously `semantalytics/stardog-webfunction-plugin`). The old URL
> auto-redirects. Java package base and Maven groupId are now `ai.tegmentum.*`.
> The `http://semantalytics.com/2021/03/ns/stardog/webfunction/` RDF vocabulary
> IRI is preserved for identifier stability.

# Stardog WebFunctions

A [Stardog](http://stardog.com) plugin for executing web assembly functions

```
prefix wf: <http://semantalytics.com/2021/03/ns/stardog/webfunction/>
prefix f: <ipfs://QmVx8jryTscgnbJoh8iuUYUiiGBeu4tr1i1A3PmCqcE5Vk/>

select ?result where { bind(wf:call(f:toUpper, \"stardog\") AS ?result) }";
```

Stardog WebFunctions supports loading functions from IPFS to reduce external dependencies and allow functions to be called offline

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
