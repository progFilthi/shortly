package com.shortly.authservice.service;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.dto.*;
import com.shortly.authservice.entity.RefreshToken;
import com.shortly.authservice.entity.User;
import com.shortly.authservice.exceptions.*;
import com.shortly.authservice.jwt.JwtTokenProvider;
import com.shortly.authservice.mapper.UserMapper;
import com.shortly.authservice.repository.RefreshTokenRepository;
import com.shortly.authservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;
    private final AuthProperties properties;
    private final LoginAttemptService loginAttempts;
    private final Clock clock;

    /** Single constructor, taking the injected {@link Clock}. Deliberately not a convenience overload
     * plausible. */
    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenService refreshTokenService,
                       UserMapper userMapper,
                       AuthProperties properties,
                       LoginAttemptService loginAttempts,
                       Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.userMapper = userMapper;
        this.properties = properties;
        this.loginAttempts = loginAttempts;
        this.clock = clock;
    }

    /* --------------------------------- register --------------------------------- */

    @Transactional
    public AuthResponse register(RegisterRequest request, String userAgent, String ipAddress) {
        validatePasswordLength(request.password());

        // Pre-check for a clear error message, but it is not the guard. Two concurrent registrations of
        // the same email can both pass it;
        if (userRepository.existsByEmailIgnoreCase(request.email())
                || userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new CredentialsTakenException();
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));

        try {
            User saved = userRepository.saveAndFlush(user);
            log.info("Registered user {} ({})", saved.getUsername(), saved.getId());
            return issueSession(saved, userAgent, ipAddress);
        } catch (DataIntegrityViolationException e) {
            // Lost the race. Reported identically to the pre-check so a client cannot use the two paths to
            // distinguish "taken a moment ago" from "taken long ago".
            throw new CredentialsTakenException();
        }
    }

    /* ----------------------------------- login ----------------------------------- */

    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent, String ipAddress) {
        Instant now = clock.instant();

        Optional<User> found = resolveIdentifier(request.identifier());

        /** Hash a throwaway value when the account does not exist, so the response takes the same time
         * whether or not the identifier is registered. */
        if (found.isEmpty()) {
            passwordEncoder.encode(request.password());
            throw new InvalidCredentialsException();
        }

        User user = found.get();

        if (user.isLockedAt(now)) {
            log.warn("Sign-in refused for locked account {}", user.getUsername());
            throw new AccountLockedException(loginAttempts.remainingLockoutSeconds(user.getId()));
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            // Recorded in its own transaction. This method then throws
            if (loginAttempts.recordFailure(user.getId())) {
                throw new AccountLockedException(properties.lockoutDuration().toSeconds());
            }
            throw new InvalidCredentialsException();
        }

        loginAttempts.recordSuccess(user.getId());

        log.info("Sign-in succeeded for {}", user.getUsername());
        return issueSession(user, userAgent, ipAddress);
    }

    /** Finds the account behind a username-or-email identifier. */
    private Optional<User> resolveIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        String trimmed = identifier.trim();
        Optional<User> byUsername = userRepository.findByUsernameIgnoreCase(trimmed);
        Optional<User> byEmail = userRepository.findByEmailIgnoreCase(trimmed);
        return byUsername.isPresent() ? byUsername : byEmail;
    }

    /* ---------------------------------- refresh ---------------------------------- */

    /** Exchanges a refresh token for a new pair. */
    @Transactional
    public AuthResponse refresh(RefreshRequest request, String userAgent, String ipAddress) {
        RefreshTokenService.IssuedToken issued =
                refreshTokenService.rotate(request.refreshToken(), userAgent, ipAddress);

        User user = userRepository.findById(issued.record().getUserId())
                .orElseThrow(RefreshTokenInvalidException::new);

        return userMapper.toAuthResponse(
                user,
                tokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getEmail()),
                issued.token(),
                properties.accessTokenTtlSeconds(),
                properties.refreshTokenTtlSeconds(),
                clock.instant());
    }

    /* ---------------------------------- logout ----------------------------------- */

    /** Ends one session, or all of them. Idempotent by design: a client that has lost its response
     * retries, and signing out twice must not be an error. */
    @Transactional
    public void logout(LogoutRequest request, String userAgent, String ipAddress) {
        String hash = RefreshTokenService.hash(request.refreshToken());

        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            if (request.allDevices()) {
                int revoked = refreshTokenService.revokeAllForUser(token.getUserId());
                log.info("Revoked {} session(s) for user {}", revoked, token.getUserId());
            } else {
                int revoked = refreshTokenService.revokeFamily(token.getFamilyId());
                log.info("Revoked session family {} for user {} ({} token(s))",
                        token.getFamilyId(), token.getUserId(), revoked);
            }
        });
    }

    /* --------------------------------- profile ----------------------------------- */

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());

        long activeSessions = refreshTokenRepository
                .countByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(user.getId());

        return userMapper.toProfileResponse(user, activeSessions);
    }

    /* --------------------------------- internals --------------------------------- */

    private AuthResponse issueSession(User user, String userAgent, String ipAddress) {
        String accessToken = tokenProvider.generateAccessToken(
                user.getId(), user.getUsername(), user.getEmail());
        RefreshTokenService.IssuedToken refresh = refreshTokenService.issue(
                user.getId(), null, userAgent, ipAddress);

        return userMapper.toAuthResponse(
                user, accessToken, refresh.token(),
                properties.accessTokenTtlSeconds(), properties.refreshTokenTtlSeconds(),
                clock.instant());
    }

    /** Enforces the configured minimum length, which cannot be expressed as a bean constraint because
     * the threshold is configuration rather than a constant. */
    private void validatePasswordLength(String password) {
        if (password != null && password.length() < properties.minPasswordLength()) {
            throw new PasswordTooWeakException(properties.minPasswordLength());
        }
    }
}
