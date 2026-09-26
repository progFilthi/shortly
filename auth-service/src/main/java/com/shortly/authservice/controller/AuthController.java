package com.shortly.authservice.controller;

import com.shortly.authservice.dto.*;
import com.shortly.authservice.security.AuthenticatedUser;
import com.shortly.authservice.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication endpoints.
 *
 * <p>Client context (user agent, IP) is read here and passed down explicitly, rather than the
 * service reaching for the current request. That keeps the service callable from a test or a
 * future non-HTTP entry point without a servlet context.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Creates an account and signs the user straight in.
     * <p>
     * Returns a token pair rather than requiring a follow-up login: the user has just proven they
     * hold a valid email by completing registration, so making them type their password again is
     * friction with no security benefit.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        AuthResponse response = authService.register(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Verifies credentials and issues a token pair.
     * <p>
     * Returns 200 rather than 201: no resource was created.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest)));
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     * <p>
     * The previous access token stays valid until it expires; there is no denylist to consult and
     * that is the deliberate trade for keeping refresh stateless from the gateway's point of view.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refresh(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest)));
    }

    /**
     * Ends the current session, or every session for the account.
     * <p>
     * 204 with no body, and idempotent: signing out twice, or signing out with a token that is
     * already dead, both succeed. A client retrying a logout whose response was lost must not be
     * left unable to sign out.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request,
                                       HttpServletRequest httpRequest) {
        authService.logout(request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * The signed-in user's own profile.
     * <p>
     * Identified from the verified token, never from a path parameter or a header the caller
     * supplied. A client asking for someone else's profile is asking the wrong service.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(authService.getProfile(principal.userId()));
    }

    /**
     * Best-effort client IP.
     * <p>
     * {@code X-Forwarded-For} is the left-most entry when present, because that is the original
     * client. Only used to annotate sessions for abuse triage, never for authorization, so an
     * attacker who spoofs it gains nothing.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
