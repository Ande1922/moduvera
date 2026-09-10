package io.github.ande1922.moduvera.web;

import io.github.ande1922.moduvera.error.CodedException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final String VALIDATION_CODE = "request.validation-failed";

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
