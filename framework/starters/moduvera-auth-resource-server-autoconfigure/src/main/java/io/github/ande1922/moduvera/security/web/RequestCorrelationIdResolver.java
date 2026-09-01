package io.github.ande1922.moduvera.security.web;

import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface RequestCorrelationIdResolver {

    String resolve(HttpServletRequest request);
}
