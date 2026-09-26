package com.shortly.authservice.config;

import com.shortly.authservice.errors.GatewaySecretFilter;
import com.shortly.authservice.jwt.JwtTokenProvider;
import com.shortly.authservice.security.JwtAuthenticationFilter;
import com.shortly.authservice.security.ProblemAuthenticationEntryPoint;
import com.shortly.authservice.security.ProblemResponses;
import com.shortly.contracts.errors.ApiError;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless bearer-token security, and the two filters that implement it.
 *
 * <p>No session and no CSRF token: the client holds a token and presents it explicitly, so there
 * is no ambient authority for CSRF to protect. Disabling CSRF is correct here and would be a bug
 * in a cookie-authenticated service.
 *
 * <p>Two tiers of trust:
 * <ul>
 *   <li>{@code /api/v1/auth/**} — callable by anyone. Register, login, refresh and logout are
 *       unauthenticated by definition; logout proves itself with the refresh token in its body
 *       rather than a header.</li>
 *   <li>everything else — needs a valid access token and a valid gateway secret. The second
 *       requirement is what stops a caller reaching this port directly from asking for another
 *       user's data with a self-minted identity header.</li>
 * </ul>
 *
 * <p>The filters are declared here as beans rather than as {@code @Component}s. A {@code Filter}
 * bean is auto-registered in the servlet chain by Boot, so a component-scanned filter that is also
 * added to the security chain gets registered twice and Spring Security cannot resolve a single
 * order for it. Owning them here makes the chain the single registration point.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptCost());
    }

    @Bean
    public GatewaySecretFilter gatewaySecretFilter(AuthProperties properties) {
        return new GatewaySecretFilter(properties);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                                            AuthProperties properties) {
        return new JwtAuthenticationFilter(tokenProvider, properties);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            GatewaySecretFilter gatewaySecretFilter) throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                /*
                 * Enumerated rather than a blanket "/api/v1/auth/**" permit.
                 *
                 * A wildcard permit would also cover /api/v1/auth/me, whose handler needs an
                 * authenticated principal. An anonymous caller would get past the security layer
                 * and then fault inside the controller, which surfaces as a 500 for what is
                 * really a 401.
                 */
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/actuator/**")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/**").authenticated()
                        .anyRequest().authenticated())
                // Both handlers write a problem document, not just a status. The stock entry
                // point returns a bodiless 401, which is the one shape a client cannot parse -
                // and the most common one it will meet.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
                        .accessDeniedHandler((request, response, denied) ->
                                ProblemResponses.write(response, HttpStatus.FORBIDDEN,
                                        ApiError.ACCESS_DENIED.wireValue(),
                                        "You do not have access to this resource.")))
                /*
                 * Both anchored to a known Spring Security filter. addFilterBefore resolves the
                 * anchor's order from Spring's own registry, so anchoring to a custom filter
                 * class fails at startup with "does not have a registered order".
                 *
                 * Insertion order is the execution order, so registering the gateway check first
                 * means an unsigned request is rejected before any token is parsed.
                 */
                .addFilterBefore(gatewaySecretFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> {
                        }))
                .build();
    }
}
