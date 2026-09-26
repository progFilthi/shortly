package com.shortly.videoservice.security;

import com.shortly.contracts.internal.InternalHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway-secret check is the whole reason a service behind the gateway can trust
 * {@code X-User-Id}. These tests cover it directly, since the controller slice tests deliberately
 * run with it disabled.
 */
class CallerIdentityFilterTest {

    private static final String SECRET = "s3cr3t-gateway-value";

    private static CallerIdentityFilter filterRequiring(String expectedSecret) {
        return new CallerIdentityFilter(expectedSecret, true);
    }

    @Test
    void rejectsARequestWithNoGatewaySecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.addHeader(InternalHeaders.USER_ID, "someone-else");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filterRequiring(SECRET).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("gateway-secret-invalid")
                // The caller's forged identity must not be honoured, so nothing downstream runs.
                .doesNotContain("someone-else");
        assertThat(chain.getRequest()).as("the request must not reach the handler").isNull();
    }

    @Test
    void rejectsARequestWithTheWrongSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, "guessing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterRequiring(SECRET).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void acceptsARequestCarryingTheConfiguredSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, SECRET);
        request.addHeader(InternalHeaders.USER_ID, "user-1");
        request.addHeader(InternalHeaders.USERNAME, "tester");
        request.addHeader(InternalHeaders.EMAIL, "tester@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filterRequiring(SECRET).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();

        Object published = request.getAttribute(CallerIdentity.REQUEST_ATTRIBUTE);
        assertThat(published).isInstanceOf(CallerIdentity.class);
        CallerIdentity identity = (CallerIdentity) published;
        assertThat(identity.userId()).isEqualTo("user-1");
        assertThat(identity.username()).isEqualTo("tester");
        assertThat(identity.email()).isEqualTo("tester@example.com");
    }

    @Test
    void publishesNoIdentityWhenTheGatewaySentNoUserHeader() throws Exception {
        // A public read with a valid secret but no subject. The attribute is simply absent, and
        // the argument resolver reports that as a routing error if a handler actually needed one.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/videos/abc");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterRequiring(SECRET).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(CallerIdentity.REQUEST_ATTRIBUTE)).isNull();
    }

    @Test
    void rejectsEverythingWhenNoSecretIsConfigured() throws Exception {
        // Fail closed. A service started without its secret would otherwise accept unsigned
        // requests, which is the exact hole the check exists to close.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterRequiring("").doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void aBlankSecretHeaderIsNotTreatedAsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.addHeader(InternalHeaders.GATEWAY_SECRET, " ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterRequiring(SECRET).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void theCheckCanBeDisabledForLocalDevelopmentOnly() throws Exception {
        // Documented escape hatch. It exists so a developer can run one service standalone, and it
        // is off by default everywhere else.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.addHeader(InternalHeaders.USER_ID, "user-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new CallerIdentityFilter("", false).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
