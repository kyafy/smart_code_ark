package com.smartark.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class TokenService {
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(
            ObjectMapper objectMapper,
            @Value("${smartark.auth.jwt-secret:change-me}") String secret,
            @Value("${smartark.auth.token-ttl-seconds:604800}") long ttlSeconds
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String issueToken(String userId) {
        long now = Instant.now().getEpochSecond();
        long exp = now + ttlSeconds;

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("iat", now);
        payload.put("exp", exp);

        String headerPart = base64Url(json(header));
        String payloadPart = base64Url(json(payload));
        String signingInput = headerPart + "." + payloadPart;
        String signaturePart = base64Url(hmacSha256(signingInput));
        return signingInput + "." + signaturePart;
    }

    public Optional<String> parseUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String signingInput = parts[0] + "." + parts[1];
            String expectedSig = base64Url(hmacSha256(signingInput));
            if (!constantTimeEquals(expectedSig, parts[2])) {
                return Optional.empty();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadBytes, Map.class);
            Object exp = payload.get("exp");
            if (!(exp instanceof Number) || Instant.now().getEpochSecond() >= ((Number) exp).longValue()) {
                return Optional.empty();
            }
            Object sub = payload.get("sub");
            if (sub == null) {
                return Optional.empty();
            }
            return Optional.of(String.valueOf(sub));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private byte[] hmacSha256(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(String raw) {
        return base64Url(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
