package io.github.ande1922.moduvera.web;

import io.github.ande1922.moduvera.error.CodedException;
import java.util.Optional;
import org.springframework.http.HttpStatusCode;

@FunctionalInterface
public interface ProblemStatusContributor {

    Optional<HttpStatusCode> statusFor(CodedException exception);
}
