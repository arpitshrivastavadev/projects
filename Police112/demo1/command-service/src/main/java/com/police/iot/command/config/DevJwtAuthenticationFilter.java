package com.police.iot.command.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.security.jwt.dev", name = "enabled", havingValue = "true")
public class DevJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper;
    private final String issuer;
    private final byte[] secret;

    public DevJwtAuthenticationFilter(ObjectMapper objectMapper, org.springframework.core.env.Environment environment) {
        this.objectMapper = objectMapper;
        this.issuer = environment.getRequiredProperty("app.security.jwt.dev.issuer");
        String configuredSecret = environment.getRequiredProperty("app.security.jwt.dev.secret");
        this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.security.jwt.dev.secret must be at least 32 characters for HS256");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Map<String, Object> claims = validateAndParse(authorization.substring(BEARER_PREFIX.length()));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    claims,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException ex) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(ex.getMessage());
        }
    }

    private Map<String, Object> validateAndParse(String token) throws IOException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid bearer token format");
        }

        byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
        Map<String, Object> header = objectMapper.readValue(headerBytes, new TypeReference<>() {
        });
        if (!"HS256".equals(header.get("alg"))) {
            throw new IllegalArgumentException("Unsupported JWT algorithm");
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] providedSignature = Base64.getUrlDecoder().decode(parts[2]);
        if (!java.security.MessageDigest.isEqual(expectedSignature, providedSignature)) {
            throw new IllegalArgumentException("Invalid bearer token signature");
        }

        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        Map<String, Object> claims = objectMapper.readValue(payloadBytes, new TypeReference<>() {
        });

        Object iss = claims.get("iss");
        if (!(iss instanceof String issValue) || !issuer.equals(issValue)) {
            throw new IllegalArgumentException("Invalid token issuer");
        }

        Object exp = claims.get("exp");
        if (!(exp instanceof Number expValue) || Instant.now().isAfter(Instant.ofEpochSecond(expValue.longValue()))) {
            throw new IllegalArgumentException("Token is expired");
        }

        return claims;
    }

    private byte[] hmacSha256(byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to verify JWT signature", e);
        }
    }
}
