package com.shortly.authservice.security;

import com.shortly.contracts.errors.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Writes a problem document for requests rejected by the security layer.
 *
 * <p>Spring Security's default entry point sets the status and nothing else, so a client that
 * forgot its token gets a bodiless 401. That is the one response shape it cannot parse, and it
 * is the most common one a mobile client will hit. Handing the same body the exception handler
 * produces means a client parses every failure the same way, whoever rejected it.
 *
 * <p>Used instead of {@code HttpStatusEntryPoint}, which cannot write a body.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String detail = authException == null || authException.getMessage() == null
                ? "Authentication is required."
                : authException.getMessage();

        ProblemResponses.write(response,
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                ApiError.UNAUTHENTICATED.wireValue(),
                detail);
    }
}
