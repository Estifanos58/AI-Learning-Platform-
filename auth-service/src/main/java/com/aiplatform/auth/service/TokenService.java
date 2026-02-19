package com.aiplatform.auth.service;

import com.aiplatform.auth.config.JwtProperties;
import com.aiplatform.auth.domain.User;
import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class TokenService {

    private final JwtProperties jwtProperties;
    private final ResourceLoader resourceLoader;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    public TokenService(JwtProperties jwtProperties, ResourceLoader resourceLoader) {
        this.jwtProperties = jwtProperties;
        this.resourceLoader = resourceLoader;
        loadKeys();
    }

    private void loadKeys() {
        try {
            var privateKeyResource = resourceLoader.getResource(jwtProperties.getPrivateKeyPath());
            String privateKeyPem = new String(privateKeyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            privateKey = parsePrivateKey(privateKeyPem);

            var publicKeyResource = resourceLoader.getResource(jwtProperties.getPublicKeyPath());
            String publicKeyPem = new String(publicKeyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            publicKey = parsePublicKey(publicKeyPem);
        } catch (Exception e) {
            log.error("Failed to load JWT keys: {}", e.getMessage());
            throw new RuntimeException("Failed to load JWT keys", e);
        }
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(stripped);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private PublicKey parsePublicKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(stripped);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getAccessTokenExpiration());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("status", user.getStatus().name())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateAccessToken(String token) {
        try {
            parseAccessToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    public long getRefreshTokenExpiration() {
        return jwtProperties.getRefreshTokenExpiration();
    }

    public long getAccessTokenExpiration() {
        return jwtProperties.getAccessTokenExpiration();
    }
}
