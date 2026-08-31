package io.github.ande1922.moduvera.reference.app.identity;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class IdentityJwksController {

    private final IdentitySigningKeys keys;

    IdentityJwksController(IdentitySigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/oauth2/jwks")
    Map<String, Object> jwks() {
        return keys.publicJwkSet();
    }
}
