package tools.descartes.teastore.registryclient.tracing;

import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SecLogTrace request-boundary logging aspect.
 *
 * <p>Emits one uniform, attack-blind log pair (ENTRY / EXIT or ERROR) for every
 * server-side REST or servlet method across the TeaStore services. The weave
 * scope is fixed in META-INF/aop.xml and does not encode any knowledge of the
 * injected attacks. Logging is applied by a blanket pointcut over the request
 * boundary, not per method and not per attack.
 *
 * <p>Trace correlation: the OpenTelemetry Java agent populates the SLF4J MDC
 * with {@code trace_id} and {@code span_id} for Logback while a span is active.
 * logback.xml renders those keys, so each log line carries the same
 * {@code trace_id} that Jaeger holds. This mirrors the SUT1 capture path
 * (scrape {@code trace_id} from the console log, resolve it in the trace store).
 *
 * <p>If MDC-based correlation ever fails to populate (agent version mismatch),
 * read the id explicitly instead. Add an opentelemetry-api (scope provided)
 * dependency and, inside the advice:
 *   String tid = io.opentelemetry.api.trace.Span.current()
 *                   .getSpanContext().getTraceId();
 * then append tid to the message. The MDC path is the default because it needs
 * no extra dependency and matches SUT1.
 */
@Aspect
public class SecLogTraceAspect {

  /** Per-target-class logger cache, so lines attribute to the service class. */
  private static final ConcurrentHashMap<Class<?>, Logger> LOGGERS =
      new ConcurrentHashMap<>();

  /** Max characters kept per rendered argument value. */
  private static final int MAX_ARG_CHARS = 256;

  /**
   * Weave scope: public methods in any service's own rest or servlet package.
   * registryclient and entities are excluded in aop.xml, so the client-side
   * REST wrappers and the entity classes are never matched.
   */
  @Around(
      "execution(public * tools.descartes.teastore..rest..*(..)) "
      + "|| execution(public * tools.descartes.teastore..servlet..*(..))")
  public Object logRequestBoundary(ProceedingJoinPoint jp) throws Throwable {
    Class<?> targetType = jp.getSignature().getDeclaringType();
    Logger log = LOGGERS.computeIfAbsent(targetType, LoggerFactory::getLogger);

    String method = jp.getSignature().getName();
    long startNanos = System.nanoTime();

    if (log.isInfoEnabled()) {
      log.info("[{}][ENTRY] args={}", method, formatArgs(jp.getArgs()));
    }

    try {
      Object result = jp.proceed();
      long ms = (System.nanoTime() - startNanos) / 1_000_000L;
      if (log.isInfoEnabled()) {
        log.info("[{}][EXIT] status=OK durationMs={}", method, ms);
      }
      return result;
    } catch (Throwable t) {
      long ms = (System.nanoTime() - startNanos) / 1_000_000L;
      log.warn("[{}][ERROR] ex={}:{} durationMs={}",
          method, t.getClass().getSimpleName(), safe(t.getMessage()), ms);
      throw t;
    }
  }

  /** Render arguments as a bounded, single-line, type-tagged string. */
  private static String formatArgs(Object[] args) {
    if (args == null || args.length == 0) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(describe(args[i]));
    }
    return sb.append(']').toString();
  }

  /**
   * Describe one argument. Large or binary payloads are summarised by type and
   * length, never dumped, so image bytes do not explode the log. Value strings
   * are truncated to MAX_ARG_CHARS.
   *
   * <p>REDACTION NOTE: the auth login endpoint receives a plaintext password
   * argument. Do not log it. Before freezing the corpus, add a redaction branch
   * here (for example, by the declaring type or parameter position of the auth
   * endpoint) so password values never reach the logs.
   */
  private static String describe(Object arg) {
    if (arg == null) {
      return "null";
    }
    if (arg instanceof byte[]) {
      return "byte[" + ((byte[]) arg).length + "]";
    }
    if (arg instanceof char[]) {
      return "char[" + ((char[]) arg).length + "]";
    }
    String rendered = safe(String.valueOf(arg));
    if (rendered.length() > MAX_ARG_CHARS) {
      rendered = rendered.substring(0, MAX_ARG_CHARS) + "...(" + rendered.length() + ")";
    }
    return rendered;
  }

  /** Strip newlines and tabs so one event stays on one log line. */
  private static String safe(String s) {
    if (s == null) {
      return "null";
    }
    return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
  }
}
