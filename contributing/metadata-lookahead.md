# Metadata lookahead prototype and benchmark

This is an enabled-by-default prototype, not a performance claim. Build and test validation of
the implementation is separate from the Python harness checks below.

## Prototype

Lookahead is enabled by default. Set the internal system property
`-Dorg.gradle.internal.resolve.metadata.lookahead=false` to opt out. Its tuning properties are:

| Property | Default |
| --- | --- |
| `org.gradle.internal.resolve.metadata.lookahead.depth` | `2` |
| `org.gradle.internal.resolve.metadata.lookahead.maxPending` | `32` |
| `org.gradle.internal.resolve.metadata.lookahead.maxCandidates` | `1024` |

Numeric values are clamped to a minimum of one. The graph hook seeds exact
dependencies after substitution but before version selection. A wrapper around
normal metadata resolution and speculative completion examines all external
variants, recursively scheduling metadata work up to the depth and candidate
budget. Constraints, project dependencies, changing declarations, explicit
artifact dependencies, and dynamic versions are skipped.

Normal graph traversal is unchanged. Speculation uses the normal repository
caches and single-flight resolver. Before graph resolution returns, it stops
accepting work, skips queued jobs, and drains running jobs. The existing Gradle
HTTP connection pool (20 connections per route, 80 total) is reused unchanged.

Use `--info` to see per-resolution scheduled, completed, failed, and demanded
candidate counts. "Demanded" means a scheduled identifier was also requested by
normal resolution, not necessarily that prefetch completed early enough to save
latency. Build-operation traces label speculative work `Prefetch metadata ...`.
Depth bounds speculative expansion from each seed; normal metadata resolution
starts another lookahead window. The candidate budget bounds the entire graph
resolution. Work exceeding the outstanding limit is dropped rather than blocking
normal traversal, and may be admitted if encountered again later.

### Caveats

* Transitive speculation ignores variant selection, substitutions, and exclusions.
  It can execute metadata rules for unused versions and variants.
* Speculative failures are ignored locally, but underlying repository failures,
  repository disabling, or dependency verification can still have side effects.
* Extra CPU, cache writes, network traffic, and waiting for running work can make
  a build slower, including an extra wait at the end of resolution.
* Caps apply per resolution; multiple resolutions multiply the work budget.
* Dynamic and synthetic resolution paths are not accelerated.
* Implementation tests cover overlap and a missing losing version; these are
  separate from the successful, all-metadata-present benchmark fixture.

## Run

Install the custom distribution from the repository root, then run the harness:

```shell
./gradlew install -Pgradle_installPath="$PWD/build/lookahead-gradle"
python3 contributing/metadata-lookahead-benchmark.py \
  --gradle "$PWD/build/lookahead-gradle/bin/gradle" \
  --runs 3 --latency-ms 50 --width 8 --depth 4
```

Python 3 and its standard library are sufficient for the harness; the installed
Gradle needs a compatible JDK. All harness Gradle subprocesses use
`--no-daemon --no-configuration-cache`. Each has a ten-minute
timeout. No wrapper distribution downloads are needed by the fixture.

The fixture is POM-only: `width` independent chains, each containing `depth`
modules, end in a shared common module with conflicting versions 1 and 2. A
direct dependency on common version 2 ensures a conflict even with width 1.
The task traverses only `ResolutionResult`, never an artifact collection.
The HTTP/1.1 threaded loopback server serves generated POMs, supports persistent
connections and HEAD, sends correct Content-Length, and injects the configured
fixed latency into every request (including missing paths). No JARs are served;
any non-POM request fails benchmark validation.

For each run, off and on have **separate initially empty Gradle user homes and
project directories** inside a fresh directory under
`build/metadata-lookahead-benchmark`. The cold pass uses these empty caches; the
warm pass reuses each mode's own caches. Off/on execution order alternates each
run, for both passes. `HOME` is never changed. The harness explicitly sets all
four prototype properties, using the defaults above for tuning.

The stopwatch covers graph resolution only, excluding script compilation,
startup, graph serialization, and artifact resolution. **Warm means warm disk
caches, not a warm JVM**: `--no-daemon` may still start a single-use daemon, and
each invocation has a cold JVM/JIT. OS caches and machine load are uncontrolled;
use repeated measurements and do not interpret one result as a general speedup.

Each sample reports elapsed milliseconds, sorted component IDs, a SHA-256 graph
fingerprint, unresolved count, HTTP requests, peak simultaneous requests, and
accepted TCP connections. Successful results must select all expected modules
and common version 2, have zero unresolved dependencies, and have identical
component IDs **and requested/selected dependency edges** across all off/on and
cold/warm samples. Failure aborts the benchmark rather than reporting a speedup.
The final summary shows median timings by mode and cache temperature.

Request counts and connections cover the entire invocation; with this isolated
fixture, repository traffic belongs to graph resolution. Fewer connections than
requests indicate reuse, and peak concurrency shows request overlap. They do not
prove the pool's configured limits or explain all JVM/network behavior. With
warm caches there may be no HTTP traffic at all.

The printed run directory retains generated POMs, build scripts, both modes'
caches, exact commands, combined stdout/stderr logs, per-invocation HTTP request
details, parameters, and incremental `results.json`. The loopback server is
stopped and joined on exit, including failures; there is no detached service.
Retained scripts contain the ephemeral server URL and are not standalone reruns:
start another harness run to regenerate a live fixture.

## Python-only validation

```shell
python3 -c 'import ast, pathlib; ast.parse(pathlib.Path("contributing/metadata-lookahead-benchmark.py").read_text())'
python3 contributing/metadata-lookahead-benchmark.py --self-test
```

These checks exercise fixture XML, HTTP framing, persistent connections, overlap,
graph-result rejection, and command options without invoking
Gradle. They do not validate the generated Groovy build or prototype behavior;
only actual runs against the custom distribution can establish those results.

## Initial local measurement

On macOS with Amazon Corretto 25.0.4, three runs of the command above against the
prototype produced these median graph-resolution times:

| Cache | Lookahead off | Lookahead on |
| --- | ---: | ---: |
| Cold | 1754.603 ms | 554.408 ms |
| Warm disk cache | 122.536 ms | 125.491 ms |

Every selected component and dependency edge matched across all twelve samples,
with no unresolved dependencies. Cold runs requested 33 POMs without lookahead
and 34 with it (including the losing common version). Both modes used nine TCP
connections; warm runs made no HTTP requests. Raw results from this session are
retained locally in `build/metadata-lookahead-benchmark/run-s17p1g51/results.json`.
These synthetic cold-JVM measurements support further experiments, not a claim
about actual IDE sync speed or production readiness.

Focused implementation tests:

```shell
./gradlew :dependency-management:test \
  --tests '*OptimisticMetadataResolverTest' --tests '*DependencyGraphBuilderTest'
./gradlew :dependency-management:forkingIntegTest \
  --tests '*OptimisticMetadataResolutionIntegrationTest'
```