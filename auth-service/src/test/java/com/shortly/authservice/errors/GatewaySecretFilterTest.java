package com.shortly.authservice.errors;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.contracts.internal.InternalHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway-secret check is the reason a service behind the gateway can trust its identity
 * headers at all. Covered directly, since the controller slice runs with the check disabled.
 */
class GatewaySecretFilterTest {

    private static final String SECRET = "s3cr3t-gateway-value";

    private static AuthProperties propertiesRequiring(String secret) {
        return new AuthProperties(
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(20),
                3, Duration.ofMinutes(15), 4, 10, 128,
                secret, true, "shortly-test", "c2lnbmluZy1rZXk=");
    }

    @Test
    void rejectsARequestWithNoSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("X-User-Id", "victim");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new GatewaySecretFilter(propertiesRequiring(SECRET)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("gateway-secret-invalid");
        assertThat(chain.getRequest()).as("must not reach the handler").isNull();
    }

    @Test
    void rejectsTheWrongSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, "brute-force-guess");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new GatewaySecretFilter(propertiesRequiring(SECRET)).doFilter(request, response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void acceptsTheConfiguredSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new GatewaySecretFilter(propertiesRequiring(SECRET)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void failsClosedWhenNoSecretIsConfigured() throws Exception {
        // A service started without its secret must reject everything, not accept everything.
        // Failing open here would turn a missing environment variable into a total auth bypass.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new GatewaySecretFilter(propertiesRequiring("")).doFilter(request, response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void theCheckCanBeDisabledForStandaloneLocalDevelopment() throws Exception {
        AuthProperties disabled = new AuthProperties(
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(20),
                3, Duration.ofMinutes(15), 4, 10, 128,
                "", false, "shortly-test", "c2lnbmluZy1rZXk=");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new GatewaySecretFilter(disabled).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
