package io.github.ande1922.moduvera.reference.app.identity;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.UUID;

public final class IdentitySigningKeys {

    private final RSAKey rsa;

    public IdentitySigningKeys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("could not initialize identity signing keys", failure);
        }
    }

    RSAKey privateJwk() {
        return rsa;
    }

    public RSAPublicKey publicKey() {
        try {
            return rsa.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException failure) {
            throw new IllegalStateException("could not expose identity public key", failure);
        }
    }

    public Map<String, Object> publicJwkSet() {
        return Map.of("keys", java.util.List.of(rsa.toPublicJWK().toJSONObject()));
    }
}
