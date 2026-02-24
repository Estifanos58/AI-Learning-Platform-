package com.aiplatform.gateway.security;

import com.aiplatform.gateway.config.GatewayJwtProperties;
import lombok.Getter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Getter
@Component
public class PublicKeyProvider {

    private final PublicKey publicKey;

    public PublicKeyProvider(ResourceLoader resourceLoader, GatewayJwtProperties jwtProperties) {
        try {
            Resource resource = resourceLoader.getResource(jwtProperties.getPublicKeyLocation());
            String key = resource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(key);
            this.publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load JWT public key", exception);
        }
    }
}
