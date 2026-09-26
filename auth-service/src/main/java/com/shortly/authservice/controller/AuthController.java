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

/** Authentication endpoints. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** Creates an account and signs the user straight in. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        AuthResponse response = authService.register(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Verifies credentials and issues a token pair. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest)));
    }

    /** Exchanges a refresh token for a fresh pair. The previous access token stays valid until it
     * stateless from the gateway's point of view. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refresh(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest)));
    }

    /** Ends the current session, or every session for the account. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request,
                                       HttpServletRequest httpRequest) {
        authService.logout(request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** The signed-in user's own profile. Identified from the verified token, never from a path
     * parameter or a header the caller supplied. */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(authService.getProfile(principal.userId()));
    }

    /** Best-effort client IP. {@code X-Forwarded-For} is the left-most entry when present, because that
     * is the original client. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
