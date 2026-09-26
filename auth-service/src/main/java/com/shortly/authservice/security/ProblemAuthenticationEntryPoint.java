package com.shortly.authservice.security;

import com.shortly.contracts.errors.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** Writes a problem document for requests rejected by the security layer. Used instead of {@code
 * HttpStatusEntryPoint}, which cannot write a body. */
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
