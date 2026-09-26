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

/**
 * Refuses any request that did not arrive through the gateway.
 *
 * <p>Every service behind the gateway trusts {@code X-User-Id} because the gateway sets it from a
 * verified token. That trust is only sound while the gateway is the only way in. If a service
 * port is reachable directly - a container network, a misconfigured security group, a developer
 * with curl - a caller can set that header to anyone and read or modify their data. Requiring a
 * shared secret the gateway strips from inbound traffic closes it.
 *
 * <p>Runs before {@link JwtAuthenticationFilter} so an unsigned request is rejected without any
 * token parsing done on it. Constructed by {@code SecurityConfig} rather than component-scanned,
 * for the same reason as that filter: the security chain is the only registration point.
 */
public class GatewaySecretFilter extends OncePerRequestFilter {

    private final AuthProperties properties;

    public GatewaySecretFilter(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * Actuator is exempt.
     * <p>
     * The container health check runs inside the container and has no way to present the gateway
     * secret, so applying the check to actuator would make every instance report unhealthy and be
     * restarted forever. In production actuator belongs on a separate management port with its own
     * network policy; exempting it here keeps the local stack honest.
     */
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

        // The gateway cannot prove its own identity, and neither can this filter, so both sides
        // are configured with the same secret. Deliberately not a JWT: this check is "did the
        // request come from inside", not "who sent it".
        String presented = request.getHeader(InternalHeaders.GATEWAY_SECRET);

        if (!secretsMatch(presented, properties.gatewaySecret())) {
            ProblemResponses.write(response, HttpStatus.FORBIDDEN, "gateway-secret-invalid",
                    "This endpoint may only be reached through the API gateway.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison.
     * <p>
     * A naive {@code equals} short-circuits on the first differing byte, so an attacker can
     * discover the secret one character at a time by measuring how long the rejection takes.
     * {@link MessageDigest#isEqual} does not, and on a short value the cost is irrelevant.
     */
    private static boolean secretsMatch(String presented, String expected) {
        if (presented == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
