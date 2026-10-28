# TeaStore logging instrumentation — implementation record

**Component:** trace-correlated application logging for the TeaStore services.
**Repository:** fork of DescartesResearch/TeaStore.
**Branch:** `seclogtrace`.
**Base commit:** `34b37f7e7be433ce72d5f9455e66922a13116749` (upstream master, 2025-01-08).
**Status:** implemented, wired, and verified end to end (2026-10-16). Log
coverage is currently partial — three of five services emit request-boundary
logs; see Section 10.

---

## 1. Objective

Add per-request application logs to TeaStore, each carrying the active
OpenTelemetry `trace_id`, so that logs and distributed traces can be joined by
trace identifier. This brings TeaStore (SUT2) to the same trace/log join model
already in use on the Train-Ticket system (SUT1).

## 2. Problem statement (findings)

Measured against the pinned source:

1. **Sparse request logging.** Main-source logging-call count: TeaStore 163 vs
   Train-Ticket 927. The `auth` service emits none. No service logs a successful
   request. Most statements sit in startup, image-setup, and error paths.
2. **Trace context discarded at the binding.** TeaStore binds SLF4J to
   `slf4j-simple`, whose MDC adapter is `NOPMDCAdapter` (a no-op). Trace
   identifiers written to the MDC by the OpenTelemetry agent are discarded. The
   agent provides no OTLP log appender for `slf4j-simple`.

Result: no configuration-only path exists. A source change and rebuild are
required.

Reference:
- `NOPMDCAdapter` is an empty implementation:
  https://slf4j.org/api/org/slf4j/helpers/NOPMDCAdapter.html
- OpenTelemetry MDC instrumentation supports Log4j and Logback only:
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/logger-mdc-instrumentation.md

## 3. Design decisions and rationale

| # | Decision | Rationale |
|---|---|---|
| 1 | Pin all work to commit `34b37f7` | Reproducibility. The published `:latest` images are mutable and already diverged from source. The artifact must rebuild identically. A base image rebuilt from pinned source is the reproducible base — see Section 9 on the stale local base. |
| 2 | Replace `slf4j-simple` with `logback-classic` 1.2.13 | Logback provides a real MDC, so `trace_id` reaches log output. 1.2.x retains the SLF4J 1.7.x API and Java 8+ compatibility. The binding is only effective once the shaded SLF4J 2.0 carried by the Kieker `aspectj` jar is kept off the resolving classpath — see decision 9 and Section 9. |
| 3 | Correlate via MDC pattern `%X{trace_id}` | The OpenTelemetry agent populates the Logback MDC automatically. No source coupling to the OpenTelemetry API is introduced. |
| 4 | Emit logs by AspectJ load-time weaving, not hand-written log lines | A single blanket aspect produces uniform coverage across services. Uniform, request-independent logging is auditable and avoids per-endpoint bias. |
| 5 | Use AspectJ `aspectjrt`/`aspectjweaver` 1.9.19 | The base runtime is JDK 11. Load-time weaving runs on Java 11 without the `--add-opens` workaround, which applies only to JDK 16+. |
| 6 | Place the aspect and configuration in `registryclient`; place the binding swap in `entities` | All five services depend on `registryclient`, which depends on `entities`. One copy covers every service archive. |
| 7 | Scope the weave to `..rest..` and `..servlet..`; exclude `entities` and the client-side wrappers in `registryclient.rest` | Logs only each service's own server-side endpoints; excludes client-side REST wrappers. The scope is fixed and independent of any specific request class. The aspect's own package (`registryclient.tracing`) is **not** excluded, so the weaver can augment it with `aspectOf()` — see decision 9. |
| 8 | Redact credential arguments | The authentication endpoint receives a plaintext password argument, which must never be logged. **Done** — see Section 6. |
| 9 | Carry the weaving config at the classpath root; keep the Kieker `aspectj` jar off the packaged classpath; load web-application-first | Discovered during bring-up (Section 9). Required for the aspect to weave and for the Logback binding to take effect. |

## 4. Change record

Branch `seclogtrace`. Commits relevant to logging, oldest first:

- `ab21d65c` — SSRF sink injected into
  `image/rest/ImageProviderEndpoint.java` (CWE-918, `fetchFromUrl`).
- `0aba8265` — Logback binding + AspectJ request-boundary aspect (initial).
- `0a52dbe5` — this implementation record + audit.
- `3a904850` — **Add AspectJ weaver agent to OTel Compose services.** Second
  `-javaagent` (after the OTel agent) + read-only mount of the weaver jar on the
  five OTel-instrumented services in `examples/docker/docker-compose_otel.yaml`.
- `23053512` — **Redact credential arguments in request-boundary logging.**
- `b782ee1d` — **Fix load-time weaving and SLF4J binding** (Section 9).

New / moved files (current state):
- `utilities/.../registryclient/.../tracing/SecLogTraceAspect.java`
- `utilities/.../registryclient/src/main/resources/aop.xml` (classpath root;
  moved from `META-INF/aop.xml`)
- `utilities/.../registryclient/src/main/resources/logback.xml`

Modified POMs / config:
- `interfaces/.../entities/pom.xml` — `slf4j-simple` → `logback-classic` 1.2.13
- `utilities/.../registryclient/pom.xml` — remove `slf4j-simple`, add `aspectjrt` 1.9.19
- `services/.../registry/pom.xml` — remove `slf4j-simple`
- `utilities/.../kieker.probes/pom.xml` — Kieker `aspectj` classifier set to
  `provided` scope (keeps its shaded SLF4J 2.0 out of the WARs)
- `utilities/.../dockerbase/baseContext.xml` — `<Loader delegate="false"/>`
  (web-application-first class loading)

Not committed (runtime artifacts, `*.jar` is gitignored under `examples/`):
`examples/docker/aspectjweaver.jar` (1.9.19, Maven Central, SHA1
`afbffb1210239fbba5cad73093c5b216d515838f`) and
`examples/docker/opentelemetry-javaagent.jar` (2.9.0). Download both before
running the stack.

## 5. Runtime behavior

- The aspect logs one `[method][ENTRY] args=...` line and one `[method][EXIT]`
  (or `[method][ERROR]`) line per woven server-side `rest`/`servlet` invocation,
  with a duration on exit.
- Argument values are bounded (max 256 chars); `byte[]`/`char[]` are summarized
  by length, not dumped.
- Credential parameters are redacted (Section 6).
- Each line carries `trace_id`/`span_id` from the MDC, blank when no span is
  active (startup).
- The weaver runs as a second `-javaagent`, after the OpenTelemetry agent, so a
  span is active when the aspect logs.

Example — one `GET /product?id=7` request, logs from three services sharing one
`trace_id`, each line resolvable in Jaeger:

```
auth        | 2026-10-16 08:21:20.769 [http-nio-8080-exec-2] INFO  trace_id=8d60913d5f48c2f88e69fc37081a7f26 span_id=8213c519… AuthUserActionsRest   - [isLoggedIn][ENTRY] args=[SessionBlob@34aa9b7c]
recommender | 2026-10-16 08:21:20.814 [http-nio-8080-exec-6] INFO  trace_id=8d60913d5f48c2f88e69fc37081a7f26 span_id=9a65224f… RecommendEndpoint     - [recommend][ENTRY] args=[[OrderItem@7466], null]
image       | 2026-10-16 08:21:20.932 [http-nio-8080-exec-9] INFO  trace_id=8d60913d5f48c2f88e69fc37081a7f26 span_id=662c3841… ImageProviderEndpoint - [getProductImages][ENTRY] args=[{258=125x125, 71=125x125, 328=125x125}]
image       | 2026-10-16 08:21:20.934 [http-nio-8080-exec-9] INFO  trace_id=8d60913d5f48c2f88e69fc37081a7f26 span_id=662c3841… ImageProviderEndpoint - [getProductImages][EXIT] status=OK durationMs=2
```

SSRF sink (target URL captured in the ENTRY line; outbound fetch appears as a
child span in the same Jaeger trace):

```
image | ... trace_id=b4501c56… ImageProviderEndpoint - [fetchFromUrl][ENTRY] args=[http://registry:8080/tools.descartes.teastore.registry/rest/services/]
```

Credential redaction on the auth login endpoint:

```
auth | ... AuthUserActionsRest - [login][ENTRY] args=[SessionBlob@…, user2, ***]
```

## 6. Credential redaction (decision 8 — done)

`SecLogTraceAspect` redacts argument values whose JAX-RS binding name
(`@QueryParam`/`@FormParam`/… value, read reflectively, javax or jakarta) is in
a fixed credential-name set (`password`, `passwd`, `pwd`), matched
case-insensitively. The per-method mask is computed once and cached. Redacted
positions render as `***`.

The rule keys on the declared parameter binding, so it is uniform across
endpoints and independent of attack class. The auth login password is redacted;
non-credential arguments, including the SSRF target URL, are unaffected.
Verified: a login with a known password produced
`[login][ENTRY] args=[SessionBlob@…, user2, ***]`, and the plaintext password
appeared zero times in the auth logs.

## 7. Verification (2026-10-16 — all steps pass)

1. **Single binding.** `mvn -pl services/.../image -am dependency:tree` →
   `slf4j-api` 1.7.36 + `logback-classic`/`logback-core` 1.2.13, no
   `slf4j-simple`. The packaged WAR carries the same and no
   `kieker-2.0.0-aspectj.jar`. **Pass.**
2. **Trace-correlated line.** Browse traffic → image logs show
   `[getWebUIImages][ENTRY] args=...` with a non-empty `trace_id`. **Pass.**
3. **Join resolves.** The SSRF `trace_id` resolved in Jaeger
   (`http://localhost:16686`) to a 2-span trace: the `fetchFromUrl` server span
   plus the outbound `GET` (the SSRF fetch, auto-instrumented). **Pass.**
4. **Endpoint argument captured.** The SSRF target URL appears in an `ENTRY`
   line (Section 5). **Pass.**

All five services register in Jaeger (`auth`, `image`, `persistence`,
`recommender`, `webui`).

## 8. Toolchain / runtime

- Base image `tomcat:10.0.27-jdk11-temurin`; `slf4j.version` 1.7.36; compiler
  release 11.
- OpenTelemetry Java agent **2.9.0**; AspectJ weaver/runtime **1.9.19**; Jaeger
  all-in-one **1.57**, in-memory storage, OTLP enabled.
- `docker-compose_otel.yaml` wires both agents via `JAVA_TOOL_OPTIONS`
  (`-javaagent:/otel/opentelemetry-javaagent.jar -javaagent:/otel/aspectjweaver.jar`)
  on persistence, auth, image, recommender, webui.

## 9. Bring-up findings (why more than the source change was needed)

The POM/aspect changes alone did not produce logs. Four issues were found and
fixed during bring-up:

1. **Stale local base image.** The locally-loaded `teastore-base` predated the
   pinned source and still injected `slf4j-simple-1.7.21` onto the classpath
   (historically added "to avoid NOP logger"). This silently provided the SLF4J
   binding and defeated the Logback swap. **Fix:** rebuild the base image from
   current source (which no longer adds it).
2. **Weaver config path.** The base runs the weaver with
   `-Dorg.aspectj.weaver.loadtime.configuration=aop.xml`, which loads only
   **root-level** `aop.xml` resources, never `META-INF/aop.xml`. **Fix:** move
   the config to the classpath root (`aop.xml`).
3. **`aspectOf()` not generated.** The original weave exclude covered all of
   `registryclient`, including the aspect class itself, so the weaver wove
   `aspectOf()` call sites into targets but never augmented the aspect with the
   accessor → `NoSuchMethodError`. **Fix:** narrow the exclude to
   `registryclient.rest` (decision 7).
4. **Shaded SLF4J 2.0 + parent-first delegation → NOP logger.**
   `kieker.probes` depends on `kieker:2.0.0:aspectj`, a shaded uber-jar bundling
   SLF4J 2.0's `LoggerFactory`/`SLF4JServiceProvider`. On the classpath (in the
   WAR, or via the agent jar under `delegate="true"`) it shadowed the SLF4J
   1.7.x + Logback binding, and SLF4J fell back to the no-op logger.
   **Fix:** set the Kieker `aspectj` dependency to `provided` (keeps the shaded
   jar out of the WAR; the unshaded kieker via `entities` plus the agent supply
   the runtime classes) **and** set `<Loader delegate="false"/>` so each WAR
   resolves SLF4J from its own `WEB-INF/lib`.

## 10. Coverage (all five services — 2026-10-23)

Request-boundary logs are emitted by **webui, image, persistence, auth, and
recommender**. Two changes brought webui and persistence in (they were dark
under the original `public`-only, package-scoped pointcut):

- **Pointcut broadened** (decision 7) to also match `protected` servlet handlers
  (webui's `handleGETRequest` is protected) and the shared JAX-RS CRUD base
  `registryclient.util.AbstractCRUDEndpoint`, whose inherited methods are the
  persistence endpoints' actual entry points.
- **Kieker weave config neutralized in the OTel Compose** by mounting an empty
  `aop.xml` over `/usr/local/tomcat/lib/aop.xml` (`examples/docker/empty-aop.xml`).
  The base image's Kieker `aop.xml` `<exclude>`d `AbstractCRUDEndpoint` (and
  carried a concrete-aspect that disabled the weaver on some class loaders);
  the AspectJ weaver unions excludes across all loaded `aop.xml`, so that one
  blocked the persistence weave. The base image is unchanged, so the Kieker
  Compose scenario still uses the real Kieker `aop.xml`.

The scope remains fixed and attack-independent. Known cosmetics, not blockers:
webui logs both `doGet` (base) and `handleGETRequest` (subclass) per request —
redundant; add an `AbstractUIServlet` exclude to the SecLogTrace `aop.xml` if a
single line per request is wanted. persistence also logs readiness polls
(`DatabaseGenerationEndpoint.isFinshed`), which are their own single-span traces
and drop out under the existing trace-level infra-noise filter. Servlet-layer
arguments render as request/response object identities (inherent to
method-execution logging at that layer); the semantically useful arguments are
captured at the REST/persistence layer.

## 11. Fallbacks

- If `aop.xml` is not picked up from the `registryclient` archive on a given
  service, copy `aop.xml` into that service's own `src/main/resources` (resolves
  to `WEB-INF/classes`, a classpath root).
- If MDC correlation does not populate, read the identifier in the aspect via
  `io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId()` and
  add `opentelemetry-api` at `provided` scope.

## 12. Reference

von Kistowski, Eismann, Schmitt, Bauer, Grohmann, Kounev. "TeaStore: A
Micro-Service Reference Application for Benchmarking, Modeling and Resource
Management Research." IEEE MASCOTS 2018, Milwaukee.
