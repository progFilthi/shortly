package com.shortly.authservice.controller;

import com.shortly.authservice.dto.AuthResponse;
import com.shortly.authservice.dto.LoginRequest;
import com.shortly.authservice.dto.LogoutRequest;
import com.shortly.authservice.dto.RefreshRequest;
import com.shortly.authservice.dto.RegisterRequest;
import com.shortly.authservice.dto.UserProfileResponse;
import com.shortly.authservice.errors.GlobalExceptionHandler;
import com.shortly.authservice.exceptions.*;
import com.shortly.authservice.config.SecurityConfig;
import com.shortly.authservice.errors.GatewaySecretFilter;
import com.shortly.authservice.jwt.JwtTokenProvider;
import com.shortly.authservice.security.AuthenticatedUser;
import com.shortly.authservice.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth endpoints: status codes, and the error contract.
 *
 * <p>A slice rather than a full context load. The service layer is covered directly by
 * {@code AuthServiceLoginTest} and {@code RefreshTokenServiceTest}; this is about the HTTP surface
 * and the shape of the errors, which is what a client actually depends on.
 */
@WebMvcTest(AuthController.class)
// The real SecurityConfig, not Spring Boot's default. Importing it is what disables CSRF and
// permits /api/v1/auth/**, so these assertions test the actual security posture rather than a
// default that would reject every POST with an empty 403.
//
// The filters stay in the chain: SecurityConfig needs them as beans, and the gateway-secret filter
// is a no-op here because the test profile sets require-gateway-secret: false.
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class AuthControllerErrorMappingTest {

    private static final String VALID_PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    /**
     * The filters need a codec to construct. Mocked because these tests are about the controller
     * and the error contract; JwtCodecTest covers verification directly, including expiry,
     * forgery, and the alg:none bypass.
     * <p>
     * Unstubbed, so it returns an empty Optional - which is exactly right for these requests,
     * since none of them carry an Authorization header and the filter must pass them through.
     */
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    private static AuthResponse authResponse() {
        return new AuthResponse("access-token", "refresh-token", 900, 2_592_000,
                "user-1", "tester", "tester@example.com", Instant.parse("2026-01-15T12:00:00Z"));
    }

    /* ------------------------------- happy paths ------------------------------- */

    @Test
    void registerReturns201WithATokenPair() throws Exception {
        when(authService.register(any(), any(), any())).thenReturn(authResponse());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"tester","email":"tester@example.com",
                                 "password":"correct-horse-battery"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("access-token"))
                // The client needs the refresh token and the lifetimes; without expiresIn it can
                // only discover expiry by getting a 401.
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.userId").value("user-1"));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        // Meaningful only because the real SecurityConfig is loaded: /api/v1/auth/** is permitted,
        // so this asserts /me is the exception and is genuinely protected.
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturns200AndNot201() throws Exception {
        // 201 would claim a resource was created. Nothing was.
        when(authService.login(any(), any(), any())).thenReturn(authResponse());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"tester","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("access-token"));
    }

    @Test
    void refreshReturnsANewPair() throws Exception {
        when(authService.refresh(any(), any(), any())).thenReturn(authResponse());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"old-refresh-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void logoutReturns204WithNoBody() throws Exception {
        doNothing().when(authService).logout(any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"some-refresh-token","allDevices":false}
                                """))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Content-Type"));
    }

    @Test
    void logoutPassesTheAllDevicesIntentThrough() throws Exception {
        doNothing().when(authService).logout(any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"some-refresh-token","allDevices":true}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).logout(
                eq(new LogoutRequest("some-refresh-token", true)), any(), any());
    }

    @Test
    void meReturnsTheProfileForTheAuthenticatedCaller() throws Exception {
        when(authService.getProfile("user-1")).thenReturn(new UserProfileResponse(
                "user-1", "tester", "tester@example.com", null, null,
                Instant.parse("2026-01-15T12:00:00Z"), 2));

        mockMvc.perform(get("/api/v1/auth/me").with(authenticatedAs("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.activeSessionCount").value(2));

        // The id comes from the token subject, never from a path parameter or a client header.
        verify(authService).getProfile("user-1");
    }

    /** Puts a token-derived identity on the security context, as the JWT filter would. */
    private static RequestPostProcessor authenticatedAs(String userId) {
        var identity = new AuthenticatedUser(userId, "tester", "tester@example.com");
        var principal = new org.springframework.security.core.userdetails.User(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        identity, null, principal.getAuthorities()));
    }

    /* ----------------------------- validation errors ---------------------------- */

    @Test
    void rejectsABlankIdentifierWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"","password":"whatever"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void rejectsAMalformedEmailWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"tester","email":"not-an-email",
                                 "password":"correct-horse-battery"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    @Test
    void rejectsAUsernameWithIllegalCharacters() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bad name!","email":"tester@example.com",
                                 "password":"correct-horse-battery"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnparseableBodyWith400RatherThan500() throws Exception {
        // A client sending malformed JSON is a client bug. Answering 500 teaches it to retry.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    void rejectsAMissingRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    /* --------------------------- domain error mapping --------------------------- */

    @Test
    void wrongCredentialsAre401AndDoNotSayWhichFieldWasWrong() throws Exception {
        when(authService.login(any(), any(), any()))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"tester","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid-credentials"))
                // The whole point: no "no such user" variant to enumerate accounts with.
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }

    @Test
    void duplicateRegistrationIs409() throws Exception {
        when(authService.register(any(), any(), any()))
                .thenThrow(new CredentialsTakenException());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"tester","email":"taken@example.com",
                                 "password":"correct-horse-battery"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("username-or-email-taken"));
    }

    @Test
    void anAccountLockoutIs423AndCarriesTheCountdown() throws Exception {
        when(authService.login(any(), any(), any()))
                .thenThrow(new AccountLockedException(742));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"tester","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("account-locked"))
                // So the client can show "try again in 12 minutes" instead of a dead end.
                .andExpect(jsonPath("$.retryAfterSeconds").value(742));
    }

    @Test
    void aWeakPasswordIsA400WithTheMinimumInTheBody() throws Exception {
        when(authService.register(any(), any(), any()))
                .thenThrow(new PasswordTooWeakException(10));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"tester","email":"tester@example.com","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.minLength").value(10));
    }

    @Test
    void anInvalidRefreshTokenIs401() throws Exception {
        when(authService.refresh(any(), any(), any()))
                .thenThrow(new RefreshTokenInvalidException());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"stolen-or-expired"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("refresh-token-invalid"));
    }

    @Test
    void logoutSucceedsEvenWithAnAlreadyDeadToken() throws Exception {
        // The service deliberately does not throw for an unresolvable token. Asserted here
        // because a 401 from logout would leave a client that lost the response unable to
        // sign out.
        doNothing().when(authService).logout(any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"long-dead","allDevices":false}
                                """))
                .andExpect(status().isNoContent());
    }

    /* --------------------------------- fallback --------------------------------- */

    @Test
    void anUnexpectedFaultIs500WithATraceIdAndNoInternalDetail() throws Exception {
        when(authService.login(any(), any(), any()))
                .thenThrow(new IllegalStateException("connection pool exhausted at /var/run/x.sock"));

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"tester","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // The message names internal paths and must not leak, but the trace id must be present so
        // the failure is correlatable.
        assertThat(body)
                .doesNotContain("connection pool exhausted")
                .doesNotContain("/var/run/x.sock")
                .contains("traceId");
    }

    @Test
    void anUnknownPathIs404ForAnAuthenticatedCaller() throws Exception {
        // Authenticated deliberately. Every route in this service requires a token, so an
        // anonymous request is stopped by the security layer and never reaches routing - which
        // means the 404 handler is only reachable with a token, and only this test can prove it
        // is mapped correctly.
        mockMvc.perform(get("/api/v1/nope").with(authenticatedAs("user-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void anUnknownPathUnderAuthIs401RatherThan404() throws Exception {
        // Deliberate. The whole /api/v1/auth prefix requires authentication, so an anonymous
        // caller cannot map out which auth routes exist. A 404 here would confirm that
        // /api/v1/auth/reset-password does not exist, which is information worth withholding.
        mockMvc.perform(get("/api/v1/auth/nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theWrongMethodIs405AndAdvertisesWhatIsAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").with(authenticatedAs("user-1")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method-not-allowed"))
                .andExpect(header().exists("Allow"));
    }
}
