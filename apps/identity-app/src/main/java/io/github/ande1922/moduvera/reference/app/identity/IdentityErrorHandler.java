package io.github.ande1922.moduvera.reference.app.identity;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import io.github.ande1922.moduvera.web.ServletRequestDiagnostics;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class IdentityErrorHandler {

    @ExceptionHandler(IdentityException.class)
    ProblemDetail identity(IdentityException exception, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setType(URI.create("urn:problem:" + exception.code()));
        detail.setTitle(exception.status().getReasonPhrase());
        detail.setProperty("code", exception.code());
        detail.setProperty("correlationId", ServletRequestDiagnostics.establish(request, false).correlationId());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request does not satisfy the identity contract");
        detail.setType(URI.create("urn:problem:request.validation-failed"));
        detail.setTitle("Bad Request");
        detail.setProperty("code", "request.validation-failed");
        detail.setProperty("correlationId", ServletRequestDiagnostics.establish(request, false).correlationId());
        return detail;
    }
}
