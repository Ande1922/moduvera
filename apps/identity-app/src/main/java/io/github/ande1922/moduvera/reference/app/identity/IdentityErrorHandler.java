package io.github.ande1922.moduvera.reference.app.identity;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class IdentityErrorHandler {

    @ExceptionHandler(IdentityException.class)
    ProblemDetail identity(IdentityException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setType(URI.create("urn:problem:" + exception.code()));
        detail.setTitle(exception.status().getReasonPhrase());
        detail.setProperty("code", exception.code());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request does not satisfy the identity contract");
        detail.setType(URI.create("urn:problem:request.validation-failed"));
        detail.setTitle("Bad Request");
        detail.setProperty("code", "request.validation-failed");
        return detail;
    }
}
