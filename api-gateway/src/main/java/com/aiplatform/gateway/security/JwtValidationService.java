package com.aiplatform.gateway.security;

import com.aiplatform.gateway.config.GatewayJwtProperties;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtValidationService {

    private final PublicKeyProvider publicKeyProvider;
    private final GatewayJwtProperties jwtProperties;

    public boolean isValid(String token) {
        try {
            Jwts.parser()
                    .verifyWith(publicKeyProvider.getPublicKey())
                    .requireIssuer(jwtProperties.getIssuer())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
