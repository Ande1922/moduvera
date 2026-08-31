package io.github.ande1922.moduvera.web;

import io.github.ande1922.moduvera.error.CodedException;
import org.springframework.http.HttpStatusCode;

@FunctionalInterface
public interface ProblemStatusResolver {

    HttpStatusCode statusFor(CodedException exception);
}
