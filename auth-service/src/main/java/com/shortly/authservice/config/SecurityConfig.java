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

/** Stateless bearer-token security, and the two filters that implement it. */
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
                /** Enumerated rather than a blanket "/api/v1/auth/**" permit. A wildcard permit would also cover
                 * /api/v1/auth/me, whose handler needs an authenticated principal. */
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
                // Both handlers write a problem document, not just a status.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
                        .accessDeniedHandler((request, response, denied) ->
                                ProblemResponses.write(response, HttpStatus.FORBIDDEN,
                                        ApiError.ACCESS_DENIED.wireValue(),
                                        "You do not have access to this resource.")))
                /** Both anchored to a known Spring Security filter. addFilterBefore resolves the anchor's order
                 * check first means an unsigned request is rejected before any token is parsed. */
                .addFilterBefore(gatewaySecretFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> {
                        }))
                .build();
    }
}
