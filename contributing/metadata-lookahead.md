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
normal metadata resolution and speculative completion examines compatible external
variants, recursively scheduling metadata work up to the depth and candidate
budget. Constraints, project dependencies, changing declarations, explicit
artifact dependencies, and dynamic versions are skipped.

Variant compatibility uses the immutable root consumer attributes and the merged
consumer/producer attribute schemas. Missing attributes remain eligible; no
disambiguation is performed. These are prediction hints, not authoritative
selection: dependency-specific attributes, substitutions and exclusions still
apply during normal traversal. In particular, a JVM import no longer speculates
into explicitly incompatible native variants of multiplatform modules.

Normal graph traversal is unchanged. Speculation uses the normal repository
caches and single-flight resolver. Before graph resolution returns, it stops
accepting work, skips queued jobs, and drains running jobs. The existing Gradle
HTTP connection pool (20 connections per route, 80 total) is reused unchanged.

Use `--info` to see per-resolution candidate, scheduled, completed, failed,
demanded, and cheap counts. "Demanded" means an admitted candidate was also
requested by normal resolution, not necessarily that prefetch completed early
enough to save latency. "Scheduled" counts submitted operations, including those
skipped before execution; "cheap" counts candidates left to normal traversal.
Build-operation traces label speculative work `Prefetch metadata ...`.
Depth bounds speculative expansion from each seed; normal metadata resolution
starts another lookahead window. The candidate budget bounds the entire graph
resolution, including cached candidates and the backlog.

### Scheduling improvements

* **Refill instead of dropping work:** candidates beyond `maxPending` wait in a
  FIFO backlog, bounded by `maxCandidates`. Each completion (including failure)
  admits the next candidate without waiting for graph traversal to rediscover it.
  This keeps lookahead productive on wide graphs without adding more workers.
* **Give demanded work precedence:** do not prefetch identifiers already demanded;
  skip queued/backlogged candidates if normal resolution takes them over. Running
  requests still complete through the existing single-flight repository resolver.
* **Avoid cached-metadata tasks:** the existing metadata-cost estimate bypasses
  speculative operations for cheap metadata. Positive estimates are reused by
  graph traversal within the resolution; expensive estimates are not retained.
  Actual resolution still uses the repository chain with normal overrides and
  cache validation. Demanded cached parents still seed their uncached children.

The backlog is discarded on shutdown; already submitted operations check shutdown
before accessing repositories. Normal demand resolution never waits for backlog
admission and the HTTP pool limits remain unchanged.

### Caveats

* Transitive speculation only approximates variant selection and ignores substitutions and exclusions.
  It can execute metadata rules for unused versions and variants.
* Speculative failures are ignored locally, but underlying repository failures,
  repository disabling, or dependency verification can still have side effects.
* Extra CPU, cache writes, network traffic, and waiting for running work can make
  a build slower, including an extra wait at the end of resolution.
* Caps apply per resolution; multiple resolutions multiply the work budget.
* Dynamic and synthetic resolution paths are not accelerated.
* Implementation tests cover overlap, backlog refill, warm caches, and a missing losing version; these are
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

The default `--metadata pom` fixture contains `width` independent chains, each
containing `depth` modules, ending in a shared common module with conflicting
versions 1 and 2. A direct dependency on common version 2 ensures a conflict even
with width 1. `--metadata module` publishes marked POMs and Gradle module metadata;
`--metadata mixed` mixes those with POM-only publishers. `--metadata bom` publishes
a root importing `width` independent BOMs, with `depth` levels of nested imports.
The BOM fixture checks first-import precedence using conflicting managed versions.
`--metadata pom-only` keeps redirection enabled but publishes no markers, measuring
the worst-case extra misses of the POM/module experiment. The original `pom`
fixture explicitly ignores redirection and is unchanged.
The task traverses only `ResolutionResult`, never an artifact collection.
The HTTP/1.1 threaded loopback server serves generated metadata, supports persistent
connections and HEAD, sends correct Content-Length, and injects the configured
fixed latency into every request (including missing paths). No JARs are served;
unexpected requests fail benchmark validation. Missing speculative `.module`
requests are allowed only at known POM-only coordinates and reported explicitly.
Duplicate requests are also reported. The server's accept backlog is 128 to avoid
artificial connection stalls under concurrent load.

For each run, off and on have **separate initially empty Gradle user homes and
project directories** inside a fresh directory under
`build/metadata-lookahead-benchmark`. The cold pass uses these empty caches; the
warm pass reuses each mode's own caches. Off/on execution order alternates each
run, for both passes. `HOME` is never changed. The harness explicitly sets all
four prototype properties. Override tuning with `--lookahead-depth`,
`--max-pending`, and `--max-candidates` (all positive integers).
Pass additional switches independently with repeatable
`--baseline-property NAME=VALUE` and `--experimental-property NAME=VALUE` options.

To compare two implementations with lookahead **enabled in both**, preserve an
installed baseline distribution before installing the new implementation, then use:

```shell
python3 contributing/metadata-lookahead-benchmark.py \
  --baseline-gradle "$PWD/build/lookahead-baseline-gradle/bin/gradle" \
  --gradle "$PWD/build/lookahead-gradle/bin/gradle" \
  --runs 3 --latency-ms 50 --width 64 --depth 4
```

This labels modes `baseline`/`experimental` rather than `off`/`on`. Both use the
same tuning, with separate initially empty caches and alternating execution order.
Without `--baseline-gradle`, the original off/on comparison is unchanged.

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

The printed run directory retains generated metadata, build scripts, both modes'
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

## Scheduling comparison

On the same macOS / Amazon Corretto 25.0.4 setup, three alternating runs compared
the preceding prototype against the scheduling improvements above, with lookahead
enabled in both distributions. All tuning remained at its defaults; each graph
used depth 4 and 50 ms injected request latency. Medians (milliseconds):

| Width | Cache | Previous prototype | Updated prototype |
| --- | --- | ---: | ---: |
| 64 | Cold | 2389.046 | 1037.541 |
| 64 | Warm disk cache | 185.450 | 197.316 |
| 8 | Cold | 555.760 | 589.500 |
| 8 | Warm disk cache | 128.575 | 129.618 |

The wide cold case improved by about **2.3x**, with no additional requests or
connections: both versions fetched 258 POMs over 20 connections. The small cold
case was about 6% slower, and the wide warm case about 12 ms slower. These results
favor the targeted wide, cold workload, not every resolution. Cold cases are the
priority; concurrency, depth, and candidate caps were deliberately not increased.

All 24 final samples matched selected components and dependency edges, with zero
unresolved dependencies. Warm samples made no HTTP requests. Final raw results
are retained locally under `build/metadata-lookahead-benchmark/` in
`run-uo2ewqze/results.json` (width 64) and `run-9nf9y4jv/results.json` (width 8).
These remain synthetic, cold-JVM measurements, not actual IDE sync results.
Validation passed 55 focused unit cases, six HTTP integration cases, checkstyle,
and Python harness self-checks.

### Next experiments (not implemented)

1. Measure a real cold IDE import with repository latency and build-operation
   traces before further tuning. Include multiple configurations and artifacts,
   which this metadata-only harness does not represent.
2. Prioritize likely selected variants in the backlog to spend the same candidate
   budget on more useful metadata; retain fallbacks for legacy variants and test
   substitutions, exclusions, and metadata rules carefully.
3. Evaluate latency-aware depth/concurrency only after measuring useful prefetches
   versus unused work. Do not simply raise HTTP connection limits: this change's
   wide-case gain already uses the same 20 pooled connections.

## Resource-download experiments

Both resource-download optimizations are enabled by default on this branch, independently
of graph lookahead. No opt-in flags are required. They use existing
cache-backed resource access and pooled HTTP connections, not new HTTP clients.

### POM/module overlap

Opt out with `-Dorg.gradle.internal.resolve.metadata.parallelRedirect=false`
(default **true**). For remote HTTP(S) Maven repositories using POM redirection,
first observe an authoritative POM redirect for a publisher group in that metadata
source. Subsequent requests in the group download the POM and `.module` resource
concurrently, then parse the POM and follow
its redirection marker normally. Unused module metadata is never parsed; an unused
download failure does not become an authoritative resolution failure. A missing
redirected module still permits normal POM fallback. Each resource result is reused
without a second download, and attempted locations are propagated only when used.

Eight module pairs at most can overlap per metadata-source instance. If all slots
are occupied, use normal sequential resolution without waiting for admission.
Snapshots, file repositories, explicit module-first sources,
and `ignoreGradleMetadataRedirection()` retain their existing path.

Unknown and POM-only groups use sequential authoritative resolution without a
speculative module probe. A nonredirecting POM or missing speculative module
revokes the hint; a subsequent authoritative redirect can teach it again. Hints
are isolated per metadata-source instance and group, not shared across repositories.
This remains experimental: mixed groups can still incur an extra missing `.module`
request, a slow unused response delays parsing, and speculative transport/cache
side effects are still possible. Limits are not build-global. The original
measurements below predate this adaptive eligibility policy.

### Imported BOM prefetch

Opt out with `-Dorg.gradle.internal.resolve.metadata.parallelBom=false`
(default **true**). Prefetch independent fixed-version imported POM resources
through the current repository's existing artifact resolver. Workers do **not**
perform recursive component resolution, parse POMs, execute metadata rules, or
mutate the descriptor context's sources. The original repository-chain lookup,
parsing, and first-import-wins merge run sequentially afterward.

Each batch contains at most eight dependency-management entries; only eligible
`pom`/`import` coordinates are submitted, and batches with fewer than two candidates
are skipped. One batch may run per parser instance; competing parses fall back to
ordinary sequential resolution. Snapshots, dynamic versions, and unresolved
properties are not prefetched. Offline builds disable this download hook.

This remains experimental: the current repository is only a guess for an imported BOM.
It may download unnecessary bytes from a repository that loses authoritative
selection, or contact it despite authoritative content filtering/ownership rules.
The normal repository chain still selects the result; the integration test uses
different BOM contents in two repositories to verify this. Speculative failures
are ignored locally, but transport, cache, authentication, and verification side
effects are not eliminated. Nested single-import chains remain sequential.

Both experiments use managed build-operation queues, acquiring a worker lease only
to create/wait for a batch. Waiting releases the lease, so they also work inside
unconstrained graph-lookahead operations with `--max-workers=1`. The combined
one-worker integration test covers both optimizations without opt-in flags.

### Reproduce comparisons

Preserve the preceding installed prototype before installing the changes. These
commands keep graph lookahead enabled in both distributions and isolate the new
switch under test, explicitly disabling the other optimization for reproducibility:

```shell
python3 contributing/metadata-lookahead-benchmark.py \
  --baseline-gradle "$PWD/build/metadata-download-baseline-gradle/bin/gradle" \
  --gradle "$PWD/build/lookahead-gradle/bin/gradle" \
  --metadata module --width 8 --depth 4 --runs 3 --latency-ms 50 \
  --baseline-property org.gradle.internal.resolve.metadata.parallelBom=false \
  --experimental-property org.gradle.internal.resolve.metadata.parallelBom=false \
  --baseline-property org.gradle.internal.resolve.metadata.parallelRedirect=false \
  --experimental-property org.gradle.internal.resolve.metadata.parallelRedirect=true
```

Repeat with `--metadata mixed` to measure the cost of speculative misses. These
remain metadata-only, cold-JVM synthetic experiments, not IDE-import measurements.

For BOMs:

```shell
python3 contributing/metadata-lookahead-benchmark.py \
  --baseline-gradle "$PWD/build/metadata-download-baseline-gradle/bin/gradle" \
  --gradle "$PWD/build/lookahead-gradle/bin/gradle" \
  --metadata bom --width 16 --depth 1 --runs 3 --latency-ms 50 \
  --baseline-property org.gradle.internal.resolve.metadata.parallelRedirect=false \
  --experimental-property org.gradle.internal.resolve.metadata.parallelRedirect=false \
  --baseline-property org.gradle.internal.resolve.metadata.parallelBom=false \
  --experimental-property org.gradle.internal.resolve.metadata.parallelBom=true
```

### Measured results (2026-09-18)

Three alternating baseline/experimental pairs per workload, fresh isolated caches
for every pair, graph lookahead enabled in both distributions. The baseline is the
preceding backlog-refill prototype, **not stock Gradle**. The experimental binary
is also retained in `build/metadata-download-gradle`. Each row enables only the
switch named in the workload. Values below are medians in milliseconds.
The measured environment was macOS 26.6.2 aarch64 and Amazon Corretto 25.0.4+7-LTS;
the experimental distribution identifies as `9.9.0-20260918091240+0000`.

| Workload / switch | Injected latency | Cold baseline → experiment | Cold change | Warm baseline → experiment | Cold requests baseline → experiment |
| --- | --- | --- | --- | --- | --- |
| Module 8×4 / redirection | 50 ms | 866 → 589 | **32% faster** | 142 → 139 | 68 → 68 |
| Mixed 8×4 / redirection | 50 ms | 685 → 585 | **15% faster** | 143 → 140 | 51 → 67–68 |
| POM-only 8×4 / redirection | 50 ms | 586 → 586 | No improvement | 139 → 136 | 34 → 67 |
| Module 64×4 / redirection | 50 ms | 1722 → 1708 | ~1%; inconclusive | 211 → 227 | 516 → 516 |
| 16 independent BOMs / BOM | 50 ms | 1342 → 601 | **55% faster** | 131 → 130 | 34 → 34 |
| 8 BOM chains, depth 3 / BOM | 50 ms | 1847 → 1438 | **22% faster** | 124 → 120 | 34 → 34 |
| Module 8×4 / redirection | 0 ms | 338 → 336 | No clear improvement | 140 → 138 | 68 → 68 |
| 16 independent BOMs / BOM | 0 ms | 354 → 341 | Small, ~13 ms | 133 → 126 | 34 → 34 |

All **96 invocations** resolved the expected components and requested/selected
edges, with zero unresolved dependencies. No duplicate or unexpected HTTP requests
occurred; all warm passes made zero HTTP requests. Mixed speculation added 16–17
404s; POM-only speculation added 33. Slight request-count variation comes from
whether speculation on a losing version runs before demand takes over.

On the 8×4 module workload, HTTP peak concurrency and pooled connection count rose
from 9 to 17. Both wide-module modes already saturated the existing 20 connections
per route, explaining why extra overlap did not help that workload. Independent
BOMs used 17 peak requests/connections in both modes (the final leaf batch dominates
that peak), but overlapping the earlier BOM stage removed sequential waits.

The clearest cold sample ranges were 857–881 → 586–599 ms for modules and
1328–1351 → 554–617 ms for independent BOMs. Three samples establish a useful
prototype signal, not statistical confidence or a general speedup. Warm wide-module
resolution regressed by 16 ms at the median; no claim of warm-cache improvement is
made. Zero injected latency is still loopback HTTP, not zero transport cost.

**Conclusion:** parallel BOM resource fetching is the strongest next investment
for cold, BOM-heavy builds. POM/module overlap is worthwhile for metadata-rich,
latency-bound graphs below connection-pool saturation, but these measurements do not
justify a universal production default. This experimental branch enables both by
default for further evaluation; real imports and repository-side traffic still need
measurement. Improve repository eligibility and avoid slow unused-download tails
before wider rollout; increasing the connection limit is not justified by these
measurements alone.

Retained raw `results.json`, commands, HTTP request logs, and fixture directories
under `build/metadata-lookahead-benchmark`:

| Workload | Directory |
| --- | --- |
| Module 8×4 | `run-ej4um33i` |
| Mixed | `run-3q26vyou` |
| POM-only | `run-eeuvtcx1` |
| Module 64×4 | `run-z10a5f34` |
| Independent BOMs | `run-xtyi7r2v` |
| Nested BOMs | `run-o893vovb` |
| Zero-latency module | `run-y00f79k2` |
| Zero-latency BOM | `run-bxydshvn` |

Validation: 144 focused unit tests and 30 HTTP integration cases passed, plus
dependency-management main/test/integration checkstyle and benchmark self-checks.
This is not a full Gradle test-suite run. At that stage, IDE sync, public/corporate repositories,
connection-queue time, detailed demand-blocked time, and unused-download drain
time had not been measured. The following section covers subsequent real-import measurements.

## Coroutines cold IDEA import (2026-09-18)

Investigated the supplied `kotlinx.coroutines/benchmark-idea-sync-cold3.sh`, using
its existing project, repositories, IDEA installation and scenario. No repository
filters, dependency changes, cache warming, or benchmark-script changes were used.
Both binaries identify as `9.9.0`; the baseline is branch commit `2c0cfe69b21`
with all three preceding optimizations enabled, **not stock Gradle**.

### Causes and changes

* Unconditional POM/module overlap probes missing modules even in repositories
  that do not own the component. The original trace made 159 missing module
  downloads to Google Maven and 34 to JetBrains Space. The new learned-group
  eligibility removes all of those module probes in this trace without changing
  authoritative repository order or POM redirection.
* All-variant lookahead fetched metadata for incompatible KMP platforms, including
  `kotlin-logging-mingwx64` in a JVM graph. Consumer/producer attribute compatibility
  now filters speculative expansion; normal variant selection remains authoritative.
* `DefaultArtifactResolutionQuery` retrieved source/documentation artifacts one
  component at a time. It now retrieves requested artifacts in bounded batches of
  eight components, using existing resolvers and caches. Metadata preparation,
  type mapping, explicit verification/result assembly and output ordering remain
  on the caller; only artifact discovery/downloads run concurrently. There are no
  additional requested artifacts. Queries with zero/one component or no artifact
  types avoid scheduling. Opt out with
  `-Dorg.gradle.internal.resolve.artifacts.parallelQuery=false`.

All optimizations remain enabled by default. Source-query batching is per query,
not a build-global admission limit. Existing transport/cache/repository failure
side effects are still possible for concurrently running operations. The earlier
prototype caveats above still apply.

### Measurements

For the main comparison, ran three cold pairs in alternating order: old/new,
new/old, old/new. Each run used the same cold scenario with `--single-shot`, no
profiling, and no concurrent builds. Between distribution swaps, replaced the ZIP
and removed the disposable `gradle-user-home` so the wrapper could not reuse the
previous binary. The scenario clears the user home, project caches and IDEA caches
before each import, and starts a cold daemon.

| Pair | Old total / Gradle (s) | New total / Gradle (s) |
| --- | ---: | ---: |
| 1 | 151.686 / 124.954 | 114.785 / 97.230 |
| 2 | 128.626 / 108.516 | 118.643 / 97.923 |
| 3 | 131.778 / 109.397 | 127.045 / 103.290 |
| **Median** | **131.778 / 109.397** | **118.643 / 97.923** |

This is **10.0% lower total sync time** and **10.5% lower Gradle time** at the
median, not a multi-fold speedup. All three pairs improved, but the last pair
improved less. Public-network latency and IDEA time vary substantially; three
pairs are not a statistical guarantee or evidence for all projects.

The exact supplied script (one warm-up, three measured imports, all cold) then
passed unchanged: measured total times **121.995, 118.742, 119.305 s**, Gradle
times **100.142, 98.156, 98.735 s**. Median: **119.305 / 98.735 s**.
Earlier old-binary script runs had medians of 140.0 and 126.3 s, illustrating why
the initial before/after result alone was insufficient. Do not attribute that
entire initial difference to these changes.

Separate single-import operation traces recorded:

| Operation metric | Old defaults | New defaults |
| --- | ---: | ---: |
| HTTP download operations (including misses; excluding HEAD) | 1784 | 912 |
| HTTP metadata-check operations | 503 | 497 |
| Google Maven missing module downloads | 159 | 0 |
| JetBrains Space missing module downloads | 34 | 0 |
| Configuration-resolution cumulative time (s) | 24.775 | 20.748 |
| HTTP metadata-check wall-time union (s) | 24.188 | 18.089 |

Download operations fell **48.9%**. This is not a claim that transferred bytes or
total network time halved: the large required JAR downloads are unchanged. Trace
timings are diagnostic single samples, excluded from the paired timing results.
All **247** configuration-resolution summaries matched across old/new traces,
including component records, requested attributes and resolved-dependency counts,
after normalizing temporary precompiled-accessor build names. This is not a full
IDE model/edge-by-edge equivalence proof.

### Reproduction and artifacts

From the Gradle checkout (preserve the old ZIP before overwriting it):

```shell
./gradlew :distributions-full:binDistributionZip -PfinalRelease=true
COROUTINES=/Users/Sebastian.Sellmair/JetBrainsProjects/kotlinx.coroutines
cp packaging/distributions-full/build/distributions/gradle-9.9.0-bin.zip "$COROUTINES/env/gradle/"
# This is the benchmark's disposable user home, never the normal ~/.gradle directory.
rm -rf "$COROUTINES/gradle-user-home"
"$COROUTINES/benchmark-idea-sync-cold3.sh"
```

For individual alternating samples, invoke `gradle-profiler --benchmark
--single-shot` with the script's project/scenario/IDE/sandbox/user-home arguments
and a unique output directory. Add `--build-ops-trace` only for diagnostic runs;
the `*-log.txt` is a JSON-lines operation trace.

Local evidence is retained under `build/coroutines-sync-investigation`:
`alternating-{1,2,3}-{original,final}`, `final-defaults`, `defaults-trace`,
`final-trace`, `final-summary.txt`, the two distribution ZIPs and analysis scripts.
The original benchmark output, previous ZIP and previous disposable user home
were preserved there rather than deleted. The final benchmark output remains in
coroutines' `benchmark-out`; the final ZIP is installed in `env/gradle`.

Validation: **60 unit tests and 51 integration cases** passed, including existing
Maven/Ivy artifact-query coverage, single-worker overlap, warm/offline reuse,
missing/error results, learned hints and compatible/incompatible variants.
Main/test/integration checkstyle and the full binary ZIP build passed. This is
not a full Gradle test-suite run. Warm-import performance and isolated attribution
of each change in the final combined candidate were not measured.