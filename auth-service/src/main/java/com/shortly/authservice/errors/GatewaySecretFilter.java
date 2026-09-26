package com.shortly.authservice.errors;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.security.JwtAuthenticationFilter;
import com.shortly.authservice.security.ProblemResponses;
import com.shortly.contracts.internal.InternalHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Refuses any request that did not arrive through the gateway. Every service behind the gateway
 * trusts {@code X-User-Id} because the gateway sets it from a verified token. */
public class GatewaySecretFilter extends OncePerRequestFilter {

    private final AuthProperties properties;

    public GatewaySecretFilter(AuthProperties properties) {
        this.properties = properties;
    }

    /** Actuator is exempt. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!properties.requireGatewaySecret()) {
            filterChain.doFilter(request, response);
            return;
        }

        // The gateway cannot prove its own identity, and neither can this filter, so both sides are
        // configured with the same secret. Deliberately not a JWT:
        String presented = request.getHeader(InternalHeaders.GATEWAY_SECRET);

        if (!secretsMatch(presented, properties.gatewaySecret())) {
            ProblemResponses.write(response, HttpStatus.FORBIDDEN, "gateway-secret-invalid",
                    "This endpoint may only be reached through the API gateway.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** Constant-time comparison. */
    private static boolean secretsMatch(String presented, String expected) {
        if (presented == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
