package com.shortly.authservice.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * Writes a problem response from inside a servlet filter.
 *
 * <p>Filters run before {@code @RestControllerAdvice}, so an error raised here has no handler to
 * catch it. This produces the same body shape the handler does, so a client sees one error format
 * whether the failure came from a filter or a controller.
 * <p>
 * Hand-rolled rather than delegating to Spring MVC, because forwarding to
 * {@code /error} from a filter to get a body is more machinery than the problem warrants.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        int statusCode = status.value();
        response.setStatus(statusCode);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"title":"%s","status":%d,"code":"%s","detail":"%s"}"""
                .formatted(escape(code), statusCode, escape(code), escape(detail)));
    }

    /**
     * Escapes a value for embedding in a JSON string literal.
     * <p>
     * Error detail can quote caller input - a rejected token, an identifier from a request body -
     * and an unescaped quote would produce a malformed body at best and reflected content at
     * worst.
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
