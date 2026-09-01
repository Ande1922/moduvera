package io.github.ande1922.moduvera.reference.catalog.api.architecturefixture;

import org.springframework.web.bind.annotation.RequestHeader;

public record TransportApiViolation(@RequestHeader("Tenant-Id") String tenantId) {}
