package io.github.ande1922.moduvera.web;

import io.github.ande1922.moduvera.error.CodedException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public final class ApiExceptionHandler {

    private static final String VALIDATION_CODE = "request.validation-failed";
    private static final String UNEXPECTED_CODE = "system.unexpected";
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final ExceptionHandlerMethodResolver FRAMEWORK_HANDLERS =
            new ExceptionHandlerMethodResolver(ResponseEntityExceptionHandler.class);

    private final ProblemStatusResolver statusResolver;

    public ApiExceptionHandler(ProblemStatusResolver statusResolver) {
        this.statusResolver = statusResolver;
    }

    @ExceptionHandler(CodedException.class)
    ResponseEntity<ProblemDetail> handleCoded(CodedException exception, HttpServletRequest request) {
        var status = statusResolver.statusFor(exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(URI.create("urn:problem:" + exception.code().value()));
        problem.setTitle("Request could not be completed");
        problem.setProperty("code", exception.code().value());
        addCorrelation(problem, request);
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more request fields are invalid");
        problem.setType(URI.create("urn:problem:" + VALIDATION_CODE));
        problem.setTitle("Request validation failed");
        problem.setProperty("code", VALIDATION_CODE);
        problem.setProperty("errors", validationErrors(exception));
        addCorrelation(problem, request);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more request values are invalid");
        problem.setType(URI.create("urn:problem:" + VALIDATION_CODE));
        problem.setTitle("Request validation failed");
        problem.setProperty("code", VALIDATION_CODE);
        problem.setProperty(
                "errors",
                exception.getConstraintViolations().stream()
                        .map(violation -> Map.of(
                                "field", violation.getPropertyPath().toString(),
                                "code", violation.getConstraintDescriptor()
                                        .getAnnotation()
                                        .annotationType()
                                        .getSimpleName(),
                                "message", violation.getMessage()))
                        .sorted(Comparator.comparing(error -> error.get("field")))
                        .toList());
        addCorrelation(problem, request);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request, HttpServletResponse response) throws Exception {
        // Preserve specific mappings for nested causes that the catch-all would otherwise shadow.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof CodedException coded) {
                return handleCoded(coded, request);
            }
            if (cause instanceof MethodArgumentNotValidException validation) {
                return handleValidation(validation, request);
            }
            if (cause instanceof ConstraintViolationException validation) {
                return handleConstraintViolation(validation, request);
            }
            if (AnnotatedElementUtils.hasAnnotation(cause.getClass(), ResponseStatus.class)) {
                throw exception;
            }
        }
        // Leave Spring-owned status and response bodies with the existing MVC resolvers.
        if (FRAMEWORK_HANDLERS.resolveMethod(exception) != null) {
            throw exception;
        }
        ServletRequestDiagnostics diagnostics = ServletRequestDiagnostics.establish(request, false);
        diagnostics.failed(exception);
        try (var ignored = diagnostics.snapshot().openScope()) {
            LOG.atError().addKeyValue("error.code", "SYS_UNEXPECTED").setCause(exception)
                    .log("请求处理发生未预期异常");
        }
        if (response.isCommitted()) {
            return null;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create("urn:problem:" + UNEXPECTED_CODE));
        problem.setTitle("Request could not be completed");
        problem.setProperty("code", UNEXPECTED_CODE);
        addCorrelation(problem, request);
        return ResponseEntity.internalServerError().body(problem);
    }

    private static List<Map<String, String>> validationErrors(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField).thenComparing(FieldError::getCode))
                .map(error -> Map.of(
                        "field", error.getField(),
                        "code", error.getCode() == null ? "invalid" : error.getCode(),
                        "message", error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .toList();
    }

    private static void addCorrelation(ProblemDetail problem, HttpServletRequest request) {
        problem.setProperty("correlationId", ServletRequestDiagnostics.establish(request, false).correlationId());
    }
}
