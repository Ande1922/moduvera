package io.github.ande1922.moduvera.reference.app.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
final class IdentityController {

    private final IdentityService identities;

    IdentityController(IdentityService identities) {
        this.identities = identities;
    }

    @PostMapping("/v1/session/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var session = identities.login(request.username(), request.password(), request.tenantId());
        return new LoginResponse(session.token(), session.expiresAt());
    }

    @PostMapping("/internal/api/v1/token/exchange")
    TokenResponse exchange(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody TokenExchangeRequest request) {
        var token = identities.exchange(authorization, request.sessionToken(), request.audience());
        return new TokenResponse(token.token(), "Bearer", token.expiresAt());
    }

    @PostMapping("/internal/api/v1/service-token")
    TokenResponse serviceToken(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody ServiceTokenRequest request) {
        var token = identities.serviceToken(
                authorization,
                request.tenantId(),
                request.audience(),
                request.initiatorType(),
                request.initiatorId());
        return new TokenResponse(token.token(), "Bearer", token.expiresAt());
    }

    record LoginRequest(
            @NotBlank String username, @NotBlank String password, @NotBlank String tenantId) {}

    record LoginResponse(String token, Instant expiresAt) {}

    record TokenExchangeRequest(@NotBlank String sessionToken, @NotBlank String audience) {}

    record ServiceTokenRequest(
            @NotBlank String tenantId,
            @NotBlank String audience,
            @NotBlank String initiatorType,
            @NotBlank String initiatorId) {}

    record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {}
}
