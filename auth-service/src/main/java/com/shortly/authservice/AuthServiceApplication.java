package com.shortly.authservice;

import com.shortly.authservice.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    /** A single injected clock. Token expiry and account lockouts are both time-dependent, and reading
     * {@code Instant.now()} inline would make them impossible to test without sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
