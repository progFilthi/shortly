package com.shortly.videoservice.security;

import com.shortly.contracts.internal.InternalHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects requests that did not come through the gateway, and publishes the caller's identity.
 *
 * <p>Controllers receive the identity through {@link CallerIdentityArgumentResolver}, so no
 * controller reads {@code X-User-Id} itself. That is the point: the header is only trustworthy
 * because this filter verified the gateway secret first, and routing every read through one place
 * makes the check very hard to bypass by accident.
 *
 * <p>Without the secret check, any caller that can reach this port directly sets the header to
 * whoever they like, and every ownership check in the service becomes theatre.
 */
@Component
@Order(1)
public class CallerIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallerIdentityFilter.class);

    private final boolean requireGatewaySecret;
    private final String expectedSecret;

    public CallerIdentityFilter(
            @Value("${gateway.internal-secret:}") String expectedSecret,
            @Value("${gateway.require-secret:true}") boolean requireGatewaySecret) {
        this.expectedSecret = expectedSecret;
        this.requireGatewaySecret = requireGatewaySecret;
    }

    /**
     * Actuator is exempt.
     * <p>
     * The container health check runs inside the container and has no way to present the gateway
     * secret, so applying the check to actuator would make every service report unhealthy and be
     * restarted forever. In production actuator would sit on a separate management port with its
     * own network policy; exempting it here keeps the local stack honest.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException, ServletException {

        if (requireGatewaySecret
                && !secretsMatch(request.getHeader(InternalHeaders.GATEWAY_SECRET), expectedSecret)) {
            log.warn("Rejected a request to {} with no valid gateway secret", request.getRequestURI());
            writeForbidden(response);
            return;
        }

        String userId = request.getHeader(InternalHeaders.USER_ID);
        if (userId != null && !userId.isBlank()) {
            request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, new CallerIdentity(
                    userId,
                    request.getHeader(InternalHeaders.USERNAME),
                    request.getHeader(InternalHeaders.EMAIL)));
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison.
     * <p>
     * {@code String.equals} short-circuits on the first differing byte, which lets an attacker
     * recover the secret one character at a time by timing rejections.
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

    /**
     * Hand-written, because this filter runs before {@code @RestControllerAdvice} and so has no
     * handler to format the response.
     */
    private static void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"title":"gateway-secret-invalid","status":403,\
                "code":"gateway-secret-invalid",\
                "detail":"This endpoint may only be reached through the API gateway."}""");
    }
}
