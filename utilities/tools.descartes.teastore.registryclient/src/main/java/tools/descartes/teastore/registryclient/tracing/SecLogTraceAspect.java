package tools.descartes.teastore.registryclient.tracing;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
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
   * Request-parameter binding names whose values are credentials and must never
   * be written to the log, matched case-insensitively. Applied uniformly to
   * every woven endpoint, so the redaction carries no attack-specific knowledge.
   */
  private static final Set<String> SENSITIVE_PARAM_NAMES =
      new HashSet<>(Arrays.asList("password", "passwd", "pwd"));

  /** Per-method cache of which argument positions hold a credential value. */
  private static final ConcurrentHashMap<Method, boolean[]> REDACT_MASKS =
      new ConcurrentHashMap<>();

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
      log.info("[{}][ENTRY] args={}", method, formatArgs(jp));
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

  /**
   * Render arguments as a bounded, single-line, type-tagged string. Positions
   * bound to a credential parameter are emitted as {@code ***} and never
   * rendered.
   */
  private static String formatArgs(ProceedingJoinPoint jp) {
    Object[] args = jp.getArgs();
    if (args == null || args.length == 0) {
      return "[]";
    }
    boolean[] redact = redactMask((MethodSignature) jp.getSignature());
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      if (i < redact.length && redact[i]) {
        sb.append("***");
      } else {
        sb.append(describe(args[i]));
      }
    }
    return sb.append(']').toString();
  }

  /**
   * Build (and cache) the per-position redaction mask for a method: a position
   * is redacted when its JAX-RS binding name (for example
   * {@code @QueryParam("password")}) is in SENSITIVE_PARAM_NAMES. Keying on the
   * declared binding keeps the rule uniform across endpoints and free of any
   * attack-specific knowledge.
   */
  private static boolean[] redactMask(MethodSignature signature) {
    return REDACT_MASKS.computeIfAbsent(
        signature.getMethod(), SecLogTraceAspect::computeRedactMask);
  }

  private static boolean[] computeRedactMask(Method method) {
    Annotation[][] paramAnnotations = method.getParameterAnnotations();
    boolean[] mask = new boolean[paramAnnotations.length];
    for (int i = 0; i < paramAnnotations.length; i++) {
      for (Annotation annotation : paramAnnotations[i]) {
        if (isSensitiveBinding(annotation)) {
          mask[i] = true;
          break;
        }
      }
    }
    return mask;
  }

  /**
   * True when the annotation is a JAX-RS parameter binding (javax or jakarta
   * {@code ws.rs}, e.g. QueryParam/FormParam/HeaderParam) whose bound name is a
   * credential name. Read reflectively so the aspect needs no JAX-RS dependency.
   */
  private static boolean isSensitiveBinding(Annotation annotation) {
    String type = annotation.annotationType().getName();
    if (!type.startsWith("jakarta.ws.rs.") && !type.startsWith("javax.ws.rs.")) {
      return false;
    }
    try {
      Object value = annotation.annotationType().getMethod("value").invoke(annotation);
      return value instanceof String
          && SENSITIVE_PARAM_NAMES.contains(((String) value).toLowerCase(Locale.ROOT));
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  /**
   * Describe one argument. Large or binary payloads are summarised by type and
   * length, never dumped, so image bytes do not explode the log. Value strings
   * are truncated to MAX_ARG_CHARS.
   *
   * <p>Credential arguments (e.g. the plaintext password on the auth login
   * endpoint) are redacted earlier, in {@link #formatArgs}, keyed on the
   * parameter's JAX-RS binding name, so they never reach this renderer.
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
