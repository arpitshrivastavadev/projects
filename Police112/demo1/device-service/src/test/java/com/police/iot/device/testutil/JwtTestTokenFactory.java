package com.police.iot.device.testutil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JwtTestTokenFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JwtTestTokenFactory() {
    }

    public static String createHs256Token(String issuer, String secret, Map<String, Object> claims) {
        try {
            Instant now = Instant.now();
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", issuer);
            payload.put("sub", "test-user");
            payload.put("iat", now.getEpochSecond());
            payload.put("exp", now.plusSeconds(3600).getEpochSecond());
            payload.putAll(claims);

            String headerPart = toBase64UrlJson(header);
            String payloadPart = toBase64UrlJson(payload);
            String signingInput = headerPart + "." + payloadPart;
            byte[] signature = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
            String signaturePart = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
            return signingInput + "." + signaturePart;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build JWT test token", e);
        }
    }

    private static String toBase64UrlJson(Map<String, Object> value) throws JsonProcessingException {
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hmacSha256(byte[] input, byte[] secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input);
    }
}
