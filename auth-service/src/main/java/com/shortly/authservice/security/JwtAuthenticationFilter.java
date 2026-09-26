package com.shortly.authservice.security;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.jwt.JwtCodec;
import com.shortly.authservice.errors.GatewaySecretFilter;
import com.shortly.authservice.jwt.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Turns a valid access token into an authenticated request identity.
 *
 * <p>Rejects a token that is present but invalid, rather than falling through to anonymous. A
 * client holding a stale token must be told so; letting it through unauthenticated would make a
 * logged-out user look like an anonymous one and hide the reason their request failed.
 * <p>
 * {@code @Order} is required, not decorative. This class is both a {@code Filter} bean and a
 * member of the security filter chain, and Spring Security has to resolve an order for a
 * registered filter; without the annotation the chain fails to build at startup.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final AuthProperties properties;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, AuthProperties properties) {
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var verification = tokenProvider.codec().verify(token);

        if (!verification.succeeded()) {
            // Distinguish the two, because the recovery differs: expired means refresh and
            // retry, invalid means sign in again. Collapsing them leaves a client unable to
            // recover on its own.
            if (verification.failure() == JwtCodec.FailureReason.EXPIRED) {
                ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, "token-expired",
                        "Access token has expired. Refresh and retry.");
            } else {
                ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, "token-invalid",
                        "Access token is invalid. Sign in again.");
            }
            return;
        }

        var claims = verification.claims();

        var verified = claims;
        var identity = new AuthenticatedUser(
                verified.userId(), verified.username(), verified.email());

        var authentication = new UsernamePasswordAuthenticationToken(
                identity, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, identity);

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
