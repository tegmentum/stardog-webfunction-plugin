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
