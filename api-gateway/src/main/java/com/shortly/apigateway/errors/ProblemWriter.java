package com.shortly.apigateway.errors;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * Writes a problem document from inside a servlet filter.
 *
 * <p>Filters run before {@code @RestControllerAdvice}, so an error raised here has no handler to
 * catch it. The body shape matches what the services emit, so a client sees one error format no
 * matter which hop rejected it.
 */
public final class ProblemWriter {

    private ProblemWriter() {
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
     * Escapes for a JSON string literal.
     * <p>
     * Detail can quote caller input, and an unescaped quote yields a malformed body at best and
     * reflected content at worst.
     */
    static String escape(String value) {
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
