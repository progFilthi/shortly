package com.shortly.authservice.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms}")
    private long jwtExpiration;

    private SecretKey getSigningKey() {
       byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
       return Keys.hmacShaKeyFor(keyBytes);
    }


    public String generateToken(String userId, String email){

        Instant now = Instant.now();

        Instant expiryInstant = now.plusMillis(jwtExpiration);

        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiryInstant))
                .signWith(getSigningKey())
                .compact();


    }
}