package com.shortly.apigateway.config;

import com.shortly.jwt.JwtCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway's JWT codec.
 *
 * <p>Built from the same signing secret the auth service mints with, and using the same
 * {@link JwtCodec} implementation. That is the point of sharing it: a second, subtly different
 * verification path is how a gateway ends up accepting a token the issuer never produced.
 */
@Configuration
public class GatewayConfig {

    @Bean
    public JwtCodec jwtCodec(@Value("${jwt.secret}") String secret,
                             @Value("${gateway.jwt-issuer:shortly}") String issuer) {
        return new JwtCodec(secret, issuer);
    }
}
