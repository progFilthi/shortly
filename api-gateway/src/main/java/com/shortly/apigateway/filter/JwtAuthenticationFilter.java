package com.shortly.apigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private SecretKey getSigningKey(){
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }


    @Override
    protected boolean  shouldNotFilter(HttpServletRequest request){
        String path = request.getRequestURI();

        return path.startsWith("/api/v1/auth");
    }


    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull  HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if(authHeader == null || !authHeader.startsWith("Bearer ")){
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("Missing or Invalid authorization header.");
            return;

        }

        String token = authHeader.substring(7);


        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();


            HttpServletRequestWrapper mutatedRequest = getMutatedRequest(request, claims);

            filterChain.doFilter(mutatedRequest, response);

        }catch (Exception e) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("Invalid or expired JWT token");
        }

    }

    private static HttpServletRequestWrapper getMutatedRequest(HttpServletRequest request, Claims claims) {
        String userId = claims.getSubject();

        /*
        * Mutate request headers to append X-User-Id
        *
        * */

        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {

                if( "X-User-Id".equalsIgnoreCase(name) ){
                    return userId;
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaderNames(){
                List<String > names = Collections.list(super.getHeaderNames());
                names.add("X-User-Id");
                return Collections.enumeration(names);
            }

        };
    }
}
