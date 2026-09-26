package com.shortly.apigateway.config;

import com.shortly.jwt.JwtCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gateway's JWT codec. */
@Configuration
public class GatewayConfig {

    @Bean
    public JwtCodec jwtCodec(@Value("${jwt.secret}") String secret,
                             @Value("${gateway.jwt-issuer:shortly}") String issuer) {
        return new JwtCodec(secret, issuer);
    }
}
