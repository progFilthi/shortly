package com.shortly.apigateway.filter;

import com.shortly.contracts.internal.InternalHeaders;
import com.shortly.jwt.JwtCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's token filter.
 *
 * <p>The important behaviour is not "it accepts good tokens" but "it refuses to let a caller
 * choose its own identity". Every test below that involves a forged {@code X-User-Id} exists
 * because if that header ever reached a service, every ownership check downstream is theatre.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString(
            "ffffffffffffffffffffffffffffffffffffffffffffffff".getBytes());
    private static final String ISSUER = "shortly-test";
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private static JwtCodec codec() {
        return new JwtCodec(SECRET, ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/videos/user/someone");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    /** The request the chain was handed, i.e. the wrapper rather than the original. */
    private static HttpServletRequest downstream(MockFilterChain chain) {
        return (HttpServletRequest) chain.getRequest();
    }

    /* ------------------------------ happy path -------------------------------- */

    @Test
    void presentsTheTokensIdentityAsHeaders() throws Exception {
        String token = codec().issueAccessToken("user-42", "tester", "tester@example.com",
                Duration.ofMinutes(15));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthenticationFilter(codec()).doFilter(requestWithToken(token), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstream(chain).getHeader(InternalHeaders.USER_ID)).isEqualTo("user-42");
        assertThat(downstream(chain).getHeader(InternalHeaders.USERNAME)).isEqualTo("tester");
        assertThat(downstream(chain).getHeader(InternalHeaders.EMAIL))
                .isEqualTo("tester@example.com");
    }

    @Test
    void passesOrdinaryHeadersThroughUntouched() throws Exception {
        String token = codec().issueAccessToken("user-42", "tester", "t@example.com",
                Duration.ofMinutes(15));
        MockHttpServletRequest request = requestWithToken(token);
        request.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(request, response, chain);

        assertThat(downstream(chain).getHeader("X-Request-Id")).isEqualTo("abc-123");
    }

    /* ------------------------- identity cannot be forged ----------------------- */

    @Test
    void discardsACallerSuppliedUserIdHeader() throws Exception {
        // The whole point. A client that sends its own X-User-Id is asking to be someone else,
        // and what it sent must be replaced by the token's subject rather than forwarded.
        String token = codec().issueAccessToken("user-42", "tester", "t@example.com",
                Duration.ofMinutes(15));
        MockHttpServletRequest request = requestWithToken(token);
        request.addHeader(InternalHeaders.USER_ID, "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(request, response, chain);

        assertThat(downstream(chain).getHeader(InternalHeaders.USER_ID))
                .as("the forged header must be replaced, not merged")
                .isEqualTo("user-42");
    }

    @Test
    void doesNotLeakTheForgedHeaderUnderAnAlternateCapitalisation() throws Exception {
        // HTTP header names are case-insensitive, and a service reading "x-user-id" would
        // otherwise still see the attacker's value.
        String token = codec().issueAccessToken("user-42", "tester", "t@example.com",
                Duration.ofMinutes(15));
        MockHttpServletRequest request = requestWithToken(token);
        request.addHeader("x-user-id", "admin");
        request.addHeader("X-USER-ID", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(request, response, chain);

        Enumeration<String> values = downstream(chain).getHeaders("x-user-id");
        assertThat(Collections.list(values)).containsExactly("user-42");
    }

    @Test
    void stripsTheGatewaySecretSoACallerSuppliedOneNeverArrives() throws Exception {
        // Services require this header. If a client's guess were forwarded, the requirement
        // would be no requirement at all.
        String token = codec().issueAccessToken("user-42", "tester", "t@example.com",
                Duration.ofMinutes(15));
        MockHttpServletRequest request = requestWithToken(token);
        request.addHeader(InternalHeaders.GATEWAY_SECRET, "guessed-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(request, response, chain);

        // The real value is added by the route filter from gateway configuration, never by the
        // caller.
        assertThat(downstream(chain).getHeader(InternalHeaders.GATEWAY_SECRET)).isNull();
    }

    @Test
    void advertisesTheInjectedIdentityButNotTheCallersOwnInternalHeaders() throws Exception {
        String token = codec().issueAccessToken("user-42", "tester", "t@example.com",
                Duration.ofMinutes(15));
        MockHttpServletRequest request = requestWithToken(token);
        request.addHeader(InternalHeaders.USER_ID, "admin");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, "guessed-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(request, response, chain);

        // The three identity headers are advertised because this filter injects them.
        assertThat(Collections.list(downstream(chain).getHeaderNames()))
                .contains(InternalHeaders.USER_ID, InternalHeaders.USERNAME, InternalHeaders.EMAIL);

        // The caller's own copies are not enumerated, so nothing downstream can go looking for
        // them and find "admin". X-Gateway-Secret is absent entirely: the real value is appended
        // later by the route filter, from gateway configuration.
        assertThat(Collections.list(downstream(chain).getHeaderNames()))
                .doesNotContain(InternalHeaders.GATEWAY_SECRET);
    }

    /* -------------------------------- rejections ------------------------------- */

    @Test
    void rejectsARequestWithNoAuthorizationHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(requestWithToken(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthenticated");
        assertThat(chain.getRequest()).as("must not reach the handler").isNull();
    }

    @Test
    void distinguishesExpiredFromInvalid() throws Exception {
        // The client has to be able to tell "refresh and retry" from "sign in again", and it
        // cannot do that if both come back as an undifferentiated 401.
        String token = codec().issueAccessToken("user-42", "tester", "t@example.com",
                Duration.ofMinutes(15));
        // Verified against a clock 16 minutes on, so the token is past its expiry.
        JwtCodec later = new JwtCodec(SECRET, ISSUER,
                Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));

        MockHttpServletResponse expired = new MockHttpServletResponse();
        new JwtAuthenticationFilter(later).doFilter(requestWithToken(token), expired,
                new MockFilterChain());
        assertThat(expired.getStatus()).isEqualTo(401);
        assertThat(expired.getContentAsString()).contains("token-expired");

        MockHttpServletResponse invalid = new MockHttpServletResponse();
        new JwtAuthenticationFilter(codec()).doFilter(requestWithToken("garbage"), invalid,
                new MockFilterChain());
        assertThat(invalid.getContentAsString()).contains("token-invalid");
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() throws Exception {
        String forged = new JwtCodec(OTHER_SECRET, ISSUER, Clock.fixed(NOW, ZoneOffset.UTC))
                .issueAccessToken("admin", "admin", "admin@example.com", Duration.ofHours(24));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JwtAuthenticationFilter(codec()).doFilter(requestWithToken(forged), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void skipsPublicPathsEntirely() throws Exception {
        // Auth endpoints and health must work with no token at all. shouldNotFilter is what makes
        // that true; if it ever stops matching, login silently breaks.
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(codec());

        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/auth/refresh",
                "/api/v1/auth/register", "/actuator/health"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            assertThat(filter.shouldNotFilter(request))
                    .as("%s must be reachable without a token", path)
                    .isTrue();
        }

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/videos/x")))
                .isFalse();
    }
}
