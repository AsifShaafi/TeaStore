# TeaStore logging instrumentation — implementation record

**Component:** trace-correlated application logging for the TeaStore services.
**Repository:** fork of DescartesResearch/TeaStore.
**Branch:** `seclogtrace`.
**Base commit:** `34b37f7e7be433ce72d5f9455e66922a13116749` (upstream master, 2025-01-08).
**Status:** source changes committed; runtime wiring and verification pending.

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
| 1 | Pin all work to commit `34b37f7` | Reproducibility. The published `:latest` images are mutable and already diverged from source (observed SLF4J 1.7.25 / Tomcat 8.5 against source 1.7.36 / Tomcat 10). The artifact must rebuild identically. |
| 2 | Replace `slf4j-simple` with `logback-classic` 1.2.13 | Logback provides a real MDC, so `trace_id` reaches log output. 1.2.x retains the SLF4J 1.7.x API and Java 8+ compatibility; 1.3+ would force SLF4J 2.0 and cascade further changes. |
| 3 | Correlate via MDC pattern `%X{trace_id}` | The OpenTelemetry agent populates the Logback MDC automatically. No source coupling to the OpenTelemetry API is introduced. |
| 4 | Emit logs by AspectJ load-time weaving, not hand-written log lines | A single blanket aspect produces uniform coverage across services. Uniform, request-independent logging is auditable and avoids per-endpoint bias. |
| 5 | Use AspectJ `aspectjrt`/`aspectjweaver` 1.9.19 | The base runtime is JDK 11. Load-time weaving runs on Java 11 without the `--add-opens` workaround, which applies only to JDK 16+. |
| 6 | Place the aspect and configuration in `registryclient`; place the binding swap in `entities` | All five services depend on `registryclient`, which depends on `entities`. One copy covers every service archive. The weaver aggregates `META-INF/aop.xml` from all archives on the webapp classpath. |
| 7 | Scope the weave to `..rest..` and `..servlet..`; exclude `entities` and `registryclient` | Logs only each service's own server-side endpoints; excludes client-side REST wrappers. The scope is fixed and independent of any specific request class. |
| 8 | Redact credential arguments | The authentication endpoint receives a plaintext password argument, which must never be logged. (Open task — see Section 6.) |

## 4. Change record

Branch `seclogtrace`, two commits.

Commit 1 — image-service endpoint modification
(`services/.../image/src/main/java/tools/descartes/teastore/image/rest/ImageProviderEndpoint.java`).

Commit 2 — logging instrumentation (6 files, +178 / −12):

New files:
- `utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/tracing/SecLogTraceAspect.java`
- `utilities/tools.descartes.teastore.registryclient/src/main/resources/META-INF/aop.xml`
- `utilities/tools.descartes.teastore.registryclient/src/main/resources/logback.xml`

Modified POMs:
- `interfaces/tools.descartes.teastore.entities/pom.xml` — `slf4j-simple` → `logback-classic` 1.2.13
- `utilities/tools.descartes.teastore.registryclient/pom.xml` — remove `slf4j-simple`, add `aspectjrt` 1.9.19
- `services/tools.descartes.teastore.registry/pom.xml` — remove `slf4j-simple`

Post-change check: no `slf4j-simple` remains on any service-reachable module.

## 5. Runtime behavior

- The aspect logs one `[method][ENTRY] args=...` line and one `[method][EXIT]`
  (or `[method][ERROR]`) line per server-side `rest`/`servlet` invocation.
- Argument values are bounded (max 256 chars); `byte[]`/`char[]` are summarized
  by length, not dumped.
- Each line carries `trace_id` and `span_id` from the MDC, blank when no span is
  active (startup).
- The weaver runs as a second `-javaagent`, after the OpenTelemetry agent, so a
  span is active when the aspect logs.

## 6. Remaining steps (operator)

1. Place `aspectjweaver.jar` 1.9.19 in `examples/docker/`.
2. In `docker-compose_otel.yaml`, add the weaver as a second `-javaagent` for
   each service (after the OpenTelemetry agent) and mount the jar. Match the
   existing agent-wiring style.
3. Implement credential redaction in `SecLogTraceAspect.describe(...)`
   (Section 3, decision 8).
4. Rebuild: `mvn clean install -DskipTests`, then rebuild the service images.
5. Run the verification procedure (Section 7).

## 7. Verification procedure (halt on first failure)

1. **Single binding.**
   `mvn -q -pl services/tools.descartes.teastore.image -am dependency:tree | Select-String slf4j`
   Expect `slf4j-api` and `logback`; `slf4j-simple` absent.
2. **Trace-correlated line.** Drive one request, then
   `docker compose -f docker-compose_otel.yaml logs image | Select-String "trace_id="`
   Expect `[<endpoint>][ENTRY] args=...` with a non-empty `trace_id`.
3. **Join resolves.** Take one `trace_id`, open Jaeger
   (`http://localhost:16686`), resolve it to a trace.
4. **Endpoint argument captured.** Exercise the image-provider endpoint; confirm
   the target argument appears in an `ENTRY` line.

## 8. Fallbacks

- If `aop.xml` is not picked up from the `registryclient` archive, copy `aop.xml`
  and `logback.xml` into each service's own `src/main/resources`
  (resolves to `WEB-INF/classes`).
- If MDC correlation does not populate, read the identifier in the aspect via
  `io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId()` and add
  `opentelemetry-api` at `provided` scope.

## 9. Environment facts

- Base image `tomcat:10.0.27-jdk11-temurin`; `slf4j.version` 1.7.36; compiler
  release 11.
- All five services (`auth`, `image`, `persistence`, `recommender`, `webui`)
  register the shared `TrackingFilter` from `registryclient`; `registry` does
  not.
- TeaStore already applies AspectJ for its Kieker probes; load-time weaving
  coexists with the build.

## 10. Reference

von Kistowski, Eismann, Schmitt, Bauer, Grohmann, Kounev. "TeaStore: A
Micro-Service Reference Application for Benchmarking, Modeling and Resource
Management Research." IEEE MASCOTS 2018, Milwaukee.
