package com.shortly.apigateway.filter;

import com.shortly.apigateway.errors.ProblemWriter;
import com.shortly.contracts.internal.InternalHeaders;
import com.shortly.jwt.JwtCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * Verifies the access token and converts it into the identity headers downstream services trust.
 *
 * <p>Ordered first so an unauthenticated request is rejected at the edge, before routing, before
 * a connection is opened, and before a service is bothered.
 *
 * <p><b>Why a caller cannot choose its own identity.</b> The wrapper overwrites
 * {@code X-User-Id}, {@code X-Username}, and {@code X-Email} with values read from the verified
 * token, and drops any inbound copies. A client that sends its own {@code X-User-Id} is asking to
 * be someone else, so what it sent is discarded rather than forwarded. Every service behind the
 * gateway also requires {@code X-Gateway-Secret}, which the gateway strips from inbound requests,
 * so the identity headers cannot be forged from outside either.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Reachable without a token, enumerated rather than a "/api/v1/auth/**" wildcard.
     *
     * <p>Register, login, refresh and logout are unauthenticated by definition. A wildcard would
     * also exempt /api/v1/auth/me, which needs a token - and an unverified /me means the gateway
     * forwards an anonymous request and the caller identity is never established here at all.
     *
     * <p>Matches by prefix so a future sub-path under one of these stays public; anything else
     * under /api/v1/auth requires a token.
     */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/actuator");

    /** Headers the gateway owns. Never taken from the client. */
    private static final Set<String> INTERNAL_HEADERS = Set.of(
            InternalHeaders.USER_ID.toLowerCase(),
            InternalHeaders.USERNAME.toLowerCase(),
            InternalHeaders.EMAIL.toLowerCase(),
            InternalHeaders.GATEWAY_SECRET.toLowerCase());

    private final JwtCodec jwtCodec;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException, ServletException {

        String token = extractBearerToken(request);

        if (token == null) {
            ProblemWriter.write(response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Authorization header with a Bearer token is required.");
            return;
        }

        JwtCodec.Verification verification = jwtCodec.verify(token);

        if (!verification.succeeded()) {
            // Both are 401, but the codes differ and the client needs the difference: an expired
            // token means "refresh and retry", an invalid one means "sign in again".
            if (verification.failure() == JwtCodec.FailureReason.EXPIRED) {
                ProblemWriter.write(response, HttpStatus.UNAUTHORIZED, "token-expired",
                        "Access token has expired. Refresh and retry.");
            } else {
                ProblemWriter.write(response, HttpStatus.UNAUTHORIZED, "token-invalid",
                        "Access token is invalid. Sign in again.");
            }
            return;
        }

        filterChain.doFilter(new IdentityHeaderRequestWrapper(request, verification.claims()), response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Presents the token's identity as request headers.
     * <p>
     * A wrapper rather than a mutation of the original header map, so the container's own view is
     * untouched and a pooled request object cannot leak one caller's identity to the next.
     * <p>
     * A class rather than a record, because {@link HttpServletRequestWrapper} is a class and a
     * record may only implement interfaces.
     */
    private static final class IdentityHeaderRequestWrapper extends HttpServletRequestWrapper {

        private final JwtCodec.AccessTokenClaims claims;

        private IdentityHeaderRequestWrapper(HttpServletRequest request,
                                             JwtCodec.AccessTokenClaims claims) {
            super(request);
            this.claims = claims;
        }

        @Override
        public String getHeader(String name) {
            if (InternalHeaders.USER_ID.equalsIgnoreCase(name)) {
                return claims.userId();
            }
            if (InternalHeaders.USERNAME.equalsIgnoreCase(name)) {
                return claims.username();
            }
            if (InternalHeaders.EMAIL.equalsIgnoreCase(name)) {
                return claims.email();
            }
            if (InternalHeaders.GATEWAY_SECRET.equalsIgnoreCase(name)) {
                // The real value is added by the route filter from gateway configuration. A
                // caller-supplied one is dropped here so it can never reach a service.
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String single = getHeader(name);
            if (single != null) {
                return Collections.enumeration(List.of(single));
            }
            // Returning an empty enumeration rather than the original headers is deliberate: a
            // request header may appear under several names and something downstream could read
            // the raw one and find a forged identity.
            return Collections.emptyEnumeration();
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(List.of(
                    InternalHeaders.USER_ID, InternalHeaders.USERNAME, InternalHeaders.EMAIL));
            Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> name != null && !INTERNAL_HEADERS.contains(name.toLowerCase()))
                    .forEach(names::add);
            return Collections.enumeration(names);
        }
    }
}
